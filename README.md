# AttributeCachingFileSystem
A kotlin based file attribute caching filesystem library

This library is meant to wrap another filesystem in order to intercept and cache calls for reading and setting file
attributes.

It reduces `OTHER_IOPS` calls as listed in the "other" column when executing [Procmon] and looking at
operations performed on a given file where file attributes are read and/or set.

**NOTE**: This filesystem does not support caching attributes from `FileAttributeView`(s) directly (ie: via
setting and/or getting attributes from views returned from `Files.getFileAttributeView()`). Please use
`Files.readAttributes()` and `Files.setAttribute()` to access proper attribute caching functionality.

## Installation and Usage Instructions
For installation with gradle:
* Dependency to add to the top level `build.gradle.kts` file:
```kotlin
dependencies {
    // To use the attribute caching filesystem for both production and testing:
    implementation("com.pkware.filesystem:file-attribute-caching:VERSION_HERE")
    // To use the attribute caching filesystem for testing only:
    testImplementation("com.pkware.filesystem:file-attribute-caching:VERSION_HERE")
}
```
* The most updated `VERSION_HERE` can be found on the maven central repository.

Library usage examples:
```kotlin
// Specify any filesystem as the argument for wrapping.
AttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
    // getPath initializes the cache for paths made from strings if the path is a file and it exists.
    val cachingPath = it.getPath("somepath.txt")

    // Continue code with using cachingPath...
}
```
or
```kotlin
// Wrapping returns a AttributeCachingFileSystem instance rather than a regular Filesystem to use
// convertToCachingPath.
AttributeCachingFileSystem.wrapping(FileSystems.getDefault()).use {
    // convertToCachingPath initializes the cache for path objects if the path is a file and it exists.
    val cachingPath = it.convertToCachingPath(existingPath)

    // Continue code with using cachingPath...
}
```

Instantiation and usage of the filesystem as a variable:
```kotlin
val cachingFilesystem = AttributeCachingFileSystem.wrapping(FileSystems.getDefault())

// Continue code using cachingFilesystem...
```

Example of wrapping an existing non attribute caching path's Filesystem as an AttributeCachingFilesystem and then
converting the path to a caching path:
```kotlin
val path = Paths.get("test\\test.txt")
val cachingFilesystem = AttributeCachingFileSystem.wrapping(path.fileSystem)
val cachingPath = cachingFilesystem.convertToCachingPath(path)

// Continue code using cachingPath...
```

## Releasing:
Both a normal or snapshot release can be made. In order to have the publish pipeline run, the release must be made from
a `release/*` branch. For a snapshot release, the release must be made fron a `release/*-SNAPSHOT` branch.

1. Make and checkout a release branch on github.
2. Change the version in gradle.properties to a non-SNAPSHOT version if needed.
3. Update the CHANGELOG.md for the impending release for non-SNAPSHOT versions.
4. Run `git commit -am "Release X.Y.Z."` (where X.Y.Z is the new version) in the terminal or
   command line.
5. Make a PR with your changes.
6. Merge the release PR after approval, tag the commit on the main branch with
   `git tag -a X.Y.Z -m "X.Y.Z"`(X.Y.Z is the new version).
7. Run `git push --tags`.
8. Verify [Sonatype] has the artifact published
8. Update `gradle.properties` to the next SNAPSHOT version.
9. Run `git commit -am "Prepare next development version."`
10. Make a PR with your changes.
11. Merge the next version PR after approval.

[Procmon]: https://learn.microsoft.com/en-us/sysinternals/downloads/procmon
[Sonatype]: https://central.sonatype.com/
