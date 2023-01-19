package com.pkware.file.fileAttributeCaching

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import java.nio.file.FileSystem

fun linuxJimfs(): FileSystem = Jimfs.newFileSystem(
    Configuration.unix()
        .toBuilder()
        .setAttributeViews("basic", "owner", "posix", "unix", "user")
        .build()
)

fun osXJimfs(): FileSystem = Jimfs.newFileSystem(
    Configuration.osX()
        .toBuilder()
        .setAttributeViews("basic", "owner", "posix", "unix", "user")
        .build()
)

fun windowsJimfs(): FileSystem = Jimfs.newFileSystem(
    Configuration.windows()
        .toBuilder()
        .setAttributeViews("basic", "owner", "dos", "acl", "user")
        .build()
)
