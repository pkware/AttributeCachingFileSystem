# Change Log

## [Unreleased]

## [1.0.4]
- Gradle, Kotlin, and other dependency versions updated.

## [1.0.3]
- Make the AttributeCachingPath on demand and remove all init logic.
- Add individual public properties to check if a given attribute has been cached.
- AttributeCachingPath copyCachedAttributesTo() only copies attributes if they were previously cached or we force copy
attributes via forceCopyAttributes = true.

## [1.0.2]
- Gradle, Kotlin, and other dependency versions updated.
- Add caching of AclFileAttributeView owner and aclEntries attributes as properties of AttributeCachingPath.
- Modified AttributeCachingFileSystemProvider setAttribute() to support AclFileAttributeView.
- Add function overrides to AttributeCachingPath for resolve() and resolveSibling() when called with strings.
- Fixed issues with multithreaded calling of AttributeCachingFileSystemProvider newFileSystem().

## [1.0.1]
Fixed issues with AttributeCachingPath not being used to wrap parent, root, and filename paths.

## [1.0.0]
Initial work for the file attribute caching filesystem using cached attribute fields that do not expire.

[Unreleased]: https://github.com/pkware/attributeCachingFileSystem/tree/main
[1.0.4]: https://github.com/pkware/attributeCachingFileSystem/tree/1.0.4
[1.0.3]: https://github.com/pkware/attributeCachingFileSystem/tree/1.0.3
[1.0.2]: https://github.com/pkware/attributeCachingFileSystem/tree/1.0.2
[1.0.1]: https://github.com/pkware/attributeCachingFileSystem/tree/1.0.1
[1.0.0]: https://github.com/pkware/attributeCachingFileSystem/tree/1.0.0
