package com.pkware.filesystem.attributecaching

import com.pkware.filesystem.forwarding.ForwardingPath
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.UserPrincipal
import java.nio.file.spi.FileSystemProvider

/**
 * Cache key for basic file attributes.
 */
public const val CACHE_KEY_BASIC: String = "*"

/**
 * Cache key for dos file attributes.
 */
public const val CACHE_KEY_DOS: String = "dos:*"

/**
 * Cache key for posix file attributes.
 */
public const val CACHE_KEY_POSIX: String = "posix:*"

/**
 * Cache key for the acl file attribute view.
 */
public const val CACHE_KEY_ACL: String = "acl:*"

/**
 * A [Path] instance that supports caching of [BasicFileAttributes] along with other classes such as
 * [DosFileAttributes], [PosixFileAttributes], ACL (access control list) [UserPrincipal] owner, and ACL
 * (access control list) [AclEntry].
 *
 * @param fileSystem the [FileSystem] associated with this [AttributeCachingPath] instance.
 * @param delegate the [Path] to forward calls to if needed.
 */
internal class AttributeCachingPath(private val fileSystem: FileSystem, internal val delegate: Path) :
    ForwardingPath(delegate) {

    private val delegateSupportedFileAttributeViews = delegate.fileSystem.supportedFileAttributeViews()

    /**
     * On demand [LazyAttribute] field for [BasicFileAttributes].
     */
    val cachedBasicAttributes = LazyAttribute {
        delegate.fileSystem.provider().readAttributes(delegate, BasicFileAttributes::class.java)
    }

    /**
     * On demand [LazyAttribute] field for [DosFileAttributes].
     */
    val cachedDosAttributes = LazyAttribute {
        if (delegateSupportedFileAttributeViews.contains("dos")) {
            delegate.fileSystem.provider().readAttributes(delegate, DosFileAttributes::class.java)
        } else {
            null
        }
    }

    /**
     * On demand [LazyAttribute] field for [PosixFileAttributes].
     */
    val cachedPosixAttributes = LazyAttribute {
        if (delegateSupportedFileAttributeViews.contains("posix")) {
            delegate.fileSystem.provider().readAttributes(delegate, PosixFileAttributes::class.java)
        } else {
            null
        }
    }

    /**
     * On demand [LazyAttribute] field for the ACL (access control list) [UserPrincipal] owner.
     */
    val cachedAccessControlListOwner = LazyAttribute {
        if (delegateSupportedFileAttributeViews.contains("acl")) {
            delegate.fileSystem.provider().getFileAttributeView(delegate, AclFileAttributeView::class.java).owner
        } else {
            null
        }
    }

    /**
     * On demand [LazyAttribute] field for the ACL (access control list) [AclEntry]s.
     */
    val cachedAccessControlListEntries = LazyAttribute {
        if (delegateSupportedFileAttributeViews.contains("acl")) {
            delegate.fileSystem.provider().getFileAttributeView(delegate, AclFileAttributeView::class.java).acl
        } else {
            null
        }
    }

    override fun getFileSystem(): FileSystem = fileSystem

    override fun getRoot(): Path? = if (delegate.root != null && delegate.root !is AttributeCachingPath) {
        AttributeCachingPath(fileSystem, delegate.root)
    } else {
        delegate.root
    }

    override fun getFileName(): Path? = if (delegate.fileName != null && delegate.fileName !is AttributeCachingPath) {
        AttributeCachingPath(fileSystem, delegate.fileName)
    } else {
        delegate.fileName
    }

    override fun getParent(): Path? = if (delegate.parent != null && delegate.parent !is AttributeCachingPath) {
        AttributeCachingPath(fileSystem, delegate.parent)
    } else {
        delegate.parent
    }

    override fun resolve(other: Path): Path {
        // Make sure if other is a AttributeCachingPath that we pass along other's
        // delegate rather than other itself.

        val resolvedDelegatePath = if (other is AttributeCachingPath) {
            delegate.resolve(other.delegate)
        } else {
            delegate.resolve(other)
        }

        return AttributeCachingPath(fileSystem, resolvedDelegatePath)
    }

    // With other being a string, it can never be an AttributeCachingPath, so it would not have a delegate. However,
    // we should still make an AttributeCachingPath out of the resulting resolved path.
    override fun resolve(other: String): Path = AttributeCachingPath(fileSystem, delegate.resolve(other))

    override fun getName(index: Int): Path {
        val nameDelegate = delegate.getName(index)
        // Don't need to copy cache here because root cannot be returned, only element closest to root is returned.
        return AttributeCachingPath(fileSystem, nameDelegate)
    }

    override fun normalize(): Path {
        val normalizedDelegate = delegate.normalize()
        val normalizedCachingPath = AttributeCachingPath(fileSystem, normalizedDelegate)
        copyCachedAttributesTo(normalizedCachingPath)
        return normalizedCachingPath
    }

    override fun subpath(beginIndex: Int, endIndex: Int): Path {
        val subPathDelegate = delegate.subpath(beginIndex, endIndex)
        // Dont need to copy cache here because we dont know if we are returning the root or another part of the path.
        return AttributeCachingPath(fileSystem, subPathDelegate)
    }

    override fun resolveSibling(other: Path): Path {
        // Make sure if other is a AttributeCachingPath that we pass along other's
        // delegate rather than other itself.
        val resolvedSiblingDelegatePath = if (other is AttributeCachingPath) {
            delegate.resolveSibling(other.delegate)
        } else {
            delegate.resolveSibling(other)
        }

        return AttributeCachingPath(fileSystem, resolvedSiblingDelegatePath)
    }

    // With other being a string, it can never be an AttributeCachingPath, so it would not have a delegate. However,
    // we should still make an AttributeCachingPath out of the resulting resolved sibling path.
    override fun resolveSibling(other: String): Path = AttributeCachingPath(fileSystem, delegate.resolveSibling(other))

    override fun relativize(other: Path): Path {
        // Make sure if other is a AttributeCachingPath that we pass along other's
        // delegate rather than other itself.
        val relativizedDelegatePath = if (other is AttributeCachingPath) {
            delegate.relativize(other.delegate)
        } else {
            delegate.relativize(other)
        }

        return AttributeCachingPath(fileSystem, relativizedDelegatePath)
    }

    override fun toAbsolutePath(): Path {
        val absoluteDelegate = delegate.toAbsolutePath()
        val absoluteCachingPath = AttributeCachingPath(fileSystem, absoluteDelegate)
        copyCachedAttributesTo(absoluteCachingPath)
        return absoluteCachingPath
    }

    override fun toRealPath(vararg options: LinkOption?): Path {
        val realDelegatePath = delegate.toRealPath()
        val realCachingPath = AttributeCachingPath(fileSystem, realDelegatePath)
        copyCachedAttributesTo(realCachingPath)
        return realCachingPath
    }

    override fun toString(): String = delegate.toString()

    /**
     * Sets the entry for the given attribute [name] with the given [value]. Can only set entire attribute `Class`es
     * such as "acl:*", "dos:*", "posix:*", and "basic:*" For most attribute `Class`es the actual attributes are cached,
     * but in the case of [AclFileAttributeView] the entire view is cached.
     *
     * The attribute name must include a "*" in order to be set within the cache.
     *
     * @param name The name of the attribute to cache.
     * @param value The [FileAttributeView] to cache from.
     */
    fun <A : FileAttributeView> setAttributeByNameUsingView(name: String, value: A?) {
        // This check is to ensure that we are only storing attribute classes and not specific attributes.
        // Remove basic: from our attribute name if present as basicFileAttributes can be accessed without that
        // qualifier
        when (name.substringAfter("basic:")) {
            CACHE_KEY_BASIC -> {
                val basicFileAttributeView = value as? BasicFileAttributeView
                cachedBasicAttributes.value = basicFileAttributeView?.readAttributes()
            }
            CACHE_KEY_DOS -> {
                val dosFileAttributeView = value as? DosFileAttributeView
                cachedDosAttributes.value = dosFileAttributeView?.readAttributes()
            }
            CACHE_KEY_POSIX -> {
                val posixFileAttributeView = value as? PosixFileAttributeView
                cachedPosixAttributes.value = posixFileAttributeView?.readAttributes()
            }
            CACHE_KEY_ACL -> {
                val aclFileAttributeView = value as? AclFileAttributeView
                cachedAccessControlListOwner.value = aclFileAttributeView?.owner
                cachedAccessControlListEntries.value = aclFileAttributeView?.acl
            }
        }
    }

    /**
     * Copies this [AttributeCachingPath]s values to the [target].
     *
     * This function only copies attribute views supported by __both__ filesystems.
     *
     * @param target The [AttributeCachingPath] to copy cached attributes to.
     * @param forceCopyAndInitTarget 'true' to indicate attributes should be copied from this
     * [AttributeCachingPath] regardless if they are `null` or not. Accessing a `null` attribute will force a lookup
     * and incur an OTHER_IOPS penalty. Default is `false` - only copy attributes if they have been previously cached.
     * @throws IOException If something goes wrong with the underlying calls with obtaining this
     * [AttributeCachingPath]'s attributes to copy.
     * @throws UnsupportedOperationException If something goes wrong with the underlying calls with obtaining this
     * [AttributeCachingPath]'s attributes to copy.
     */
    @Throws(IOException::class, UnsupportedOperationException::class)
    fun copyCachedAttributesTo(target: AttributeCachingPath, forceCopyAndInitTarget: Boolean = false) {
        try {
            val commonAttributeViews = delegate.fileSystem.supportedFileAttributeViews().intersect(
                target.delegate.fileSystem.supportedFileAttributeViews(),
            )

            // Copy only the attribute views shared by both filesystems.
            for (view in commonAttributeViews) {
                when (view) {
                    "basic" -> target.cachedBasicAttributes.copyValue(cachedBasicAttributes, forceCopyAndInitTarget)
                    "dos" -> target.cachedDosAttributes.copyValue(cachedDosAttributes, forceCopyAndInitTarget)
                    "posix" -> target.cachedPosixAttributes.copyValue(cachedPosixAttributes, forceCopyAndInitTarget)
                    "acl" -> {
                        target.cachedAccessControlListOwner.copyValue(
                            cachedAccessControlListOwner,
                            forceCopyAndInitTarget,
                        )
                        target.cachedAccessControlListEntries.copyValue(
                            cachedAccessControlListEntries,
                            forceCopyAndInitTarget,
                        )
                    }
                }
            }
        } catch (expected: NoSuchFileException) {
            // Swallow NoSuchFileExceptions and skip cache checks on files that do not exist or are not regular files.
            // Checking these attributes directly does incur OTHER_IOPS penalties which we want to avoid.
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
    fun <A : BasicFileAttributes?> getAllAttributesMatchingClass(type: Class<A>): A? = when (type) {
        BasicFileAttributes::class.java -> cachedBasicAttributes.value as A
        DosFileAttributes::class.java -> cachedDosAttributes.value as A
        PosixFileAttributes::class.java -> cachedPosixAttributes.value as A
        else -> null
    }

    /**
     * Get all attributes matching [names] from the cache.
     *
     * @param names The attributes to be retrieved from the cache. Can be single attributes, multiple attributes from
     * the same `Class`, or an entire attribute `Class` String (ie: "dos:*","basic:*","posix:permissions",
     * "dos:hidden,readOnly,system", etc.).
     * @return The value(s) in the cache that corresponds to the given [names] or `null` if those [names] are not
     * supported. The value(s) are returned with map keys do not contain any attribute class information (ie: no
     * "basic:", "dos:" or "posix:" string qualifiers will be present.).
     * @throws IOException  If something goes wrong with the underlying calls to the [delegate]
     * [FileSystemProvider].
     * @throws UnsupportedOperationException If the attributes of the given [names] are not supported.
     */
    @Throws(IOException::class, UnsupportedOperationException::class)
    fun getAllAttributesMatchingNames(names: String): MutableMap<String, Any>? {
        var attributeMap = mutableMapOf<String, Any>()
        // remove basic: from our attribute name if present as basicFileAttributes can be accessed without that qualifier
        var checkedNames = names.substringAfter("basic:")

        if (checkedNames.startsWith("acl")) {
            // get our acl attributes and translate them to MutableMap<String, Any>?
            // Acl file attributes do not extend BasicFileAttributes and are their own separate entity.
            try {
                attributeMap["owner"] = requireNotNull(cachedAccessControlListOwner.value)
                attributeMap["acl"] = requireNotNull(cachedAccessControlListEntries.value)
            } catch (expected: IllegalArgumentException) {
                return null
            }
            // We do not catch NoSuchFileException here and throw it up the chain instead
        } else {
            // get our attribute class from the cache, should be BasicFileAttributes, DosFileAttributes, or
            // PosixFileAttributes.
            val attributeClass = if (checkedNames.startsWith("dos")) {
                cachedDosAttributes.value
            } else if (checkedNames.startsWith("posix")) {
                cachedPosixAttributes.value
            } else {
                cachedBasicAttributes.value
            }
            // We do not catch NoSuchFileException here and throw it up the chain instead

            if (attributeClass == null) return null

            // translate our class object to MutableMap<String, Any>?

            // get basic filesystem attributes
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

            // AttributeClass may or may not be either of the following BasicFileAttributes subclasses
            if (attributeClass is DosFileAttributes) {
                attributeMap["readonly"] = attributeClass.isReadOnly
                attributeMap["hidden"] = attributeClass.isHidden
                attributeMap["archive"] = attributeClass.isArchive
                attributeMap["system"] = attributeClass.isSystem
            } else if (attributeClass is PosixFileAttributes) {
                attributeMap["owner"] = attributeClass.owner()
                attributeMap["group"] = attributeClass.group()
                attributeMap["permissions"] = attributeClass.permissions()
            }
        }

        // Filter out attributes if the checkedName does not contain the "*" wildcard
        if (!checkedNames.contains("*")) {
            // remove dos: for later attribute name only filtering
            checkedNames = checkedNames.substringAfter("dos:")
            // remove posix: for later attribute name only filtering
            checkedNames = checkedNames.substringAfter("posix:")
            // remove acl: for later attribute name only filtering
            checkedNames = checkedNames.substringAfter("acl:")

            // If we contain a "," then we have multiple attributes
            attributeMap = if (checkedNames.contains(",")) {
                val attributeNames = checkedNames.split(",")
                val aggregateMap = mutableMapOf<String, Any>()
                for (attributeName in attributeNames) {
                    aggregateMap.putAll(attributeMap.filter { it.key == attributeName })
                }
                aggregateMap
            }
            // Otherwise we have a single attribute
            else {
                attributeMap.filter { it.key == checkedNames }.toMutableMap()
            }
        }

        if (attributeMap.isEmpty()) return null

        return attributeMap
    }
}
