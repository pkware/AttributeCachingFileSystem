package com.pkware.file.fileAttributeCaching

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
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.spi.FileSystemProvider
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * A [FileSystemProvider] wrapper that handles [FileAttributeCachingPath]s for reading and writing file attributes.
 *
 * It forwards most operations using the [FileAttributeCachingPath]s underlying delegate [Path]. It caches file
 * attributes ([BasicFileAttributes], [DosFileAttributes], and [PosixFileAttributes]) on reads, writes, copy and move
 * operations.
 */
@AutoService(FileSystemProvider::class)
internal class FileAttributeCachingFileSystemProvider : FileSystemProvider() {

    private val fileSystems = mutableMapOf<URI, FileSystem>()
    private val isWindowsOS by lazy {
        // Adapted from org/junit/jupiter/api/condition/OS.java to look up the operating system name
        System.getProperty("os.name").lowercase(Locale.ENGLISH).contains("win")
    }

    override fun getScheme(): String = "cache"

    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem =
        fileSystems.computeIfAbsent(uri) {
            FileAttributeCachingFileSystem(
                env.getValue("filesystem") as FileSystem,
                this
            )
        }

    override fun getFileSystem(uri: URI): FileSystem =
        fileSystems[uri] ?: throw FileSystemNotFoundException("Filesystem for $uri not found.")

