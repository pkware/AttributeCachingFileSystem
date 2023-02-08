# FileAttributeCachingFileSystem
A java based file attribute caching fileSystem library

This library is meant to wrap another filesystem in order to intercept and cache calls for reading and setting file
attributes.

It reduces OTHER_IOPS calls as listed in the "other" column when executing [Procmon] and looking at
operations performed on a given file where file attributes are read and/or set.

## Usage Instructions
Examples of how this library should be used:
```kotlin
// You can specify any filesystem as the argument for wrapping.
FileAttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
    // getPath initializes the cache for paths made from strings if the path is a file and it exists.
    val cachingPath = it.getPath("somepath.txt")

    // Continue your code with using cachingPath...
}
```
or
```kotlin
// Wrapping returns a FileAttributeCachingFilesystem instance rather than a regular Filesystem to use
// convertToCachingPath.
FileAttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
    // convertToCachingPath initializes the cache for path objects if the path is a file and it exists.
    val cachingPath = it.convertToCachingPath(existingPath)

    // Continue your code with using cachingPath...
}
```

You can also instantiate and use the filesystem later:
```kotlin
val cachingFilesystem = FileAttributeCachingFileSystem.wrapping(FileSystems.getDefault())

// Continue your code using cachingFilesystem...
```

[Procmon]: https://learn.microsoft.com/en-us/sysinternals/downloads/procmon
