# AttributeCachingFileSystem
A java based file attribute caching filesystem library

This library is meant to wrap another filesystem in order to intercept and cache calls for reading and setting file
attributes.

It reduces OTHER_IOPS calls as listed in the "other" column when executing [Procmon] and looking at
operations performed on a given file where file attributes are read and/or set.

## Usage Instructions
Dependency to add via gradle:
```kotlin
dependencies {
    implementation("com.pkware.filesystem:file-attribute-caching:VERSION_HERE")
    testImplementation("com.pkware.filesystem:file-attribute-caching:VERSION_HERE")
}
```
The most updated `VERSION_HERE` can be found on the maven central repository.

Examples of how this library should be used:
```kotlin
// You can specify any filesystem as the argument for wrapping.
AttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
    // getPath initializes the cache for paths made from strings if the path is a file and it exists.
    val cachingPath = it.getPath("somepath.txt")

    // Continue your code with using cachingPath...
}
```
or
```kotlin
// Wrapping returns a AttributeCachingFileSystem instance rather than a regular Filesystem to use
// convertToCachingPath.
AttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
    // convertToCachingPath initializes the cache for path objects if the path is a file and it exists.
    val cachingPath = it.convertToCachingPath(existingPath)

    // Continue your code with using cachingPath...
}
```

You can also instantiate and use the filesystem later:
```kotlin
val cachingFilesystem = AttributeCachingFileSystem.wrapping(FileSystems.getDefault())

// Continue your code using cachingFilesystem...
```

You can also wrap an existing non attribute caching path's Filesystem as an AttributeCachingFilesystem and then convert
the path to a caching path like so:
```kotlin
val path = Paths.get("test\\test.txt")
val cachingFilesystem = AttributeCachingFileSystem.wrapping(path.fileSystem)
val cachingPath = cachingFilesystem.convertToCachingPath(path)

// Continue your code using cachingPath...
```

[Procmon]: https://learn.microsoft.com/en-us/sysinternals/downloads/procmon