    override fun getPath(uri: URI): Path = Paths.get(uri)

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>?
    ): SeekableByteChannel = if (path is FileAttributeCachingPath) {
        Files.newByteChannel(path.delegate, options, *attrs)
    } else {
        Files.newByteChannel(path, options, *attrs)
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> = if (dir is FileAttributeCachingPath) {
        Files.newDirectoryStream(dir.delegate, filter)
    } else {
        Files.newDirectoryStream(dir, filter)
    }

    override fun createDirectory(
        dir: Path,
        vararg attrs: FileAttribute<*>
    ) {
        if (dir is FileAttributeCachingPath) {
            Files.createDirectory(dir.delegate, *attrs)
        } else {
            Files.createDirectory(dir, *attrs)
        }
    }

    override fun delete(path: Path) {
        if (path is FileAttributeCachingPath) {
            Files.delete(path.delegate)
        } else {
            Files.delete(path)
        }
    }

    /**
     * Copies [source] to [target] with the specified [options].
     *
     * If the [options] include [StandardCopyOption.COPY_ATTRIBUTES] and both the [source] and [target] are
     * [FileAttributeCachingPath]s, then the cached attributes are copied from the [source] to the [target].
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
        val actualSourcePath = if (source is FileAttributeCachingPath) source.delegate else source
        val actualTargetPath = if (target is FileAttributeCachingPath) target.delegate else target

        // If we have both target and source caching paths we copy the attributes from source to target and run
        // Files.copy(source, target, *newOptions).
        if (options.contains(StandardCopyOption.COPY_ATTRIBUTES) &&
            source is FileAttributeCachingPath &&
            target is FileAttributeCachingPath
        ) {
            // Filter out StandardCopyOption.COPY_ATTRIBUTES here because we dont want the copied file to repopulate
            // the cache from the delegate provider/filesystem.
            val newOptions = options.filter {
                it != StandardCopyOption.COPY_ATTRIBUTES
            }.toTypedArray()

            source.copyCachedAttributesTo(target)
            Files.copy(actualSourcePath, actualTargetPath, *newOptions)
        } else {
            // If the StandardCopyOption.COPY_ATTRIBUTES option is not selected, there is no need to cache the
            // attributes for the copied file.
            Files.copy(actualSourcePath, actualTargetPath, *options)
        }
    }

    /**
     * Moves [source] to [target] with the specified [options].
     *
     * If the [options] include [StandardCopyOption.COPY_ATTRIBUTES] and both the [source] and [target] are
     * [FileAttributeCachingPath]s, then only the cached attributes are copied from the [source] to the [target].
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
        val actualSourcePath = if (source is FileAttributeCachingPath) source.delegate else source
        val actualTargetPath = if (target is FileAttributeCachingPath) target.delegate else target

        // If we have both target and source caching paths we copy the attributes from source to target and run
        // Files.move(source, target, *newOptions).
        if (options.contains(StandardCopyOption.COPY_ATTRIBUTES) &&
            source is FileAttributeCachingPath &&
            target is FileAttributeCachingPath
        ) {
            // Filter out StandardCopyOption.COPY_ATTRIBUTES here because we dont want the moved file to repopulate
            // the cache from the delegate provider/filesystem.
            val newOptions = options.filter {
                it != StandardCopyOption.COPY_ATTRIBUTES
            }.toTypedArray()

            source.copyCachedAttributesTo(target)
            Files.move(actualSourcePath, actualTargetPath, *newOptions)
        } else {
            // If the StandardCopyOption.COPY_ATTRIBUTES option is not selected, there is no need to cache the
            // attributes for the moved file.
            Files.move(actualSourcePath, actualTargetPath, *options)
        }
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        val actualPath = if (path is FileAttributeCachingPath) path.delegate else path
        val actualPath2 = if (path2 is FileAttributeCachingPath) path2.delegate else path2

        return Files.isSameFile(actualPath, actualPath2)
    }

    /**
     * Tells whether a file is considered to be hidden. The exact definition of hidden is platform or provider
     * dependent.
     *
     * On Windows a file is considered hidden if it isn't a directory and the DOS hidden attribute is set.
     *
     * On UNIX a file is considered to be hidden if its name begins with a period character ('.').
     *
     * Depending on the implementation, this method may access the [path] to determine if the file is
     * considered hidden.
     *
     * @param path The path to check.
     * @return `true` if the path is hidden, `false` otherwise.
     * @throws IOException if an error occurs while accessing the underlying delegate provider or the provided path was
     * not a [FileAttributeCachingPath].
     */
    @Throws(IOException::class)
    override fun isHidden(path: Path): Boolean = if (path is FileAttributeCachingPath) {
        if (isWindowsOS) {
            val attributesMap = path.getAllAttributesMatchingName("dos:*") ?: throw IOException(
                "Could not get dos attributes of $path from delegate filesystem."
            )
            val isHidden = attributesMap["dos:hidden"] as Boolean && !(attributesMap["directory"] as Boolean)
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
    override fun getFileStore(path: Path): FileStore = if (path is FileAttributeCachingPath) {
        val delegatePath = path.delegate
        val providerDelegate = delegatePath.fileSystem.provider()
        providerDelegate.getFileStore(delegatePath)
    } else {
        val provider = path.fileSystem.provider()
        provider.getFileStore(path)
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode?) {
        if (path is FileAttributeCachingPath) {
            val delegatePath = path.delegate
            val providerDelegate = delegatePath.fileSystem.provider()
            providerDelegate.checkAccess(delegatePath, *modes)
        } else {
            val provider = path.fileSystem.provider()
            provider.checkAccess(path, *modes)
        }
    }

    override fun <V : FileAttributeView?> getFileAttributeView(
        path: Path,
        type: Class<V>?,
        vararg options: LinkOption?
    ): V = if (path is FileAttributeCachingPath) {
        val delegatePath = path.delegate
        val providerDelegate = delegatePath.fileSystem.provider()
        providerDelegate.getFileAttributeView(delegatePath, type, *options)
    } else {
        val provider = path.fileSystem.provider()
        provider.getFileAttributeView(path, type, *options)
    }

    @Throws(
        IllegalArgumentException::class,
        UnsupportedOperationException::class,
        FileAlreadyExistsException::class,
        IOException::class
    )
    override fun newFileChannel(
        path: Path,
        options: Set<OpenOption?>,
        vararg attrs: FileAttribute<*>?
    ): FileChannel = if (path is FileAttributeCachingPath) {
        val delegatePath = path.delegate
        val providerDelegate = delegatePath.fileSystem.provider()
        providerDelegate.newFileChannel(delegatePath, options, *attrs)
    } else {
        val provider = path.fileSystem.provider()
        provider.newFileChannel(path, options, *attrs)
    }

    @Throws(
        IllegalArgumentException::class,
        UnsupportedOperationException::class,
        FileAlreadyExistsException::class,
        IOException::class
    )
    override fun newAsynchronousFileChannel(
        path: Path,
        options: Set<OpenOption?>,
        executor: ExecutorService,
        vararg attrs: FileAttribute<*>?
    ): AsynchronousFileChannel = if (path is FileAttributeCachingPath) {
        val delegatePath = path.delegate
        val providerDelegate = delegatePath.fileSystem.provider()
        providerDelegate.newAsynchronousFileChannel(delegatePath, options, executor, *attrs)
    } else {
        val providerDelegate = path.fileSystem.provider()
        providerDelegate.newAsynchronousFileChannel(path, options, executor, *attrs)
    }

    @Throws(
        UnsupportedOperationException::class,
        FileAlreadyExistsException::class,
        IOException::class
    )
    override fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>?) {
        val actualLinkPath = if (link is FileAttributeCachingPath) link.delegate else link
        val actualTargetPath = if (target is FileAttributeCachingPath) target.delegate else target
        Files.createSymbolicLink(actualLinkPath, actualTargetPath, *attrs)
    }

    @Throws(
        UnsupportedOperationException::class,
        FileAlreadyExistsException::class,
        IOException::class
    )
    override fun createLink(link: Path, existing: Path) {
        val actualLinkPath = if (link is FileAttributeCachingPath) link.delegate else link
        val actualExistingPath = if (existing is FileAttributeCachingPath) existing.delegate else existing
        Files.createLink(actualLinkPath, actualExistingPath)
    }

    @Throws(
        UnsupportedOperationException::class,
        NotLinkException::class,
        IOException::class
    )
    override fun readSymbolicLink(link: Path): Path? = if (link is FileAttributeCachingPath) {
        Files.readSymbolicLink(link.delegate)
    } else {
        Files.readSymbolicLink(link)
    }

    /**
     * Read file attributes specified by the attribute `Class` [type] from the incoming [path]. If the returned
     * attributes are `null` we then attempt to get them from the [path]s delegate [FileSystemProvider] and populate
     * the [path]'s cache with those attributes.
     *
     * The attributes returned will always be from the [path] itself, never directly from the [path]s delegate.
     *
     * @param path The [Path] to read file attributes from. It must be a [FileAttributeCachingPath] otherwise an
     * [IOException] will be thrown.
     * @param type The `Class` of the file attributes to be read. `Class` types include [BasicFileAttributes],
     * [DosFileAttributes], or [PosixFileAttributes].
     * @param options The [LinkOption]s indicating how symbolic links are handled.
     * @return The file attributes for the given [path].
     * @throws IOException  If something goes wrong with the underlying calls to the [path]s delegate
     * [FileSystemProvider].
     * @throws UnsupportedOperationException If the [path] is not a [FileAttributeCachingPath] or the attributes of the
     * given [type] are not supported.
     */
    @Throws(IOException::class, UnsupportedOperationException::class)
    override fun <A : BasicFileAttributes?> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A = if (path is FileAttributeCachingPath) {
        val attributes = path.getAllAttributesMatchingClass(type) ?: throw UnsupportedOperationException(
            "Could not read attributes of type $type from the path $path and its delegate filesystem."
        )
        attributes
    } else {
        path.fileSystem.provider().readAttributes(path, type, *options)
    }

    /**
     * Read file [attributes] from the incoming [path]. If the returned attributes are `null` we then attempt to get
     * them from the [path]s delegate [FileSystemProvider] and populate the [path]s cache with those attributes.
     *
     * The attributes returned will always be from the [path] itself, never directly from the [path]s delegate.
     *
     * @param path The [Path] to read file attributes from. It must be a [FileAttributeCachingPath] otherwise an
     * [IOException] will be thrown.
     * @param attributes The attributes to be retrieved from the [path]. Can be single attributes or an entire attribute
     * `Class` String (ie: "dos:*","basic:*","posix:permissions", etc.).
     * @param options The [LinkOption]s indicating how symbolic links are handled.
     * @return The file attributes for the given [path] as a [MutableMap].
     * @throws IOException  If something goes wrong with the underlying calls to the [path]s delegate
     * [FileSystemProvider].
     * @throws UnsupportedOperationException If the [path] is not a [FileAttributeCachingPath].
     * @throws IllegalArgumentException If the [attributes] are not recognized, or they cannot be read from the delegate
     * [FileSystemProvider].
     */
    @Throws(IOException::class, UnsupportedOperationException::class, IllegalArgumentException::class)
    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): MutableMap<String, Any> = if (path is FileAttributeCachingPath) {
        val attributesMap = path.getAllAttributesMatchingName(attributes) ?: throw IllegalArgumentException(
            "Could not read attributes $attributes of the path $path from the delegate filesystem."
        )
        attributesMap
    } else {
        path.fileSystem.provider().readAttributes(path, attributes, *options)
    }

    /**
     * Set a single attribute or attribute class (ie: "dos:*","basic:*","posix:permissions", etc.) for the given [path]
     * first on the [path]'s delegate [FileSystemProvider] and then in the [path]s cache.
     *
     * @param path The [Path] to set the given [attribute] on. It must be a [FileAttributeCachingPath] otherwise an
     * [IOException] will be thrown.
     * @param attribute The attribute name to set and associate with the [path].
     * @param value The value of the [attribute] to set.
     * @param options The [LinkOption]s indicating how symbolic links are handled.
     * @throws IOException If an IO error occurs.
     * @throws UnsupportedOperationException If the attribute view for the given [attribute] name is not available.
     * @throws IllegalArgumentException If the [attribute] name is not recognized or if its value is of the incorrect
     * type.
     */
    @Throws(IOException::class, UnsupportedOperationException::class, IllegalArgumentException::class)
    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) {
        if (path is FileAttributeCachingPath) {
            val delegatePath = path.delegate
            val delegateProvider = delegatePath.fileSystem.provider()

            // Always set delegate attribute(s) first with real file IO
            delegateProvider.setAttribute(delegatePath, attribute, value, *options)

            // Then set our cache
            // Need to make sure that we only supply class names to path.setAttributeByName
            // cannot set single attribute in the cache
            val attributeClassName: String = if (attribute.startsWith("dos")) {
                "dos:*"
            } else if (attribute.startsWith("posix")) {
                "posix:*"
            } else {
                "*"
            }

            // Even if we have a single attribute only we should get the entire attribute class for that single
            // attribute to properly set the cache.
            val attributesObject = getAttributesClassFromPathProvider(path, attributeClassName)
            path.setAttributeByName(attributeClassName, attributesObject)
        } else {
            throw UnsupportedOperationException(
                "Could not set attribute(s) or the attribute cache for $path. Path was not a FileAttributeCachingPath."
            )
        }
    }

    /**
     * Obtain the attribute `Class` for a given [path] and [attributes] String.
     *
     * @param path The [FileAttributeCachingPath] to obtain the attribute `Class` from.
     * @param attributes The attributes used to look up the attribute `Class`. Can be single attributes or an entire
     * attribute `Class` String (ie: "dos:*","basic:*","posix:permissions", etc.).
     * @return The attribute `Class` for the [path] from the given [attributes] or `null` if the `Class` does not exist.
     * @throws IOException if an error occurs while trying to obtain the attribute `Class`.
     */
    @Throws(IOException::class)
    private fun getAttributesClassFromPathProvider(
        path: FileAttributeCachingPath,
        attributes: String
    ): BasicFileAttributes? {
        val delegatePath = path.delegate
        val delegateProvider = delegatePath.fileSystem.provider()

        val attributeView: BasicFileAttributeView? = if (attributes.startsWith("dos")) {
            delegateProvider.getFileAttributeView(delegatePath, DosFileAttributeView::class.java)
        } else if (attributes.startsWith("posix")) {
            delegateProvider.getFileAttributeView(delegatePath, PosixFileAttributeView::class.java)
        } else {
            delegateProvider.getFileAttributeView(delegatePath, BasicFileAttributeView::class.java)
        }
        return attributeView?.readAttributes()
    }
}
