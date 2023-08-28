package com.pkware.filesystem.attributecaching

import com.google.common.jimfs.Jimfs
import com.google.common.truth.ComparableSubject
import com.google.common.truth.Truth.assertThat
import org.apache.commons.io.IOUtils
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
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
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
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
import java.util.UUID
import java.util.stream.Stream
import kotlin.concurrent.thread
import kotlin.io.path.div
import kotlin.io.path.exists

class AttributeCachingFileSystemTests {

    @Test
    fun `attribute caching filesystem invoked many times across multiple threads does not throw`() {
        val defaultFileSystem = FileSystems.getDefault()
        val waitTimeMillis: Long = 50_000
        var caughtException: Exception? = null

        fun runTestThread(): Thread = thread {
            @Suppress("UnusedPrivateProperty")
            for (i in 1..100_000) {
                try {
                    AttributeCachingFileSystem.wrapping(defaultFileSystem).use { }
                } catch (e: Exception) {
                    // Need to catch and record the exception here because junit does not fail the test if an
                    // assertion fails inside a thread.
                    caughtException = e
                }
            }
        }

        val firstThread = runTestThread()
        val secondThread = runTestThread()
        val thirdThread = runTestThread()

        // Join threads out of order to try to catch concurrences, timeout after waitTimeMillis in cases of deadlock
        firstThread.join(waitTimeMillis)
        thirdThread.join(waitTimeMillis)
        secondThread.join(waitTimeMillis)

        assertThat(caughtException).isNull()
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `attributes are cached on demand when using getPath`(fileSystem: FileSystem) {
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
            assertThat(cachingPath.basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            // now read attributes from caching path and verify they dont change
            val attributesMap = Files.readAttributes(cachingPath, "*")
            assertThat(attributesMap).isNotEmpty()
            assertThat(cachingPath.basicAttributesCached).isTrue()
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `attributes are cached on demand when using convertToCachingPath`(fileSystem: FileSystem) {
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
            assertThat(cachingPath.basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            // now read attributes from caching path and verify they are populated
            val attributesMap = Files.readAttributes(cachingPath, "*")
            assertThat(attributesMap).isNotEmpty()
            assertThat(cachingPath.basicAttributesCached).isTrue()
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
    fun `resolve with a path returns a cachingPath`(fileSystem: FileSystem) =
        AttributeCachingFileSystem.wrapping(fileSystem).use {
            val cachingPath = it.getPath("test.txt")

            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }
            val otherPath = fileSystem.getPath("temp")
            Files.createDirectory(otherPath)
            val resolvedPath = cachingPath.resolve(otherPath)
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(resolvedPath).isInstanceOf(AttributeCachingPath::class.java)
        }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `resolve with a string returns a cachingPath`(fileSystem: FileSystem) =
        AttributeCachingFileSystem.wrapping(fileSystem).use {
            val cachingPath = it.getPath("test.txt")

            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }
            val otherPath = fileSystem.getPath("temp")
            Files.createDirectory(otherPath)
            val resolvedPath = cachingPath / "temp"
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(resolvedPath).isInstanceOf(AttributeCachingPath::class.java)
        }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `getName returns a cachingPath`(fileSystem: FileSystem) =
        AttributeCachingFileSystem.wrapping(fileSystem).use {
            val cachingPath = it.getPath("test.txt")
            val closestToRootPathName = cachingPath.getName(0)
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(closestToRootPathName).isInstanceOf(AttributeCachingPath::class.java)
        }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `normalize returns a cachingPath and copies attributes copies attributes if they were previously cached`(
        fileSystem: FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem).use {
        val tempParentDirPath = it.getPath("temp")
        val tempDirPath = it.getPath("temp${it.separator}test")
        Files.createDirectory(tempParentDirPath)
        Files.createDirectory(tempDirPath)
        val cachingPath = it.getPath(
            "temp${it.separator}.${it.separator}.${it.separator}test${it.separator}test.txt",
        )
        Files.createFile(cachingPath)
        Files.newOutputStream(cachingPath).use { outputStream ->
            outputStream.write("hello".toByteArray(Charsets.UTF_8))
        }

        // cache basic attributes with setAttribute lastModifiedTime
        Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)

        assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isTrue()
        assertThat(cachingPath.dosAttributesCached).isFalse()
        assertThat(cachingPath.posixAttributesCached).isFalse()
        assertThat(cachingPath.accessControlListOwnerCached).isFalse()
        assertThat(cachingPath.accessControlListEntriesCached).isFalse()

        val normalizedPath = cachingPath.normalize()

        assertThat(normalizedPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((normalizedPath as AttributeCachingPath).basicAttributesCached).isTrue()
        assertThat(normalizedPath.dosAttributesCached).isFalse()
        assertThat(normalizedPath.posixAttributesCached).isFalse()
        assertThat(normalizedPath.accessControlListOwnerCached).isFalse()
        assertThat(normalizedPath.accessControlListEntriesCached).isFalse()

        // write stuff to realPath to force lastModifiedTime update
        Files.newOutputStream(normalizedPath).use { outputStream ->
            outputStream.write("rewrite".toByteArray(Charsets.UTF_8))
        }

        // now read attributes from absolutePath path
        val attributesMap = Files.readAttributes(normalizedPath, "*")

        assertThat(attributesMap["lastModifiedTime"]).isEqualTo(testDateFileTime)
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `normalize returns a cachingPath and does not copy attributes if they were not previously cached`(
        fileSystem: FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem).use {
        val tempParentDirPath = it.getPath("temp")
        val tempDirPath = it.getPath("temp${it.separator}test")
        Files.createDirectory(tempParentDirPath)
        Files.createDirectory(tempDirPath)
        val cachingPath = it.getPath(
            "temp${it.separator}.${it.separator}.${it.separator}test${it.separator}test.txt",
        )
        Files.createFile(cachingPath)
        Files.newOutputStream(cachingPath).use { outputStream ->
            outputStream.write("hello".toByteArray(Charsets.UTF_8))
        }

        assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
        assertThat(cachingPath.dosAttributesCached).isFalse()
        assertThat(cachingPath.posixAttributesCached).isFalse()
        assertThat(cachingPath.accessControlListOwnerCached).isFalse()
        assertThat(cachingPath.accessControlListEntriesCached).isFalse()

        val normalizedPath = cachingPath.normalize()

        assertThat(normalizedPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((normalizedPath as AttributeCachingPath).basicAttributesCached).isFalse()
        assertThat(normalizedPath.dosAttributesCached).isFalse()
        assertThat(normalizedPath.posixAttributesCached).isFalse()
        assertThat(normalizedPath.accessControlListOwnerCached).isFalse()
        assertThat(normalizedPath.accessControlListEntriesCached).isFalse()
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `subpath returns a cachingPath`(fileSystem: FileSystem) = AttributeCachingFileSystem.wrapping(fileSystem).use {
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

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `resolveSibling with a path returns a cachingPath`(fileSystem: FileSystem) =
        AttributeCachingFileSystem.wrapping(fileSystem).use {
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

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `resolveSibling with a string returns a cachingPath`(fileSystem: FileSystem) =
        AttributeCachingFileSystem.wrapping(fileSystem).use {
            val tempParentDirPath = it.getPath("temp")
            val tempDirPath = it.getPath("temp${it.separator}test")
            Files.createDirectory(tempParentDirPath)
            Files.createDirectory(tempDirPath)
            val cachingPath = it.getPath("$tempDirPath${it.separator}test.txt")
            val resolvedSiblingPath = cachingPath.resolveSibling("test2.txt")
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
            assertThat(resolvedSiblingPath).isInstanceOf(AttributeCachingPath::class.java)
        }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `relativize returns a cachingPath`(fileSystem: FileSystem) =
        AttributeCachingFileSystem.wrapping(fileSystem).use {
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

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `toAbsolutePath returns a cachingPath and copies attributes if they were previously cached`(
        fileSystem: FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem).use {
        val cachingPath = it.getPath("test.txt")
        Files.createFile(cachingPath)
        Files.newOutputStream(cachingPath).use { outputStream ->
            outputStream.write("hello".toByteArray(Charsets.UTF_8))
        }

        // cache basic attributes with setAttribute lastModifiedTime
        Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)

        assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isTrue()
        assertThat(cachingPath.dosAttributesCached).isFalse()
        assertThat(cachingPath.posixAttributesCached).isFalse()
        assertThat(cachingPath.accessControlListOwnerCached).isFalse()
        assertThat(cachingPath.accessControlListEntriesCached).isFalse()

        val absolutePath = cachingPath.toAbsolutePath()

        assertThat(absolutePath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((absolutePath as AttributeCachingPath).basicAttributesCached).isTrue()
        assertThat(absolutePath.dosAttributesCached).isFalse()
        assertThat(absolutePath.posixAttributesCached).isFalse()
        assertThat(absolutePath.accessControlListOwnerCached).isFalse()
        assertThat(absolutePath.accessControlListEntriesCached).isFalse()

        // write stuff to realPath to force lastModifiedTime update
        Files.newOutputStream(absolutePath).use { outputStream ->
            outputStream.write("rewrite".toByteArray(Charsets.UTF_8))
        }

        // now read attributes from absolutePath path to confirm they were saved
        val attributesMap = Files.readAttributes(absolutePath, "*")

        assertThat(attributesMap["lastModifiedTime"]).isEqualTo(testDateFileTime)
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `toAbsolutePath returns a cachingPath and does not copy attributes if they were not previously cached`(
        fileSystem: FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem).use {
        val cachingPath = it.getPath("test.txt")
        Files.createFile(cachingPath)
        Files.newOutputStream(cachingPath).use { outputStream ->
            outputStream.write("hello".toByteArray(Charsets.UTF_8))
        }

        assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
        assertThat(cachingPath.dosAttributesCached).isFalse()
        assertThat(cachingPath.posixAttributesCached).isFalse()
        assertThat(cachingPath.accessControlListOwnerCached).isFalse()
        assertThat(cachingPath.accessControlListEntriesCached).isFalse()

        val absolutePath = cachingPath.toAbsolutePath()

        assertThat(absolutePath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((absolutePath as AttributeCachingPath).basicAttributesCached).isFalse()
        assertThat(absolutePath.dosAttributesCached).isFalse()
        assertThat(absolutePath.posixAttributesCached).isFalse()
        assertThat(absolutePath.accessControlListOwnerCached).isFalse()
        assertThat(absolutePath.accessControlListEntriesCached).isFalse()
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `toRealPath returns a cachingPath and copies attributes if they were previously cached`(
        fileSystem: FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem).use {
        val cachingPath = it.getPath("test.txt")
        Files.createFile(cachingPath)
        Files.newOutputStream(cachingPath).use { outputStream ->
            outputStream.write("hello".toByteArray(Charsets.UTF_8))
        }

        // cache basic attributes with setAttribute lastModifiedTime
        Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)

        assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isTrue()
        assertThat(cachingPath.dosAttributesCached).isFalse()
        assertThat(cachingPath.posixAttributesCached).isFalse()
        assertThat(cachingPath.accessControlListOwnerCached).isFalse()
        assertThat(cachingPath.accessControlListEntriesCached).isFalse()

        val realPath = cachingPath.toRealPath()

        assertThat(realPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((realPath as AttributeCachingPath).basicAttributesCached).isTrue()
        assertThat(realPath.dosAttributesCached).isFalse()
        assertThat(realPath.posixAttributesCached).isFalse()
        assertThat(realPath.accessControlListOwnerCached).isFalse()
        assertThat(realPath.accessControlListEntriesCached).isFalse()

        // write stuff to realPath to force lastModifiedTime update
        Files.newOutputStream(realPath).use { outputStream ->
            outputStream.write("rewrite".toByteArray(Charsets.UTF_8))
        }

        // now read attributes from realPath path to confirm they were saved
        val attributesMap = Files.readAttributes(realPath, "*")

        assertThat(attributesMap["lastModifiedTime"]).isEqualTo(testDateFileTime)
    }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `toRealPath returns a cachingPath and does not copy attributes if they were not previously cached`(
        fileSystem: FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem).use {
        val cachingPath = it.getPath("test.txt")
        Files.createFile(cachingPath)
        Files.newOutputStream(cachingPath).use { outputStream ->
            outputStream.write("hello".toByteArray(Charsets.UTF_8))
        }

        assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
        assertThat(cachingPath.dosAttributesCached).isFalse()
        assertThat(cachingPath.posixAttributesCached).isFalse()
        assertThat(cachingPath.accessControlListOwnerCached).isFalse()
        assertThat(cachingPath.accessControlListEntriesCached).isFalse()

        val realPath = cachingPath.toRealPath()

        assertThat(realPath).isInstanceOf(AttributeCachingPath::class.java)
        assertThat((realPath as AttributeCachingPath).basicAttributesCached).isFalse()
        assertThat(realPath.dosAttributesCached).isFalse()
        assertThat(realPath.posixAttributesCached).isFalse()
        assertThat(realPath.accessControlListOwnerCached).isFalse()
        assertThat(realPath.accessControlListEntriesCached).isFalse()
    }

    @ParameterizedTest
    @MethodSource("allTypes")
    fun <A : BasicFileAttributes?> `read attributes by class type from provider`(
        type: Class<A>,
        fileSystem: () -> FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem()).use {
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

    @ParameterizedTest
    @MethodSource("allNames")
    fun `verify read attributes throws NoSuchFileException on a file that doesn't exist`(
        attributes: String,
        fileSystem: () -> FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem()).use {
        val file = it.getPath("xls.xls")
        val e = assertThrows<NoSuchFileException> { Files.readAttributes(file, attributes) }
        assertThat(e).hasMessageThat().contains("xls.xls")
    }

    @ParameterizedTest
    @MethodSource("allNamesWithMapVerification")
    fun `read attributes by name from provider`(
        attributeName: String,
        expectedMapSize: Int,
        attributeViewName: String,
        fileSystem: () -> FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem()).use {
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
        // verify attribute map keys do not contain the attribute view qualifier "attributeViewName:"
        for (entry in attributesMap) {
            assertThat(entry.key).doesNotContain("$attributeViewName:")
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `set and read posix attributes for path`() = AttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
        // get filesystem attribute caching path
        val uniqueID = UUID.randomUUID()
        val javaTmpDir = it.getPath(System.getProperty("java.io.tmpdir"))
        val testDir = javaTmpDir / "TEST-POSIX-$uniqueID"
        Files.createDirectories(testDir)

        val cachingPath = testDir / "testfile-$uniqueID.txt"

        Files.createFile(cachingPath)
        Files.newOutputStream(cachingPath).use { outputStream ->
            outputStream.write("hello".toByteArray(Charsets.UTF_8))
        }

        assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)

        assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
        assertThat(cachingPath.dosAttributesCached).isFalse()
        assertThat(cachingPath.posixAttributesCached).isFalse()
        assertThat(cachingPath.accessControlListOwnerCached).isFalse()
        assertThat(cachingPath.accessControlListEntriesCached).isFalse()

        try {
            // Test with original file owner and group on default filesystem because it's a large amount of work to
            // create our own test user and group there.
            val originalAttributeMapOwner = Files.readAttributes(cachingPath, "posix:owner")

            assertThat(cachingPath.basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isTrue()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            val owner = originalAttributeMapOwner["owner"] as? UserPrincipal
            val originalAttributeMapGroup = Files.readAttributes(cachingPath, "posix:group")
            val group = originalAttributeMapGroup["group"] as? GroupPrincipal
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

            assertThat(cachingPath.basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isTrue()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            val attributesMap = Files.readAttributes(cachingPath, "posix:*")

            assertThat(attributesMap.size).isEqualTo(12)
            val ownerUserPrincipal = attributesMap["owner"] as? UserPrincipal
            assertThat(ownerUserPrincipal?.name).isEqualTo(owner?.name)
            val groupUserPrincipal = attributesMap["group"] as? GroupPrincipal
            assertThat(groupUserPrincipal?.name).isEqualTo(group?.name)
            @Suppress("UNCHECKED_CAST")
            assertThat(
                PosixFilePermissions.toString(attributesMap["permissions"] as? MutableSet<PosixFilePermission>),
            ).isEqualTo(
                PosixFilePermissions.toString(permissions),
            )

            assertThat(cachingPath.basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isTrue()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()
        } finally {
            Files.deleteIfExists(cachingPath)
            Files.deleteIfExists(testDir)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `set and read acl attributes for path`(): Unit =
        AttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
            // get filesystem attribute caching path
            val uniqueID = UUID.randomUUID()
            val javaTmpDir = it.getPath(System.getProperty("java.io.tmpdir"))
            val testDir = javaTmpDir / "TEST-ACL-$uniqueID"
            Files.createDirectories(testDir)
            val cachingPath = testDir / "testfile-$uniqueID.txt"

            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)

            assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            try {
                // Test with original file owner on default filesystem because it's a large amount of work to create our
                // own test user there.
                val originalAttributesMap = Files.readAttributes(cachingPath, "acl:owner")

                assertThat(cachingPath.basicAttributesCached).isFalse()
                assertThat(cachingPath.dosAttributesCached).isFalse()
                assertThat(cachingPath.posixAttributesCached).isFalse()
                assertThat(cachingPath.accessControlListOwnerCached).isTrue()
                assertThat(cachingPath.accessControlListEntriesCached).isTrue()

                val owner = originalAttributesMap["owner"] as? UserPrincipal
                val acl = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setFlags(AclEntryFlag.FILE_INHERIT)
                    .setPermissions(
                        AclEntryPermission.WRITE_NAMED_ATTRS,
                        AclEntryPermission.WRITE_ATTRIBUTES,
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.READ_ACL,
                        AclEntryPermission.APPEND_DATA,
                        AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.READ_NAMED_ATTRS,
                        AclEntryPermission.DELETE,
                    )
                    .build()

                val aclEntries = listOf<AclEntry>(acl)

                Files.setAttribute(cachingPath, "acl:owner", owner)
                Files.setAttribute(cachingPath, "acl:acl", aclEntries)

                assertThat(cachingPath.basicAttributesCached).isFalse()
                assertThat(cachingPath.dosAttributesCached).isFalse()
                assertThat(cachingPath.posixAttributesCached).isFalse()
                assertThat(cachingPath.accessControlListOwnerCached).isTrue()
                assertThat(cachingPath.accessControlListEntriesCached).isTrue()

                val attributesMap = Files.readAttributes(cachingPath, "acl:*")

                // verify that attribute is "right" type returned from the provider
                assertThat(attributesMap).isInstanceOf(MutableMap::class.java)
                assertThat(owner).isEqualTo(attributesMap["owner"])
                @Suppress("UNCHECKED_CAST")
                assertThat(aclEntries).containsExactlyElementsIn(attributesMap["acl"] as? List<AclEntry>).inOrder()

                assertThat(cachingPath.basicAttributesCached).isFalse()
                assertThat(cachingPath.dosAttributesCached).isFalse()
                assertThat(cachingPath.posixAttributesCached).isFalse()
                assertThat(cachingPath.accessControlListOwnerCached).isTrue()
                assertThat(cachingPath.accessControlListEntriesCached).isTrue()
            } finally {
                Files.deleteIfExists(cachingPath)
                Files.deleteIfExists(testDir)
            }
        }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `cached acl attributes do not get modified by concurrent operation`() {
        val defaultFileSystem = FileSystems.getDefault()
        val uniqueID = UUID.randomUUID()
        val tempDirPath = defaultFileSystem.getPath(System.getProperty("java.io.tmpdir")) / "TEST-ACL-$uniqueID"

        AttributeCachingFileSystem.wrapping(defaultFileSystem).use {
            // get filesystem attribute caching path
            val javaTmpDir = it.getPath(System.getProperty("java.io.tmpdir"))
            val testDir = javaTmpDir / "TEST-ACL-$uniqueID"
            Files.createDirectories(testDir)
            val cachingPath = testDir / "testfile-$uniqueID.txt"

            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)

            assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            try {
                // Test with original file owner on default filesystem because it's a large amount of work to create our
                // own test user there.

                val originalAttributeMap = Files.readAttributes(cachingPath, "acl:*")

                assertThat(cachingPath.basicAttributesCached).isFalse()
                assertThat(cachingPath.dosAttributesCached).isFalse()
                assertThat(cachingPath.posixAttributesCached).isFalse()
                assertThat(cachingPath.accessControlListOwnerCached).isTrue()
                assertThat(cachingPath.accessControlListEntriesCached).isTrue()

                val originalOwner = originalAttributeMap["owner"] as? UserPrincipal

                @Suppress("UNCHECKED_CAST")
                val originalAclEntries = originalAttributeMap["acl"] as? List<AclEntry>

                // simulate concurrent modification on default filesystem
                val concurrentPath = defaultFileSystem.getPath(
                    "$tempDirPath${defaultFileSystem.separator}testfile-$uniqueID.txt",
                )

                val acl = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(originalOwner)
                    .setFlags(AclEntryFlag.FILE_INHERIT)
                    .setPermissions(
                        AclEntryPermission.WRITE_NAMED_ATTRS,
                        AclEntryPermission.WRITE_ATTRIBUTES,
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.READ_ACL,
                        AclEntryPermission.APPEND_DATA,
                        AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.READ_NAMED_ATTRS,
                        AclEntryPermission.DELETE,
                    )
                    .build()
                Files.setAttribute(concurrentPath, "acl:acl", listOf<AclEntry>(acl))

                val newAttributesMap = Files.readAttributes(cachingPath, "acl:*")

                assertThat(newAttributesMap).isInstanceOf(MutableMap::class.java)
                assertThat(originalOwner).isEqualTo(newAttributesMap["owner"] as? UserPrincipal)
                @Suppress("UNCHECKED_CAST")
                assertThat(originalAclEntries).containsExactlyElementsIn(
                    newAttributesMap["acl"] as? List<AclEntry>,
                ).inOrder()

                assertThat(cachingPath.basicAttributesCached).isFalse()
                assertThat(cachingPath.dosAttributesCached).isFalse()
                assertThat(cachingPath.posixAttributesCached).isFalse()
                assertThat(cachingPath.accessControlListOwnerCached).isTrue()
                assertThat(cachingPath.accessControlListEntriesCached).isTrue()
            } finally {
                Files.deleteIfExists(cachingPath)
                Files.deleteIfExists(testDir)
            }
        }
    }

    // This test demonstrates that we cannot cache attributes via the AclFileAttributeView itself and by extension we
    // cannot cache attributes via any other view directly (BasicFileAttributeView, DosFileAttributeView,
    // PosixFileAttributeView) because accessing those views' properties performs filesystem/disk io.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `acl attributes accessed from Files getFileAttributeView() are not cached`() {
        val defaultFileSystem = FileSystems.getDefault()
        val uniqueID = UUID.randomUUID()
        val tempDirPath = defaultFileSystem.getPath(System.getProperty("java.io.tmpdir")) / "TEST-ACL-$uniqueID"

        AttributeCachingFileSystem.wrapping(defaultFileSystem).use {
            // get filesystem attribute caching path
            val javaTmpDir = it.getPath(System.getProperty("java.io.tmpdir"))
            val testDir = javaTmpDir / "TEST-ACL-$uniqueID"
            Files.createDirectories(testDir)
            var cachingPath = testDir / "testfile-$uniqueID.txt"

            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)

            assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            try {
                // Test with original file owner on default filesystem because it's a large amount of work to create our
                // own test user there.

                val originalAttributeView = Files.getFileAttributeView(cachingPath, AclFileAttributeView::class.java)
                val originalOwner = originalAttributeView.owner
                val originalAclEntries = originalAttributeView.acl

                assertThat(cachingPath.basicAttributesCached).isFalse()
                assertThat(cachingPath.dosAttributesCached).isFalse()
                assertThat(cachingPath.posixAttributesCached).isFalse()
                assertThat(cachingPath.accessControlListOwnerCached).isFalse()
                assertThat(cachingPath.accessControlListEntriesCached).isFalse()

                // simulate concurrent modification on default filesystem
                val concurrentPath = defaultFileSystem.getPath(
                    "$tempDirPath${defaultFileSystem.separator}testfile-$uniqueID.txt",
                )

                val acl = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(originalOwner)
                    .setFlags(AclEntryFlag.FILE_INHERIT)
                    .setPermissions(
                        AclEntryPermission.WRITE_NAMED_ATTRS,
                        AclEntryPermission.WRITE_ATTRIBUTES,
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.READ_ACL,
                        AclEntryPermission.APPEND_DATA,
                        AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.READ_NAMED_ATTRS,
                        AclEntryPermission.DELETE,
                    )
                    .build()
                Files.setAttribute(concurrentPath, "acl:acl", listOf<AclEntry>(acl))

                val newAttributeView = Files.getFileAttributeView(cachingPath, AclFileAttributeView::class.java)
                val newOwner = newAttributeView.owner
                val newAclEntries = newAttributeView.acl

                assertThat(newAttributeView).isInstanceOf(AclFileAttributeView::class.java)
                // owner doesn't change because we didn't modify the owner in the concurrent file path
                assertThat(originalOwner).isEqualTo(newOwner)
                // acl entries do change because we modified the acl entries in the concurrent file path
                assertThat(originalAclEntries).doesNotContain(newAclEntries)

                assertThat(cachingPath.basicAttributesCached).isFalse()
                assertThat(cachingPath.dosAttributesCached).isFalse()
                assertThat(cachingPath.posixAttributesCached).isFalse()
                assertThat(cachingPath.accessControlListOwnerCached).isFalse()
                assertThat(cachingPath.accessControlListEntriesCached).isFalse()
            } finally {
                Files.deleteIfExists(cachingPath)
                Files.deleteIfExists(testDir)
            }
        }
    }

    @ParameterizedTest
    @EnabledOnOs(OS.WINDOWS)
    @ValueSource(strings = ["dos:readonly", "dos:hidden", "dos:archive", "dos:system"])
    fun `set and read dos boolean attributes for path`(attributeName: String) =
        AttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
            // get filesystem attribute caching path
            val javaTmpDir = it.getPath(System.getProperty("java.io.tmpdir"))
            assertThat(javaTmpDir).isInstanceOf(AttributeCachingPath::class.java)
            val testDir = javaTmpDir / "TEST-ACL-${UUID.randomUUID()}"
            assertThat(testDir).isInstanceOf(AttributeCachingPath::class.java)
            Files.createDirectories(testDir)
            val cachingPath = testDir / "testfile-${UUID.randomUUID()}.txt"
            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)

            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            try {
                Files.setAttribute(cachingPath, attributeName, true)
                val attributesMap = Files.readAttributes(cachingPath, attributeName)

                assertThat(attributesMap.size).isEqualTo(1)

                val maplookUpAttributeName = attributeName.substringAfter("dos:")
                assertThat(attributesMap[maplookUpAttributeName]).isEqualTo(true)
            } finally {
                // Set readonly/system attributes back to false before trying to delete them on the default filesystem
                if (attributeName == "dos:readonly" || attributeName == "dos:system") {
                    Files.setAttribute(cachingPath, attributeName, false)
                }
                Files.deleteIfExists(cachingPath)
                Files.deleteIfExists(testDir)
            }
        }

    @ParameterizedTest
    @MethodSource("allFileSystems")
    fun `cached attributes do not get modified by concurrent operation`(
        fileSystem: FileSystem,
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
                SimpleDateFormat("MM/dd/yyyy, hh:mm:ss a").parse("01/01/1971, 08:34:27 PM").toInstant(),
            )
            val creationTime = FileTime.from(
                SimpleDateFormat("MM/dd/yyyy, hh:mm:ss a").parse("01/01/1969, 06:34:27 PM").toInstant(),
            )

            // set and populate cache attributes, 3 different times
            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastAccessTime", lastAccessTime)
            Files.setAttribute(cachingPath, "creationTime", creationTime)

            // simulate concurrent modification on default filesystem
            val concurrentPath = fileSystem.getPath(
                "$tempDirPath${fileSystem.separator}testfile.txt",
            )
            val concurrentTime = FileTime.from(
                SimpleDateFormat("MM/dd/yyyy, hh:mm:ss a").parse("01/01/2001, 01:11:11 PM").toInstant(),
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
        fileSystem: () -> FileSystem,
    ) {
        val testFileSystem = fileSystem()
        AttributeCachingFileSystem.wrapping(testFileSystem).use {
            // get filesystem attribute caching path
            val cachingPath = it.getPath("testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)

            assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            Files.setAttribute(cachingPath, "creationTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastAccessTime", testDateFileTime)

            assertThat(cachingPath.basicAttributesCached).isTrue()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            val destinationCachingPath = it.getPath("testfile2.txt")

            assertThat(destinationCachingPath).isInstanceOf(AttributeCachingPath::class.java)

            assertThat((destinationCachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
            assertThat(destinationCachingPath.dosAttributesCached).isFalse()
            assertThat(destinationCachingPath.posixAttributesCached).isFalse()
            assertThat(destinationCachingPath.accessControlListOwnerCached).isFalse()
            assertThat(destinationCachingPath.accessControlListEntriesCached).isFalse()

            assertThat(destinationCachingPath.exists()).isEqualTo(false)

            Files.copy(cachingPath, destinationCachingPath, option)

            assertThat(cachingPath.exists()).isEqualTo(true)
            assertThat(destinationCachingPath.exists()).isEqualTo(true)

            if (option == StandardCopyOption.COPY_ATTRIBUTES) {
                assertThat(destinationCachingPath.basicAttributesCached).isTrue()

                when (getOsString(testFileSystem)) {
                    "windows" -> {
                        assertThat(destinationCachingPath.dosAttributesCached).isTrue()
                        assertThat(destinationCachingPath.posixAttributesCached).isFalse()
                        assertThat(destinationCachingPath.accessControlListOwnerCached).isTrue()
                        assertThat(destinationCachingPath.accessControlListEntriesCached).isTrue()
                    }
                    "posix" -> {
                        assertThat(destinationCachingPath.dosAttributesCached).isFalse()
                        assertThat(destinationCachingPath.posixAttributesCached).isTrue()
                        assertThat(destinationCachingPath.accessControlListOwnerCached).isFalse()
                        assertThat(destinationCachingPath.accessControlListEntriesCached).isFalse()
                    }
                    else -> fail("Unexpected Jimfs Operating System in use by this test.")
                }
            } else {
                assertThat(destinationCachingPath.basicAttributesCached).isFalse()
                assertThat(destinationCachingPath.dosAttributesCached).isFalse()
                assertThat(destinationCachingPath.posixAttributesCached).isFalse()
                assertThat(destinationCachingPath.accessControlListOwnerCached).isFalse()
                assertThat(destinationCachingPath.accessControlListEntriesCached).isFalse()
            }

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

            assertThat(destinationCachingPath.basicAttributesCached).isTrue()
            if (option != StandardCopyOption.COPY_ATTRIBUTES) {
                assertThat(destinationCachingPath.dosAttributesCached).isFalse()
                assertThat(destinationCachingPath.posixAttributesCached).isFalse()
                assertThat(destinationCachingPath.accessControlListOwnerCached).isFalse()
                assertThat(destinationCachingPath.accessControlListEntriesCached).isFalse()
            }
        }
    }

    @ParameterizedTest
    @MethodSource("allFileSystemsWithMoveOption")
    fun `move file from source to target`(
        option: CopyOption,
        fileSystem: () -> FileSystem,
    ) {
        val testFileSystem = fileSystem()
        AttributeCachingFileSystem.wrapping(testFileSystem).use {
            // get filesystem attribute caching path
            val cachingPath = it.getPath("testfile.txt")
            Files.createFile(cachingPath)
            Files.newOutputStream(cachingPath).use { outputStream ->
                outputStream.write("hello".toByteArray(Charsets.UTF_8))
            }

            assertThat(cachingPath).isInstanceOf(AttributeCachingPath::class.java)

            assertThat((cachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            Files.setAttribute(cachingPath, "creationTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastModifiedTime", testDateFileTime)
            Files.setAttribute(cachingPath, "lastAccessTime", testDateFileTime)

            assertThat(cachingPath.basicAttributesCached).isTrue()
            assertThat(cachingPath.dosAttributesCached).isFalse()
            assertThat(cachingPath.posixAttributesCached).isFalse()
            assertThat(cachingPath.accessControlListOwnerCached).isFalse()
            assertThat(cachingPath.accessControlListEntriesCached).isFalse()

            // ensure temp directory exists
            Files.createDirectory(it.getPath("temp"))
            val destinationCachingPath = it.getPath("temp", "testfile2.txt")

            assertThat((destinationCachingPath as AttributeCachingPath).basicAttributesCached).isFalse()
            assertThat(destinationCachingPath.dosAttributesCached).isFalse()
            assertThat(destinationCachingPath.posixAttributesCached).isFalse()
            assertThat(destinationCachingPath.accessControlListOwnerCached).isFalse()
            assertThat(destinationCachingPath.accessControlListEntriesCached).isFalse()

            assertThat(destinationCachingPath.exists()).isEqualTo(false)

            Files.move(cachingPath, destinationCachingPath, option)

            if (option == StandardCopyOption.COPY_ATTRIBUTES) {
                assertThat(destinationCachingPath.basicAttributesCached).isTrue()

                when (getOsString(testFileSystem)) {
                    "windows" -> {
                        assertThat(destinationCachingPath.dosAttributesCached).isTrue()
                        assertThat(destinationCachingPath.posixAttributesCached).isFalse()
                        assertThat(destinationCachingPath.accessControlListOwnerCached).isTrue()
                        assertThat(destinationCachingPath.accessControlListEntriesCached).isTrue()
                    }
                    "posix" -> {
                        assertThat(destinationCachingPath.dosAttributesCached).isFalse()
                        assertThat(destinationCachingPath.posixAttributesCached).isTrue()
                        assertThat(destinationCachingPath.accessControlListOwnerCached).isFalse()
                        assertThat(destinationCachingPath.accessControlListEntriesCached).isFalse()
                    }
                    else -> fail("Unexpected Jimfs Operating System in use by this test.")
                }
            } else {
                assertThat(destinationCachingPath.basicAttributesCached).isFalse()
                assertThat(destinationCachingPath.dosAttributesCached).isFalse()
                assertThat(destinationCachingPath.posixAttributesCached).isFalse()
                assertThat(destinationCachingPath.accessControlListOwnerCached).isFalse()
                assertThat(destinationCachingPath.accessControlListEntriesCached).isFalse()
            }

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

            assertThat(destinationCachingPath.basicAttributesCached).isTrue()
            if (option != StandardCopyOption.COPY_ATTRIBUTES) {
                assertThat(destinationCachingPath.dosAttributesCached).isFalse()
                assertThat(destinationCachingPath.posixAttributesCached).isFalse()
                assertThat(destinationCachingPath.accessControlListOwnerCached).isFalse()
                assertThat(destinationCachingPath.accessControlListEntriesCached).isFalse()
            }
        }
    }

    @DisabledOnOs(OS.MAC, OS.LINUX)
    @ParameterizedTest
    @MethodSource("hiddenTestPathsWindows")
    fun `file isHidden on windows`(fileName: String, expectedHidden: Boolean) =
        AttributeCachingFileSystem.wrapping(windowsJimfs()).use {
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

    @DisabledOnOs(OS.WINDOWS)
    @ParameterizedTest
    @MethodSource("hiddenTestPathsPosix")
    fun `file isHidden on unix and macOS`(
        fileName: String,
        expectedHidden: Boolean,
        fileSystem: () -> FileSystem,
    ) = AttributeCachingFileSystem.wrapping(fileSystem()).use {
        val directoryName = "temp"
        Files.createDirectory(it.getPath(directoryName))
        val cachingPath = it.getPath(directoryName, fileName)
        assertThat(Files.isHidden(cachingPath)).isEqualTo(expectedHidden)
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
            arguments("acl:*", 2, "acl", ::windowsJimfs),
            arguments("acl:owner", 1, "acl", ::windowsJimfs),
            arguments("acl:owner,acl", 2, "acl", ::windowsJimfs),
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
            arguments("acl:*", ::windowsJimfs),
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
            expected: FileTime,
        ) = if (options == StandardCopyOption.COPY_ATTRIBUTES) isEqualTo(expected) else isNotEqualTo(expected)

        private fun getOsString(fileSystem: FileSystem): String {
            val supportedViews = fileSystem.supportedFileAttributeViews()
            return when {
                supportedViews.contains("dos") -> "windows"
                supportedViews.contains("posix") -> "posix"
                supportedViews.contains("acl") -> "windows"
                else -> "none"
            }
        }
    }
}

/**
 * A [FileTime] representing the date "01/01/1970, 07:34:27 PM".
 */
private val testDateFileTime: FileTime = FileTime.from(
    SimpleDateFormat("MM/dd/yyyy, hh:mm:ss a").parse("01/01/1970, 07:34:27 PM").toInstant(),
)
