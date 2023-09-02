package com.pkware.filesystem.attributecaching

import com.google.auto.service.AutoService
import java.io.IOException
import java.net.URI
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NotLinkException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.ExecutorService

/**
 * A [FileSystemProvider] wrapper that handles [AttributeCachingPath]s for reading and writing filesystem attributes.
 *
 * It forwards most operations using the [AttributeCachingPath]s underlying delegate [Path]. It caches filesystem
 * attributes ([BasicFileAttributes], [DosFileAttributes], and [PosixFileAttributes]) on reads, writes, copy and move
 * operations.
 */
@AutoService(FileSystemProvider::class)
internal class AttributeCachingFileSystemProvider : FileSystemProvider() {

    private val fileSystems = mutableMapOf<URI, FileSystem>()
    private val isWindowsOS by lazy {
        // Adapted from org/junit/jupiter/api/condition/OS.java to look up the operating system name
        // lowercase() defaults to Locale.ROOT in kotlin, but toLowerCase() in java does not, BE AWARE OF THIS.
        System.getProperty("os.name").lowercase().contains("win")
    }

    /**
     * Helper function to prevent concurrent creation and addition of new [FileSystem]s for this provider
     *
     * Only one thread is allowed at a time to create and add a new [FileSystem] to this provider's
     * [fileSystems] map.
     *
     * @param uri The [URI] identifying the file system.
     * @param env A map of provider specific properties to configure the file system.
     * @return A [FileSystem] instance matching the given [uri] configured with the given [env].
     */
    @Synchronized
    private fun newFileSystemSynchronized(uri: URI, env: MutableMap<String, *>): FileSystem =
        fileSystems.computeIfAbsent(uri) {
            AttributeCachingFileSystem(env.getValue("filesystem") as FileSystem, this)
        }

    override fun getScheme(): String = "cache"

    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = newFileSystemSynchronized(uri, env)

    override fun getFileSystem(uri: URI): FileSystem =
        fileSystems[uri] ?: throw FileSystemNotFoundException("Filesystem for $uri not found.")

