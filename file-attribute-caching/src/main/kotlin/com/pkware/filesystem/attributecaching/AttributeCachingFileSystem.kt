package com.pkware.filesystem.attributecaching

import com.pkware.filesystem.forwarding.ForwardingFileSystem
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.ProviderNotFoundException
import java.nio.file.spi.FileSystemProvider
import java.util.UUID

/**
 * A [FileSystem] wrapper that utilizes a [AttributeCachingFileSystemProvider] and provides
 * [AttributeCachingPath]s when [getPath] is called.
 *
 * A [ForwardingFileSystem] is used to handle forwarding most filesystem operations to the [delegate].
 *
 * @param delegate The [FileSystem] to wrap and forward calls to.
 * @param provider The [FileSystemProvider] associated with this [FileSystem].
 */
public class AttributeCachingFileSystem(delegate: FileSystem, private val provider: FileSystemProvider) :
    ForwardingFileSystem(delegate) {

    override fun provider(): FileSystemProvider = provider

    override fun getPath(first: String, vararg more: String?): Path {
        val delegate = super.getPath(first, *more)
        return AttributeCachingPath(this, delegate)
    }

    /**
     * Converts [path] to a [AttributeCachingPath] if it is not one and initializes its cache if the [path] is
     * exists and is a filesystem.
     *
     * @param path The [Path] to convert.
     * @return A [Path] that now has [AttributeCachingPath] properties, or the original [path] object.
     */
    public fun convertToCachingPath(path: Path): Path = if (path !is AttributeCachingPath) {
        AttributeCachingPath(this, path)
    } else {
        path
    }

    public companion object {
        /**
         * Wraps the incoming [fileSystem] with an instance of this [AttributeCachingFileSystem] and uses
         * [AttributeCachingFileSystemProvider] as its provider.
         *
         * The created [FileSystem] [URI] is prefixed with "cache" and provides a unique ID with every call to
         * [wrapping].
         *
         * @param fileSystem The [FileSystem] to wrap and associate with this [AttributeCachingFileSystem] instance.
         * @return A new instance of this [AttributeCachingFileSystem].
         * @throws FileAlreadyExistsException If the underlying generated [URI] matches an existing
         * [FileSystem] - this should never occur.
         * @throws ProviderNotFoundException If the underlying [AttributeCachingFileSystem] cannot be found or
         * initialized.
         * @throws IOException If an IO error occurs.
         */
        @Throws(FileAlreadyExistsException::class, ProviderNotFoundException::class, IOException::class)
        public fun wrapping(fileSystem: FileSystem): AttributeCachingFileSystem = FileSystems.newFileSystem(
            // Need to ensure a unique fileSystem name everytime this is called, hence UUID.randomUUID()
            URI.create("cache:///${UUID.randomUUID()}"),
            mapOf(Pair("filesystem", fileSystem)),
        ) as AttributeCachingFileSystem
    }
}
