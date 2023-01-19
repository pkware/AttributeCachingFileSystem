package com.pkware.file.forwarding

import java.nio.file.FileSystem

/**
 * Does nothing except forward calls to the [delegate]. Will not compile if [ForwardingFileSystem] does not implement
 * all members.
 */
class StubFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate)