    override fun getPath(uri: URI): Path = Paths.get(uri)

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>?,
    ): SeekableByteChannel = Files.newByteChannel(path.getUnderlyingPath(), options, *attrs)

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> =
        Files.newDirectoryStream(dir.getUnderlyingPath(), filter)

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        Files.createDirectory(dir.getUnderlyingPath(), *attrs)
    }

    override fun delete(path: Path) = Files.delete(path.getUnderlyingPath())

    /**
     * Copies [source] to [target] with the specified [options].
     *
     * If the [options] include [StandardCopyOption.COPY_ATTRIBUTES] and both the [source] and [target] are
     * [AttributeCachingPath]s, then the cached attributes are copied from the [source] to the [target].
     *
     * See [FileSystemProvider.copy] for details.
     *
     * @param source The [Path] to copy from.
     * @param target The destination [Path] to copy to.
     * @param options The [CopyOption]s to use during this copy operation, can be `null`.
     * @throws IOException If any errors occur with the [source] or [target] paths or their underlying delegates.
     */
    @Suppress("SpreadOperator")
    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption?) {
        val actualSourcePath = source.getUnderlyingPath()
        val actualTargetPath = target.getUnderlyingPath()

        // If we have both target and source caching paths we copy the attributes from source to target and run
        // Files.copy(source, target, *newOptions).
        if (options.contains(StandardCopyOption.COPY_ATTRIBUTES) &&
            source is AttributeCachingPath &&
            target is AttributeCachingPath &&
            source.delegate.fileSystem == target.delegate.fileSystem
        ) {
            // Filter out StandardCopyOption.COPY_ATTRIBUTES here because we dont want the copied filesystem to repopulate
            // the cache from the delegate provider/filesystem.
            val newOptions = options.filter {
                it != StandardCopyOption.COPY_ATTRIBUTES
            }.toTypedArray()

            source.copyCachedAttributesTo(target, true)
            Files.copy(actualSourcePath, actualTargetPath, *newOptions)
        } else {
            // If the StandardCopyOption.COPY_ATTRIBUTES option is not selected, there is no need to cache the
            // attributes for the copied filesystem.
            Files.copy(actualSourcePath, actualTargetPath, *options)
        }
    }

    /**
     * Moves [source] to [target] with the specified [options].
     *
     * If the [options] include [StandardCopyOption.COPY_ATTRIBUTES] and both the [source] and [target] are
     * [AttributeCachingPath]s, then only the cached attributes are copied from the [source] to the [target].
     *
     * See [FileSystemProvider.move] for details.
     *
     * @param source The [Path] to move.
     * @param target The destination [Path] to move to.
     * @param options The [CopyOption]s to use during this move operation, can be `null`.
     * @throws IOException If any errors occur with the [source] or [target] paths or their underlying delegates.
     */
    @Suppress("SpreadOperator")
    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption?) {
        val actualSourcePath = source.getUnderlyingPath()
        val actualTargetPath = target.getUnderlyingPath()

        // If we have both target and source caching paths we copy the attributes from source to target and run
        // Files.move(source, target, *newOptions).
        if (options.contains(StandardCopyOption.COPY_ATTRIBUTES) &&
            source is AttributeCachingPath &&
            target is AttributeCachingPath &&
            source.delegate.fileSystem == target.delegate.fileSystem
        ) {
            // Filter out StandardCopyOption.COPY_ATTRIBUTES here because we dont want the moved filesystem to repopulate
            // the cache from the delegate provider/filesystem.
            val newOptions = options.filter {
                it != StandardCopyOption.COPY_ATTRIBUTES
            }.toTypedArray()

            source.copyCachedAttributesTo(target, true)
            Files.move(actualSourcePath, actualTargetPath, *newOptions)
        } else {
            // If the StandardCopyOption.COPY_ATTRIBUTES option is not selected, there is no need to cache the
            // attributes for the moved filesystem.
            Files.move(actualSourcePath, actualTargetPath, *options)
        }
    }

    override fun isSameFile(path: Path, path2: Path) = Files.isSameFile(
        path.getUnderlyingPath(),
        path2.getUnderlyingPath(),
    )

    /**
     * Tells whether a filesystem is considered to be hidden. The exact definition of hidden is platform or provider
     * dependent.
     *
     * On Windows a filesystem is considered hidden if it isn't a directory and the DOS hidden attribute is set.
     *
     * On UNIX a filesystem is considered to be hidden if its name begins with a period character ('.').
     *
     * Depending on the implementation, this method may access the [path] to determine if the filesystem is
     * considered hidden.
     *
     * @param path The path to check.
     * @return `true` if the path is hidden, `false` otherwise.
     * @throws IOException if an error occurs while accessing the underlying delegate provider or the provided path was
     * not a [AttributeCachingPath].
     */
    @Throws(IOException::class)
    override fun isHidden(path: Path): Boolean = if (path is AttributeCachingPath) {
        if (isWindowsOS) {
            val attributesMap = path.getAllAttributesMatchingNames("dos:*") ?: throw IOException(
                "Could not get dos attributes of $path from delegate filesystem.",
            )
            val isHidden = attributesMap["hidden"] as Boolean && !(attributesMap["directory"] as Boolean)
            isHidden
        } else {
            val delegatePath = path.delegate
            val delegateProvider = delegatePath.fileSystem.provider()
            delegateProvider.isHidden(delegatePath)
        }
    } else {
        path.fileSystem.provider().isHidden(path)
    }

    @Throws(IOException::class)
    override fun getFileStore(path: Path): FileStore {
        val actualPath = path.getUnderlyingPath()
        return actualPath.fileSystem.provider().getFileStore(actualPath)
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode?) {
        val actualPath = path.getUnderlyingPath()
        return actualPath.fileSystem.provider().checkAccess(actualPath, *modes)
    }

    override fun <V : FileAttributeView?> getFileAttributeView(
        path: Path,
        type: Class<V>?,
        vararg options: LinkOption?,
    ): V {
        val actualPath = path.getUnderlyingPath()
        return actualPath.fileSystem.provider().getFileAttributeView(actualPath, type, *options)
    }

    @Throws(
        IllegalArgumentException::class,
        UnsupportedOperationException::class,
        FileAlreadyExistsException::class,
        IOException::class,
    )
    override fun newFileChannel(path: Path, options: Set<OpenOption?>, vararg attrs: FileAttribute<*>?): FileChannel {
        val actualPath = path.getUnderlyingPath()
        return actualPath.fileSystem.provider().newFileChannel(actualPath, options, *attrs)
    }

    @Throws(
        IllegalArgumentException::class,
        UnsupportedOperationException::class,
        FileAlreadyExistsException::class,
        IOException::class,
    )
    override fun newAsynchronousFileChannel(
        path: Path,
        options: Set<OpenOption?>,
        executor: ExecutorService,
        vararg attrs: FileAttribute<*>?,
    ): AsynchronousFileChannel {
        val actualPath = path.getUnderlyingPath()
        return actualPath.fileSystem.provider().newAsynchronousFileChannel(actualPath, options, executor, *attrs)
    }

    @Throws(
        UnsupportedOperationException::class,
        FileAlreadyExistsException::class,
        IOException::class,
    )
    override fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>?) {
        val actualLinkPath = if (link is AttributeCachingPath) link.delegate else link
        val actualTargetPath = if (target is AttributeCachingPath) target.delegate else target
        Files.createSymbolicLink(actualLinkPath, actualTargetPath, *attrs)
    }

    @Throws(UnsupportedOperationException::class, FileAlreadyExistsException::class, IOException::class)
    override fun createLink(link: Path, existing: Path) {
        Files.createLink(link.getUnderlyingPath(), existing.getUnderlyingPath())
    }

    @Throws(UnsupportedOperationException::class, NotLinkException::class, IOException::class)
    override fun readSymbolicLink(link: Path): Path? = Files.readSymbolicLink(link.getUnderlyingPath())

    /**
     * Read filesystem attributes specified by the attribute `Class` [type] from the incoming [path]. If the returned
     * attributes are `null` we then attempt to get them from the [path]s delegate [FileSystemProvider] and populate
     * the [path]'s cache with those attributes.
     *
     * The attributes returned will always be from the [path] itself, never directly from the [path]s delegate.
     *
     * @param path The [Path] to read filesystem attributes from. It must be a [AttributeCachingPath] otherwise an
     * [IOException] will be thrown.
     * @param type The `Class` of the filesystem attributes to be read. `Class` types include [BasicFileAttributes],
     * [DosFileAttributes], or [PosixFileAttributes].
     * @param options The [LinkOption]s indicating how symbolic links are handled.
     * @return The filesystem attributes for the given [path].
     * @throws IOException  If something goes wrong with the underlying calls to the [path]s delegate
     * [FileSystemProvider].
     * @throws UnsupportedOperationException If the [path] is not a [AttributeCachingPath] or the attributes of the
     * given [type] are not supported.
     */
    @Throws(IOException::class, UnsupportedOperationException::class)
    override fun <A : BasicFileAttributes?> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption,
    ): A = if (path is AttributeCachingPath) {
        val attributes = path.getAllAttributesMatchingClass(type) ?: throw UnsupportedOperationException(
            "Could not read attributes of type $type from the path $path and its delegate filesystem.",
        )
        attributes
    } else {
        path.fileSystem.provider().readAttributes(path, type, *options)
    }

    /**
     * Read filesystem [attributes] from the incoming [path]. If the returned attributes are `null` we then attempt to get
     * them from the [path]s delegate [FileSystemProvider] and populate the [path]s cache with those attributes.
     *
     * The attributes returned will always be from the [path] itself, never directly from the [path]s delegate.
     *
     * @param path The [Path] to read filesystem attributes from. It must be a [AttributeCachingPath] otherwise an
     * [IOException] will be thrown.
     * @param attributes The attributes to be retrieved from the [path]. Can be single attributes or an entire attribute
     * `Class` String (ie: "dos:*","basic:*","posix:permissions", etc.).
     * @param options The [LinkOption]s indicating how symbolic links are handled.
     * @return The filesystem attributes for the given [path] as a [MutableMap].
     * @throws IOException  If something goes wrong with the underlying calls to the [path]s delegate
     * [FileSystemProvider].
     * @throws UnsupportedOperationException If the [path] is not a [AttributeCachingPath].
     * @throws IllegalArgumentException If the [attributes] are not recognized, or they cannot be read from the delegate
     * [FileSystemProvider].
     */
    @Throws(IOException::class, UnsupportedOperationException::class, IllegalArgumentException::class)
    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption,
    ): MutableMap<String, Any> = if (path is AttributeCachingPath) {
        val attributesMap = path.getAllAttributesMatchingNames(attributes) ?: throw IllegalArgumentException(
            "Could not read attributes $attributes of the path $path from the delegate filesystem.",
        )
        attributesMap
    } else {
        path.fileSystem.provider().readAttributes(path, attributes, *options)
    }

    /**
     * Set a single attribute or attribute class (ie: "dos:*","basic:*","posix:permissions", etc.) for the given [path]
     * first on the [path]'s delegate [FileSystemProvider] and then in the [path]s cache.
     *
     * @param path The [Path] to set the given [attribute] on. It must be a [AttributeCachingPath] otherwise an
     * [IOException] will be thrown.
     * @param attribute The attribute name to set and associate with the [path].
     * @param value The value of the [attribute] to set, can be `null`.
     * @param options The [LinkOption]s indicating how symbolic links are handled.
     * @throws IOException If an IO error occurs.
     * @throws UnsupportedOperationException If the attribute view for the given [attribute] name is not available.
     * @throws IllegalArgumentException If the [attribute] name is not recognized or if its value is of the incorrect
     * type.
     */
    @Throws(IOException::class, UnsupportedOperationException::class, IllegalArgumentException::class)
    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) {
        val actualPath = path.getUnderlyingPath()
        val provider = actualPath.fileSystem.provider()
        // Always set attribute(s) first with real filesystem IO
        provider.setAttribute(actualPath, attribute, value, *options)

        if (path !is AttributeCachingPath) {
            return
        }

        // Then set our cache
        // Need to make sure that we only supply class names to path.setAttributeByName
        // cannot set single attribute in the cache
        val attributeCacheKey = when {
            attribute.startsWith("dos") -> CACHE_KEY_DOS
            attribute.startsWith("posix") -> CACHE_KEY_POSIX
            attribute.startsWith("acl") -> CACHE_KEY_ACL
            else -> CACHE_KEY_BASIC
        }

        // Even if we have a single attribute only we should get the entire attribute view class for that single
        // attribute to properly set the cache.
        val fileAttributeView: FileAttributeView? = provider.getFileAttributeView(
            path.delegate,
            getAttributeViewJavaClassType(attributeCacheKey),
        )
        path.setAttributeByNameUsingView(attributeCacheKey, fileAttributeView)
    }

    /**
     * Gets the specific [FileAttributeView]::class.java type instance from the given [key] or `null` if the key
     * doesn't match [CACHE_KEY_BASIC], [CACHE_KEY_DOS], [CACHE_KEY_POSIX], or [CACHE_KEY_ACL].
     *
     * @param key The string key to look up.
     * @return The specific [FileAttributeView]::class.java type instance or `null` if the key doesn't match any known
     * value.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <V : FileAttributeView> getAttributeViewJavaClassType(key: String): Class<V>? = when (key) {
        CACHE_KEY_BASIC -> BasicFileAttributeView::class.java as Class<V>
        CACHE_KEY_DOS -> DosFileAttributeView::class.java as Class<V>
        CACHE_KEY_POSIX -> PosixFileAttributeView::class.java as Class<V>
        CACHE_KEY_ACL -> AclFileAttributeView::class.java as Class<V>
        else -> null
    }

    /**
     * Returns this path's [AttributeCachingPath.delegate] if it is an instance of a [AttributeCachingPath],
     * otherwise returns the path instance.
     */
    private fun Path.getUnderlyingPath(): Path = if (this is AttributeCachingPath) delegate else this
}
