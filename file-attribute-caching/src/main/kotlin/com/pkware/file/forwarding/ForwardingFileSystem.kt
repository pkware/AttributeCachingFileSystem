package com.pkware.file.forwarding

import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchService
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider

/**
 * A [FileSystem] wrapper that handles forwarding all calls to the [delegate].
 *
 * @param delegate The [FileSystem] to forward calls to.
 */
public abstract class ForwardingFileSystem(private val delegate: FileSystem) : FileSystem() {

    override fun close() {
        // Do not close the delegate if it is the default filesystem.
        if (delegate != FileSystems.getDefault()) delegate.close()
    }

    override fun getSeparator(): String = delegate.separator

    override fun newWatchService(): WatchService = delegate.newWatchService()

    override fun supportedFileAttributeViews(): MutableSet<String> = delegate.supportedFileAttributeViews()

    override fun isReadOnly(): Boolean = delegate.isReadOnly

    override fun getFileStores(): MutableIterable<FileStore> = delegate.fileStores

    override fun isOpen(): Boolean = delegate.isOpen

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = delegate.userPrincipalLookupService

    override fun getPathMatcher(syntaxAndPattern: String?): PathMatcher = delegate.getPathMatcher(syntaxAndPattern)

    override fun getRootDirectories(): MutableIterable<Path> = delegate.rootDirectories

    override fun getPath(first: String, vararg more: String?): Path = delegate.getPath(first, *more)

    override fun provider(): FileSystemProvider = delegate.provider()
}
