package com.pkware.file.forwarding

import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.spi.FileSystemProvider

/**
 * A [FileSystemProvider] wrapper that handles forwarding all calls to the [delegate].
 *
 * @param delegate The [FileSystemProvider] to forward calls to.
 */
public abstract class ForwardingFileSystemProvider(private val delegate: FileSystemProvider) : FileSystemProvider() {
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        delegate.checkAccess(path, *modes)
    }

    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        delegate.copy(source, target, *options)
    }

    override fun <V : FileAttributeView?> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V = delegate.getFileAttributeView(path, type, *options)

    override fun isSameFile(path: Path, path2: Path): Boolean = delegate.isSameFile(path, path2)

    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = delegate.newFileSystem(uri, env)

    override fun getScheme(): String = delegate.scheme

    override fun isHidden(path: Path): Boolean = delegate.isHidden(path)

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> =
        delegate.newDirectoryStream(dir, filter)

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel = delegate.newByteChannel(path, options, *attrs)

    override fun delete(path: Path) {
        delegate.delete(path)
    }

    override fun <A : BasicFileAttributes?> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A = delegate.readAttributes(path, type, *options)

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
        delegate.readAttributes(path, attributes, *options)

    override fun getFileSystem(uri: URI): FileSystem = delegate.getFileSystem(uri)

    override fun getPath(uri: URI): Path = delegate.getPath(uri)

    override fun getFileStore(path: Path): FileStore = delegate.getFileStore(path)

    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) {
        delegate.setAttribute(path, attribute, value, *options)
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        delegate.move(source, target, *options)
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        delegate.createDirectory(dir, *attrs)
    }
}
