package com.pkware.file.fileAttributeCaching

import com.pkware.file.forwarding.ForwardingFileSystem
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.ProviderNotFoundException
import java.nio.file.spi.FileSystemProvider
import java.util.UUID

/**
 * A [FileSystem] wrapper that utilizes a [FileAttributeCachingFileSystemProvider] and provides
 * [FileAttributeCachingPath]s when [getPath] is called.
 *
 * A [ForwardingFileSystem] is used to handle forwarding most filesystem operations to the [delegate].
 *
 * @param delegate The [FileSystem] to wrap and forward calls to.
 * @param provider The [FileSystemProvider] associated with this [FileSystem].
 */
public class FileAttributeCachingFileSystem(
    delegate: FileSystem,
    private val provider: FileSystemProvider,
) : ForwardingFileSystem(delegate) {

    override fun provider(): FileSystemProvider = provider

    override fun getPath(first: String, vararg more: String?): Path {
        val delegate = super.getPath(first, *more)
        val cachingPath = FileAttributeCachingPath(this, delegate)
        cachingPath.initializeCache()
        return cachingPath
    }

    /**
     * Converts [path] to a [FileAttributeCachingPath] if it is not one and initializes its cache if the [path] is
     * exists and is a file.
     *
     * @param path The [Path] to convert.
     * @return A [Path] that now has [FileAttributeCachingPath] properties, or the original [path] object.
     */
    public fun convertToCachingPath(path: Path): Path = if (path !is FileAttributeCachingPath) {
        val cachingPath = FileAttributeCachingPath(this, path)
        cachingPath.initializeCache()
        cachingPath
    } else {
        path
    }

    public companion object {
        /**
         * Wraps the incoming [fileSystem] with an instance of this [FileAttributeCachingFileSystem] and uses
         * [FileAttributeCachingFileSystemProvider] as its provider.
         *
         * The created [FileSystem] [URI] is prefixed with "cache" and provides a unique ID with every call to
         * [wrapping].
         *
         * @param fileSystem The [FileSystem] to wrap and associate with this [FileAttributeCachingFileSystem] instance.
         * @return A new instance of this [FileAttributeCachingFileSystem].
         * @throws FileAlreadyExistsException If the underlying generated [URI] matches an existing
         * [FileSystem] - this should never occur.
         * @throws ProviderNotFoundException If the underlying [FileAttributeCachingFileSystem] cannot be found or
         * initialized.
         * @throws IOException If an IO error occurs.
         */
        @Throws(FileAlreadyExistsException::class, ProviderNotFoundException::class, IOException::class)
        public fun wrapping(
            fileSystem: FileSystem,
        ): FileAttributeCachingFileSystem = FileSystems.newFileSystem(
            // Need to ensure a unique fileSystem name everytime this is called, hence UUID.randomUUID()
            URI.create("cache:///${UUID.randomUUID()}"),
            mapOf(Pair("filesystem", fileSystem))
        ) as FileAttributeCachingFileSystem
    }
}
