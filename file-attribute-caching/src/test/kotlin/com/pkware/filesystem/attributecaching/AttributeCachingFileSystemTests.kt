package com.pkware.filesystem.attributecaching

import com.google.common.jimfs.Jimfs
import com.google.common.truth.ComparableSubject
import com.google.common.truth.Truth.assertThat
import org.apache.commons.io.IOUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.CopyOption
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.stream.Stream
import kotlin.io.path.exists

class AttributeCachingFileSystemTests {

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `cache gets initialized only after file exists and getPath is called`(fileSystem: FileSystem) {
        val tempDirPath = fileSystem.getPath("temp")
        Files.createDirectory(tempDirPath)
        var testPath = fileSystem.getPath("$tempDirPath${fileSystem.separator}testfile.txt")
        Files.createFile(testPath)

        AttributeCachingFileSystem.wrapping(fileSystem).use {

            assertThat(testPath).isNotInstanceOf(AttributeCachingPath::class.java)

            // get filesystem attribute caching path
            // get tha path again, it should now initialize since it exists
            testPath = it.getPath("$tempDirPath${it.separator}testfile.txt")
            assertThat(testPath).isInstanceOf(AttributeCachingPath::class.java)
            val cachingPath = testPath as AttributeCachingPath
            assertThat(cachingPath.isCachedInitialized()).isTrue()

            // now read attributes from caching path and verify they dont change
            val attributesMap = Files.readAttributes(cachingPath, "*")
            assertThat(attributesMap).isNotEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `cache gets initialized only after file exists and convertToCachingPath is called`(fileSystem: FileSystem) {
        val tempDirPath = fileSystem.getPath("temp")
        Files.createDirectory(tempDirPath)
        val testPath = fileSystem.getPath("$tempDirPath${fileSystem.separator}testfile.txt")
        Files.createFile(testPath)

        AttributeCachingFileSystem.wrapping(fileSystem).use {

            assertThat(testPath).isNotInstanceOf(AttributeCachingPath::class.java)

            // get and verify filesystem attribute caching path
            var cachingPath = it.convertToCachingPath(testPath)
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            cachingPath = cachingPath as AttributeCachingPath
            assertThat(cachingPath.isCachedInitialized()).isTrue()

            // now read attributes from caching path and verify they are populated
            val attributesMap = Files.readAttributes(cachingPath, "*")
            assertThat(attributesMap).isNotEmpty()
        }
    }

    @Test
    fun `create java io tmpdir directory with jimfs does not throw a ProviderMismatchException`() {
        val filesystem = AttributeCachingFileSystem.wrapping(Jimfs.newFileSystem())
        assertDoesNotThrow {
            val javaTmpPath = filesystem.getPath(System.getProperty("java.io.tmpdir"))

            if (!Files.exists(javaTmpPath)) {
                Files.createDirectories(javaTmpPath)
            }
            assertThat(Files.exists(javaTmpPath)).isTrue()
            Files.deleteIfExists(javaTmpPath)
        }
        filesystem.close()
    }

    @Test
    fun `create java io tmpdir file with default filesystem does not throw a NullPointerException`() {
        AttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
            assertDoesNotThrow {
                val javaTmpPath = it.getPath(System.getProperty("java.io.tmpdir"))
                val tempFilePath = Files.createTempFile(javaTmpPath, "test", ".txt")
                assertThat(Files.exists(tempFilePath)).isTrue()
                Files.deleteIfExists(tempFilePath)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `resolve returns a cachingPath`(fileSystem: FileSystem) = fileSystem.use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val cachingPath = it.getPath("test.txt")

            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }
            val otherPath = jimfs.getPath("temp")
            Files.createDirectory(otherPath)
            val resolvedPath = cachingPath.resolve(otherPath)
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(resolvedPath).isInstanceOf(AttributeCachingPath::class.java)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `getName returns a cachingPath`(fileSystem: FileSystem) = fileSystem.use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val cachingPath = it.getPath("test.txt")
            val closestToRootPathName = cachingPath.getName(0)
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(closestToRootPathName).isInstanceOf(AttributeCachingPath::class.java)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `normalize returns a cachingPath and copies attributes`(fileSystem: FileSystem) = fileSystem.use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val tempParentDirPath = it.getPath("temp")
            val tempDirPath = it.getPath("temp${it.separator}test")
            Files.createDirectory(tempParentDirPath)
            Files.createDirectory(tempDirPath)
            val cachingPath = it.getPath(
                "temp${it.separator}.${it.separator}.${it.separator}test${it.separator}test.txt"
            )
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)

            val normalizedPath = cachingPath.normalize()
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(normalizedPath).isInstanceOf(AttributeCachingPath::class.java)

            // now read attributes from absolutePath path
            val attributesMap = Files.readAttributes(normalizedPath, "*")

            assertThat(attributesMap["lastModifiedTime"]).isEqualTo(testDateFileTime)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `subpath returns a cachingPath`(fileSystem: FileSystem) = fileSystem.use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val tempParentDirPath = it.getPath("temp")
            val tempDirPath = it.getPath("temp${it.separator}test")
            Files.createDirectory(tempParentDirPath)
            Files.createDirectory(tempDirPath)
            val cachingPath = it.getPath("$tempDirPath${it.separator}test.txt")
            val nameCount = cachingPath.nameCount
            val subPath = cachingPath.subpath(nameCount - 2, nameCount - 1)
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(subPath).isInstanceOf(AttributeCachingPath::class.java)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `resolveSibling returns a cachingPath`(fileSystem: FileSystem) = fileSystem.use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val tempParentDirPath = it.getPath("temp")
            val tempDirPath = it.getPath("temp${it.separator}test")
            Files.createDirectory(tempParentDirPath)
            Files.createDirectory(tempDirPath)
            val cachingPath = it.getPath("$tempDirPath${it.separator}test.txt")
            val otherPath = it.getPath("test2.txt")
            val resolvedSiblingPath = cachingPath.resolveSibling(otherPath)
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(resolvedSiblingPath).isInstanceOf(AttributeCachingPath::class.java)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `relativize returns a cachingPath`(fileSystem: FileSystem) = fileSystem.use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val tempGrandparentDirPath = it.getPath("temp")
            val tempParentDirPath = it.getPath("temp${it.separator}test")
            val tempDirPath = it.getPath("temp${it.separator}test${it.separator}test2")
            Files.createDirectory(tempGrandparentDirPath)
            Files.createDirectory(tempParentDirPath)
            Files.createDirectory(tempDirPath)
            val cachingPath = it.getPath("$tempParentDirPath${it.separator}test.txt")
            val relativizedPath = cachingPath.relativize(tempDirPath)
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(relativizedPath).isInstanceOf(AttributeCachingPath::class.java)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `toAbsolutePath returns a cachingPath and copies attributes`(fileSystem: FileSystem) = fileSystem.use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val cachingPath = it.getPath("test.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)

            val absolutePath = cachingPath.toAbsolutePath()
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(absolutePath).isInstanceOf(AttributeCachingPath::class.java)

            // now read attributes from absolutePath path
            val attributesMap = Files.readAttributes(absolutePath, "*")

            assertThat(attributesMap["lastModifiedTime"]).isEqualTo(testDateFileTime)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `toRealPath returns a cachingPath and copies attributes`(fileSystem: FileSystem) = fileSystem.use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val cachingPath = it.getPath("test.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)

            val realPath = cachingPath.toRealPath()
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(realPath).isInstanceOf(AttributeCachingPath::class.java)

            // now read attributes from absolutePath path
            val attributesMap = Files.readAttributes(realPath, "*")

            assertThat(attributesMap["lastModifiedTime"]).isEqualTo(testDateFileTime)
        }
    }

    @ParameterizedTest
    @MethodSource("allTypes")
    fun <A : BasicFileAttributes?> `read attributes by class type from provider`(
        type: Class<A>,
        fileSystem: () -> FileSystem
    ) = fileSystem().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            // get filesystem attribute caching path
            val cachingPath = it.getPath("testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }
            // read filesystem attributes for path from provider with the given class type
            val attributes = Files.readAttributes(cachingPath, type)
            // verify that attribute is "right" type returned from the provider
            assertThat(attributes).isInstanceOf(type)
        }
    }

    @ParameterizedTest
    @MethodSource("allNames")
    fun `verify read attributes throws NoSuchFileException on a file that doesn't exist`(
        attributes: String,
        fileSystem: () -> FileSystem,
    ) = fileSystem().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val file = it.getPath("xls.xls")
            val e = assertThrows<NoSuchFileException> { Files.readAttributes(file, attributes) }
            assertThat(e).hasMessageThat().contains("xls.xls")
        }
    }

    @ParameterizedTest
    @MethodSource("allNamesWithMapVerification")
    fun `read attributes by name from provider`(
        attributeName: String,
        expectedMapSize: Int,
        attributeViewName: String,
        fileSystem: () -> FileSystem
    ) = fileSystem().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            // get filesystem attribute caching path
            val cachingPath = it.getPath("testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }
            // read filesystem attributes for path from provider with the given name
            val attributesMap = Files.readAttributes(cachingPath, attributeName)
            // verify that attribute is "right" type returned from the provider
            assertThat(attributesMap).isInstanceOf(MutableMap::class.java)
            assertThat(attributesMap.size).isEqualTo(expectedMapSize)
            // verify attribute map keys do not contain the attribute view qualifier (attributeViewName)
            for (entry in attributesMap) {
                assertThat(entry.key).doesNotContain(attributeViewName)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("posixFileSystems")
    fun `set posix attributes for path`(fileSystem: () -> FileSystem) = fileSystem().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {

            // get filesystem attribute caching path
            val cachingPath = it.getPath("testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }
            val lookupService = it.userPrincipalLookupService
            val owner = lookupService.lookupPrincipalByName("testUser")
            val group = lookupService.lookupPrincipalByGroupName("testGroup")
            val permissions = EnumSet.of(
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_WRITE,
            )

            Files.setAttribute(cachingPath, "posix:owner", owner)
            Files.setAttribute(cachingPath, "posix:group", group)
            Files.setAttribute(cachingPath, "posix:permissions", permissions)
            val attributesMap = Files.readAttributes(cachingPath, "posix:*")

            assertThat(attributesMap.size).isEqualTo(12)
            val ownerUserPrincipal = attributesMap["owner"] as UserPrincipal
            assertThat(ownerUserPrincipal.name).isEqualTo(owner.name)
            val groupUserPrincipal = attributesMap["group"] as GroupPrincipal
            assertThat(groupUserPrincipal.name).isEqualTo(group.name)
            @Suppress("UNCHECKED_CAST")
            assertThat(
                PosixFilePermissions.toString(attributesMap["permissions"] as? MutableSet<PosixFilePermission>)
            ).isEqualTo(
                PosixFilePermissions.toString(permissions)
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dos:readonly", "dos:hidden", "dos:archive"])
    fun `set and read dos boolean attributes for path`(attributeName: String) = windowsJimfs().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {

            // get filesystem attribute caching path
            val cachingPath = it.getPath("testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            Files.setAttribute(cachingPath, attributeName, true)
            val attributesMap = Files.readAttributes(cachingPath, attributeName)

            assertThat(attributesMap.size).isEqualTo(1)

            val maplookUpAttributeName = attributeName.substringAfter("dos:")
            assertThat(attributesMap[maplookUpAttributeName]).isEqualTo(true)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `cached attributes do not get modified by concurrent operation if cache has not expired`(
        fileSystem: FileSystem
    ) {
        val tempDirPath = fileSystem.getPath("temp")

        AttributeCachingFileSystem.wrapping(fileSystem).use {

            // get filesystem attribute caching path
            Files.createDirectory(tempDirPath)
            val cachingPath = it.getPath("$tempDirPath${it.separator}testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            val lastAccessTime = FileTime.from(
                SimpleDateFormat("MM/dd/yyyy, hh:mm:ss a").parse("01/01/1971, 08:34:27 PM").toInstant()
            )
            val creationTime = FileTime.from(
                SimpleDateFormat("MM/dd/yyyy, hh:mm:ss a").parse("01/01/1969, 06:34:27 PM").toInstant()
            )

            // set and populate cache attributes, 3 different times
            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastAccessTime", lastAccessTime)
            Files.setAttribute(cachingPath, "creationTime", creationTime)

            // simulate concurrent modification on default filesystem
            val concurrentPath = fileSystem.getPath(
                "$tempDirPath${fileSystem.separator}testfile.txt"
            )
            val concurrentTime = FileTime.from(
                SimpleDateFormat("MM/dd/yyyy, hh:mm:ss a").parse("01/01/2001, 01:11:11 PM").toInstant()
            )
            // should set the attributes directly without setting the cache, set all to same time
            Files.setAttribute(concurrentPath, "lastModifiedTime", concurrentTime)
            Files.setAttribute(concurrentPath, "lastAccessTime", concurrentTime)
            Files.setAttribute(concurrentPath, "creationTime", concurrentTime)

            // now read attributes from caching path and verify they dont change
            val attributesMap = Files.readAttributes(cachingPath, "*")

            assertThat(attributesMap["lastModifiedTime"]).isEqualTo(testDateFileTime)
            assertThat(attributesMap["lastAccessTime"]).isEqualTo(lastAccessTime)
            assertThat(attributesMap["creationTime"]).isEqualTo(creationTime)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystemsWithCopyOption")
    fun `copy file from source to target`(
        option: CopyOption,
        fileSystem: () -> FileSystem
    ) = fileSystem().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            // get filesystem attribute caching path
            val cachingPath = it.getPath("testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }
            Files.setAttribute(cachingPath, "creationTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastAccessTime", testDateFileTime)

            val destinationCachingPath = it.getPath("testfile2.txt")

            assertThat(destinationCachingPath.exists()).isEqualTo(false)

            Files.copy(cachingPath, destinationCachingPath, option)

            assertThat(cachingPath.exists()).isEqualTo(true)
            assertThat(destinationCachingPath.exists()).isEqualTo(true)

            Files.newInputStream(destinationCachingPath).use { inputStream ->
                val bytes = IOUtils.toByteArray(inputStream)
                assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("hello")
            }

            val basicFileAttributes = Files.readAttributes(destinationCachingPath, "*")
            val creationTime = basicFileAttributes["creationTime"] as FileTime
            assertThat(creationTime).followedFlagRulesComparedTo(option, testDateFileTime)
            val lastModifiedTime = basicFileAttributes["lastModifiedTime"] as FileTime
            assertThat(lastModifiedTime).followedFlagRulesComparedTo(option, testDateFileTime)
            val lastAccessTime = basicFileAttributes["lastAccessTime"] as FileTime
            assertThat(lastAccessTime).followedFlagRulesComparedTo(option, testDateFileTime)
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystemsWithMoveOption")
    fun `move file from source to target`(
        option: CopyOption,
        fileSystem: () -> FileSystem
    ) = fileSystem().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            // get filesystem attribute caching path
            val cachingPath = it.getPath("testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }
            Files.setAttribute(cachingPath, "creationTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastAccessTime", testDateFileTime)

            // ensure temp directory exists
            Files.createDirectory(it.getPath("temp"))
            val destinationCachingPath = it.getPath("temp", "testfile2.txt")

            assertThat(destinationCachingPath.exists()).isEqualTo(false)

            Files.move(cachingPath, destinationCachingPath, option)

            assertThat(cachingPath.exists()).isEqualTo(false)

            Files.newInputStream(destinationCachingPath).use { inputStream ->
                val bytes = IOUtils.toByteArray(inputStream)
                assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("hello")
            }
            val basicFileAttributes = Files.readAttributes(destinationCachingPath, "*")

            // creation and move time are preserved for a move regardless of the option flag used
            assertThat(basicFileAttributes["creationTime"]).isEqualTo(testDateFileTime)
            assertThat(basicFileAttributes["lastModifiedTime"]).isEqualTo(testDateFileTime)
            val lastAccessTime = basicFileAttributes["lastAccessTime"] as FileTime
            assertThat(lastAccessTime).followedFlagRulesComparedTo(option, testDateFileTime)
        }
    }

    @DisabledOnOs(OS.MAC, OS.LINUX)
    @ParameterizedTest
    @MethodSource("hiddenTestPathsWindows")
    fun `file isHidden on windows`(fileName: String, expectedHidden: Boolean) = windowsJimfs().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val directoryName = "temp"
            Files.createDirectory(it.getPath(directoryName))
            val cachingPath = it.getPath(directoryName, fileName)
            if (fileName.isNotEmpty()) {
                Files.createFile(cachingPath)
                Files.newOutputStream(cachingPath).use { outputStream ->
                    outputStream.write("hello".toByteArray(Charsets.UTF_8))
                }
            }
            Files.setAttribute(cachingPath, "dos:hidden", true)
            assertThat(Files.isHidden(cachingPath)).isEqualTo(expectedHidden)
        }
    }

    @DisabledOnOs(OS.WINDOWS)
    @ParameterizedTest
    @MethodSource("hiddenTestPathsPosix")
    fun `file isHidden on unix and macOS`(
        fileName: String,
        expectedHidden: Boolean,
        fileSystem: () -> FileSystem
    ) = fileSystem().use { jimfs ->
        AttributeCachingFileSystem.wrapping(jimfs).use {
            val directoryName = "temp"
            Files.createDirectory(it.getPath(directoryName))
            val cachingPath = it.getPath(directoryName, fileName)
            assertThat(Files.isHidden(cachingPath)).isEqualTo(expectedHidden)
        }
    }

    companion object {
        @JvmStatic
        fun allTypes(): Stream<Arguments> = Stream.of(
            arguments(BasicFileAttributes::class.java, ::windowsJimfs),
            arguments(DosFileAttributes::class.java, ::windowsJimfs),
            arguments(PosixFileAttributes::class.java, ::linuxJimfs),
            arguments(PosixFileAttributes::class.java, ::osXJimfs),
        )

        @JvmStatic
        fun allNamesWithMapVerification(): Stream<Arguments> = Stream.of(
            arguments("*", 9, "basic", ::windowsJimfs),
            arguments("size", 1, "basic", ::windowsJimfs),
            arguments("size,lastModifiedTime,lastAccessTime", 3, "basic", ::windowsJimfs),
            arguments("basic:*", 9, "basic", ::windowsJimfs),
            arguments("basic:size", 1, "basic", ::windowsJimfs),
            arguments("basic:size,lastModifiedTime,lastAccessTime", 3, "basic", ::windowsJimfs),
            arguments("dos:*,", 13, "dos", ::windowsJimfs),
            arguments("dos:readonly", 1, "dos", ::windowsJimfs),
            arguments("dos:hidden,system,readonly", 3, "dos", ::windowsJimfs),
            arguments("posix:*", 12, "posix", ::linuxJimfs),
            arguments("posix:group", 1, "posix", ::linuxJimfs),
            arguments("posix:owner,group,permissions", 3, "posix", ::linuxJimfs),
            arguments("posix:*", 12, "posix", ::osXJimfs),
            arguments("posix:group", 1, "posix", ::osXJimfs),
            arguments("posix:owner,group,permissions", 3, "posix", ::osXJimfs),
        )

        @JvmStatic
        fun allNames(): Stream<Arguments> = Stream.of(
            arguments("*", ::windowsJimfs),
            arguments("dos:*", ::windowsJimfs),
            arguments("posix:*", ::linuxJimfs),
            arguments("posix:*", ::osXJimfs),
        )

        @JvmStatic
        fun allFileSystems(): Stream<Arguments> = Stream.of(
            arguments(windowsJimfs()),
            arguments(linuxJimfs()),
            arguments(osXJimfs()),
        )

        @JvmStatic
        fun posixFileSystems(): Stream<Arguments> = Stream.of(
            arguments(::linuxJimfs),
            arguments(::osXJimfs),
        )

        @JvmStatic
        fun hiddenTestPathsWindows(): Stream<Arguments> = Stream.of(
            arguments("test1.txt", true),
            // blank filesystem name for a directory, directories can never be hidden
            arguments("", false),
        )

        @JvmStatic
        fun hiddenTestPathsPosix(): Stream<Arguments> = Stream.of(
            arguments(".test1.txt", true, ::linuxJimfs),
            arguments(".test1.txt", true, ::osXJimfs),
            arguments("test2.txt", false, ::linuxJimfs),
            arguments("test2.txt", false, ::osXJimfs),
        )

        @JvmStatic
        fun allFileSystemsWithCopyOption(): Stream<Arguments> = Stream.of(
            arguments(StandardCopyOption.REPLACE_EXISTING, ::windowsJimfs),
            arguments(StandardCopyOption.COPY_ATTRIBUTES, ::windowsJimfs),
            arguments(StandardCopyOption.REPLACE_EXISTING, ::linuxJimfs),
            arguments(StandardCopyOption.COPY_ATTRIBUTES, ::linuxJimfs),
            arguments(StandardCopyOption.REPLACE_EXISTING, ::osXJimfs),
            arguments(StandardCopyOption.COPY_ATTRIBUTES, ::osXJimfs),
        )

        @JvmStatic
        fun allFileSystemsWithMoveOption(): Stream<Arguments> = Stream.of(
            arguments(StandardCopyOption.REPLACE_EXISTING, ::windowsJimfs),
            arguments(StandardCopyOption.COPY_ATTRIBUTES, ::windowsJimfs),
            arguments(StandardCopyOption.ATOMIC_MOVE, ::windowsJimfs),
            arguments(StandardCopyOption.REPLACE_EXISTING, ::linuxJimfs),
            arguments(StandardCopyOption.COPY_ATTRIBUTES, ::linuxJimfs),
            arguments(StandardCopyOption.ATOMIC_MOVE, ::linuxJimfs),
            arguments(StandardCopyOption.REPLACE_EXISTING, ::osXJimfs),
            arguments(StandardCopyOption.COPY_ATTRIBUTES, ::osXJimfs),
            arguments(StandardCopyOption.ATOMIC_MOVE, ::osXJimfs),
        )

        private fun ComparableSubject<FileTime>.followedFlagRulesComparedTo(
            options: CopyOption,
            expected: FileTime
        ) = if (options == StandardCopyOption.COPY_ATTRIBUTES) isEqualTo(expected) else isNotEqualTo(expected)
    }
}

/**
 * A [FileTime] representing the date "01/01/1970, 07:34:27 PM".
 */
private val testDateFileTime: FileTime = FileTime.from(
    SimpleDateFormat("MM/dd/yyyy, hh:mm:ss a").parse("01/01/1970, 07:34:27 PM").toInstant()
)
