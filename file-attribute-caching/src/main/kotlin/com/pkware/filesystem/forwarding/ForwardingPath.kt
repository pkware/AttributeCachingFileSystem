package com.pkware.filesystem.forwarding

import java.io.File
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * A [Path] wrapper that handles forwarding all calls to the [delegate].
 *
 * @param delegate The [Path] to forward calls to.
 */
public abstract class ForwardingPath(private val delegate: Path) : Path {
    override fun endsWith(other: String): Boolean = delegate.endsWith(other)

    override fun register(watcher: WatchService, vararg events: WatchEvent.Kind<*>?): WatchKey =
        delegate.register(watcher, events)

    override fun startsWith(other: String): Boolean = delegate.startsWith(other)

    override fun resolve(other: String): Path = delegate.resolve(other)

    override fun toFile(): File = delegate.toFile()

    override fun iterator(): MutableIterator<Path> = delegate.iterator()

    override fun resolveSibling(other: Path): Path = delegate.resolveSibling(other)

    override fun resolveSibling(other: String): Path = delegate.resolveSibling(other)

    override fun getFileSystem(): FileSystem = delegate.fileSystem

    override fun getFileName(): Path? = delegate.fileName

    override fun isAbsolute(): Boolean = delegate.isAbsolute

    override fun getName(index: Int): Path = delegate.getName(index)

    override fun subpath(beginIndex: Int, endIndex: Int): Path = delegate.subpath(beginIndex, endIndex)

    override fun endsWith(other: Path): Boolean = delegate.endsWith(other)

    override fun register(
        watcher: WatchService,
        events: Array<out WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier?,
    ): WatchKey = delegate.register(watcher, events, *modifiers)

    override fun relativize(other: Path): Path = delegate.relativize(other)

    override fun toUri(): URI = delegate.toUri()

    override fun toRealPath(vararg options: LinkOption?): Path = delegate.toRealPath(*options)

    override fun normalize(): Path = delegate.normalize()

    override fun getParent(): Path? = delegate.parent

    override fun compareTo(other: Path): Int = delegate.compareTo(other)

    override fun getNameCount(): Int = delegate.nameCount

    override fun startsWith(other: Path): Boolean = delegate.startsWith(other)

    override fun getRoot(): Path? = delegate.root

    override fun resolve(other: Path): Path = delegate.resolve(other)

    override fun toAbsolutePath(): Path = delegate.toAbsolutePath()
}
