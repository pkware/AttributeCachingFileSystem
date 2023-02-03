package com.pkware.file.fileAttributeCaching

import com.pkware.file.forwarding.ForwardingPath
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.spi.FileSystemProvider
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * A [Path] instance that supports caching of [BasicFileAttributes] and other classes that extend it such as
 * [DosFileAttributes] and [PosixFileAttributes].
 *
 * @param fileSystem the [FileSystem] associated with this [FileAttributeCachingPath] instance.
 * @param delegate the [Path] to forward calls to if needed.
 * @param initializeCache `true` to initialize the cached attribute fields of this [FileAttributeCachingPath] instance.
 * The default is `false`.
 */
internal class FileAttributeCachingPath(
    private val fileSystem: FileSystem,
    internal val delegate: Path,
    private val initializeCache: Boolean = false,
) : ForwardingPath(delegate) {

    /**
     * Controls whether calls to [initializeCache] populate the cache or not. Default is `false`.
     */
    var isInitialized = false

    private var basicAttributes: BasicFileAttributes? = null
    private var dosAttributes: DosFileAttributes? = null
    private var posixAttributes: PosixFileAttributes? = null

    init {
        if (initializeCache) initializeCache()
    }

    override fun getFileSystem(): FileSystem = fileSystem

    override fun resolve(other: Path): Path {
        // Make sure if other is a FileAttributeCachingPath that we pass along other's
        // delegate rather than other itself.
        val resolvedDelegatePath = if (other is FileAttributeCachingPath) {
            delegate.resolve(other.delegate)
        } else {
            delegate.resolve(other)
        }

        return FileAttributeCachingPath(fileSystem, resolvedDelegatePath)
    }

    override fun getName(index: Int): Path {
        val nameDelegate = delegate.getName(index)
        // Dont need to copy cache here because root cannot be returned, only element closest to root is returned.
        return FileAttributeCachingPath(fileSystem, nameDelegate)
    }

    override fun normalize(): Path {
        val normalizedDelegate = delegate.normalize()
        val normalizedCachingPath = FileAttributeCachingPath(fileSystem, normalizedDelegate)
        copyCachedAttributesTo(normalizedCachingPath)
        return normalizedCachingPath
    }

    override fun subpath(beginIndex: Int, endIndex: Int): Path {
        val subPathDelegate = delegate.subpath(beginIndex, endIndex)
        // Dont need to copy cache here because we dont know if we are returning the root or another part of the path.
        return FileAttributeCachingPath(fileSystem, subPathDelegate)
    }

    override fun resolveSibling(other: Path): Path {
        // Make sure if other is a FileAttributeCachingPath that we pass along other's
        // delegate rather than other itself.
        val resolvedSiblingDelegatePath = if (other is FileAttributeCachingPath) {
            delegate.resolveSibling(other.delegate)
        } else {
            delegate.resolveSibling(other)
        }

        return FileAttributeCachingPath(fileSystem, resolvedSiblingDelegatePath)
    }

    override fun relativize(other: Path): Path {
        // Make sure if other is a FileAttributeCachingPath that we pass along other's
        // delegate rather than other itself.
        val relativizedDelegatePath = if (other is FileAttributeCachingPath) {
            delegate.relativize(other.delegate)
        } else {
            delegate.relativize(other)
        }

        return FileAttributeCachingPath(fileSystem, relativizedDelegatePath)
    }

    override fun toAbsolutePath(): Path {
        val absoluteDelegate = delegate.toAbsolutePath()
        val absoluteCachingPath = FileAttributeCachingPath(fileSystem, absoluteDelegate)
        copyCachedAttributesTo(absoluteCachingPath)
        return absoluteCachingPath
    }

    override fun toRealPath(vararg options: LinkOption?): Path {
        val realDelegatePath = delegate.toRealPath()
        val realCachingPath = FileAttributeCachingPath(fileSystem, realDelegatePath)
        copyCachedAttributesTo(realCachingPath)
        return realCachingPath
    }

    override fun toString(): String = delegate.toString()

    /**
     * Sets the entry for the given attribute [name] with the given [value]. Can only set entire
     * attribute `Class`es such as "dos:*", "posix:*", and "basic:*"
     *
     * The attribute name must include a "*" in order to be set within the cache.
     *
     * @param name The name of the attribute to cache.
     * @param value The attribute value to cache.
     */
    fun <A : BasicFileAttributes?> setAttributeByName(name: String, value: A?) {
        // remove basic from our attribute name if present as basicFileAttributes can be accessed without that qualifier

        // this check is to ensure that we are only storing attribute classes and not specific attributes
        when (name.substringAfter("basic:")) {
            "*" -> basicAttributes = value
            "dos:*" -> posixAttributes = value as? PosixFileAttributes
            "posix:*" -> dosAttributes = value as? DosFileAttributes
        }
    }

    /**
     * Copies this [FileAttributeCachingPath]s values to the [target].
     *
     * @param target The [FileAttributeCachingPath] to copy cached attributes to.
     * @throws IOException If something goes wrong with the underlying calls with obtaining this
     * [FileAttributeCachingPath]'s attributes to copy.
     * @throws UnsupportedOperationException If something goes wrong with the underlying calls with obtaining this
     * [FileAttributeCachingPath]'s attributes to copy.
     */
    @Throws(IOException::class, UnsupportedOperationException::class)
    fun copyCachedAttributesTo(target: FileAttributeCachingPath) {
        try {
            val delegateFileSystem = delegate.fileSystem
            val supportedViews = delegateFileSystem.supportedFileAttributeViews()

            val basicFileAttributes = getAllAttributesMatchingClass(BasicFileAttributes::class.java)

            val dosFileAttributes = if (supportedViews.contains("dos")) {
                getAllAttributesMatchingClass(DosFileAttributes::class.java)
            } else null

            val posixFileAttributes = if (supportedViews.contains("posix")) {
                getAllAttributesMatchingClass(PosixFileAttributes::class.java)
            } else null

            // Can set null values here but that's okay.
            target.setAttributeByType(BasicFileAttributes::class.java, basicFileAttributes)
            target.setAttributeByType(DosFileAttributes::class.java, dosFileAttributes)
            target.setAttributeByType(PosixFileAttributes::class.java, posixFileAttributes)
            target.isInitialized = isInitialized
        } catch (expected: NoSuchFileException) {
            // swallow NoSuchFileExceptions and skip cache copies for now, since checking if we exist, and if
            // we are a regular file do incur OTHER_IOPS penalties.
        }
    }

    /**
     * Get all attributes matching the `Class` [type] from the cache.
     *
     * @param type The attribute `Class` to get from the cache. Class` types include [BasicFileAttributes],
     * [DosFileAttributes], or [PosixFileAttributes].
     * @return The value in the cache that corresponds to the given [type] or `null` if that [type] is not
     * supported.
     * @throws IOException  If something goes wrong with the underlying calls to the [delegate]
     * [FileSystemProvider].
     * @throws UnsupportedOperationException If the attributes of the given [type] are not supported.
     */
    @Suppress("UNCHECKED_CAST")
    @Throws(IOException::class, UnsupportedOperationException::class)
    fun <A : BasicFileAttributes?> getAllAttributesMatchingClass(
        type: Class<A>,
    ): A? = when (type) {
        BasicFileAttributes::class.java -> {
            if (basicAttributes == null) {
                basicAttributes = delegate.fileSystem.provider().readAttributes(
                    delegate, BasicFileAttributes::class.java
                )
            }
            basicAttributes as A
        }
        DosFileAttributes::class.java -> {
            if (dosAttributes == null) {
                dosAttributes = delegate.fileSystem.provider().readAttributes(
                    delegate, DosFileAttributes::class.java
                )
            }
            dosAttributes as A
        }
        PosixFileAttributes::class.java -> {
            if (posixAttributes == null) {
                posixAttributes = delegate.fileSystem.provider().readAttributes(
                    delegate, PosixFileAttributes::class.java
                )
            }
            posixAttributes as A
        }
        else -> null
    }

    /**
     * Get all attributes matching [name] from the cache.
     *
     * @param name The attributes to be retrieved from the cache. Can be single attributes or an entire attribute
     * `Class` String (ie: "dos:*","basic:*","posix:permissions", etc.).
     * @return The value in the cache that corresponds to the given [name] or `null` if that [name] is not
     * supported.
     * @throws IOException  If something goes wrong with the underlying calls to the [delegate]
     * [FileSystemProvider].
     * @throws UnsupportedOperationException If the attributes of the given [name] are not supported.
     */
    @Throws(IOException::class, UnsupportedOperationException::class)
    fun getAllAttributesMatchingName(
        name: String,
    ): MutableMap<String, Any>? {

        var attributeMap = mutableMapOf<String, Any>()
        // remove basic: from our attribute name if present as basicFileAttributes can be accessed without that qualifier
        val checkedName = name.substringAfter("basic:")

        // get our attribute class from the cache, should be BasicFileAttributes, DosFileAttributes, or PosixFileAttributes
        val attributeClass = if (checkedName.startsWith("dos")) {
            if (dosAttributes == null) {
                dosAttributes = delegate.fileSystem.provider().readAttributes(
                    delegate, DosFileAttributes::class.java
                )
            }
            dosAttributes
        } else if (checkedName.startsWith("posix")) {
            if (posixAttributes == null) {
                posixAttributes = delegate.fileSystem.provider().readAttributes(
                    delegate, PosixFileAttributes::class.java
                )
            }
            posixAttributes
        } else {
            if (basicAttributes == null) {
                basicAttributes = delegate.fileSystem.provider().readAttributes(
                    delegate, BasicFileAttributes::class.java
                )
            }
            basicAttributes
        }

        if (attributeClass == null) return null

        // translate our class object to MutableMap<String, Any>?

        // get basic file attributes
        // these should always exist because DosFileAttributes and PosixFileAttributes extend BasicFileAttributes
        attributeMap["lastModifiedTime"] = attributeClass.lastModifiedTime()
        attributeMap["lastAccessTime"] = attributeClass.lastAccessTime()
        attributeMap["creationTime"] = attributeClass.creationTime()
        attributeMap["regularFile"] = attributeClass.isRegularFile
        attributeMap["directory"] = attributeClass.isDirectory
        attributeMap["symbolicLink"] = attributeClass.isSymbolicLink
        attributeMap["other"] = attributeClass.isOther
        attributeMap["size"] = attributeClass.size()
        // Don't add the fileKey to our map if it's null, it throws an NPE
        if (attributeClass.fileKey() != null) {
            attributeMap["fileKey"] = attributeClass.fileKey()
        }

        // attributeClass may or may not be either of the following BasicFileAttributes subclasses
        if (attributeClass is DosFileAttributes) {
            attributeMap["dos:readonly"] = attributeClass.isReadOnly
            attributeMap["dos:hidden"] = attributeClass.isHidden
            attributeMap["dos:archive"] = attributeClass.isArchive
            attributeMap["dos:system"] = attributeClass.isSystem
        } else if (attributeClass is PosixFileAttributes) {
            attributeMap["posix:owner"] = attributeClass.owner()
            attributeMap["posix:group"] = attributeClass.group()
            attributeMap["posix:permissions"] = attributeClass.permissions()
        }

        // filter out attributes for a specific checkedName if the checkedName does not contain the "*" wildcard
        if (!checkedName.contains("*")) {
            attributeMap = attributeMap.filter { it.key == checkedName }.toMutableMap()
        }

        if (attributeMap.isEmpty()) return null

        return attributeMap
    }

    /**
     * Initializes the attributes of this [FileAttributeCachingPath] instance if it has not already been initialized,
     * if it is a regular file, and if it exists.
     */
    private fun initializeCache() {
        if (!isInitialized) {
            try {
                val delegateFileSystem = delegate.fileSystem
                val delegateProvider = delegateFileSystem.provider()
                val supportedViews = delegateFileSystem.supportedFileAttributeViews()

                val basicFileAttributesType = BasicFileAttributes::class.java
                setAttributeByType(
                    basicFileAttributesType,
                    delegateProvider.readAttributes(delegate, basicFileAttributesType)
                )

                if (supportedViews.contains("dos")) {
                    val dosFileAttributesType = DosFileAttributes::class.java
                    setAttributeByType(
                        dosFileAttributesType,
                        delegateProvider.readAttributes(delegate, dosFileAttributesType)
                    )
                }

                if (supportedViews.contains("posix")) {
                    val posixFileAttributesType = PosixFileAttributes::class.java
                    setAttributeByType(
                        posixFileAttributesType,
                        delegateProvider.readAttributes(delegate, posixFileAttributesType)
                    )
                }

                isInitialized = true
            } catch (expected: NoSuchFileException) {
                // swallow NoSuchFileExceptions and skip cache initializations for now, since checking if we exist, and
                // if we are a regular file do incur OTHER_IOPS penalties.
            }
        }
    }

    /**
     * Sets the entry for the given attribute `Class` [type] to the given [value].
     *
     * @param type The attribute `Class` to set. `Class` types include [BasicFileAttributes], [DosFileAttributes],
     * or [PosixFileAttributes].
     * @param value The attribute value to cache. Can be null.
     */
    private fun <A : BasicFileAttributes?> setAttributeByType(type: Class<A>, value: A?) {
        when (type) {
            BasicFileAttributes::class.java -> basicAttributes = value
            DosFileAttributes::class.java -> posixAttributes = value as? PosixFileAttributes
            PosixFileAttributes::class.java -> dosAttributes = value as? DosFileAttributes
        }
    }
}
