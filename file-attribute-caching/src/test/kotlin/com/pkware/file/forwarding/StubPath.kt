package com.pkware.file.forwarding

import java.nio.file.Path

/**
 * Does nothing except forward calls to the [delegate]. Will not compile if [ForwardingPath] does not implement
 * all members.
 */
class StubPath(delegate: Path) : ForwardingPath(delegate)
