package com.pkware.filesystem.attributecaching

import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth.assertAbout

internal class AttributeCachingPathSubject internal constructor(
    metadata: FailureMetadata,
    private val actual: AttributeCachingPath?,
) : Subject(metadata, actual) {

    /**
     * Asserts that the [AttributeCachingPath] is caching the given [cacheableAttributes]
     *
     * @param cacheableAttributes Array of arguments whose values will be verified to be assigned.
     */
    fun caches(vararg cacheableAttributes: CacheableAttribute) {
        checkNotNull(actual)
        cacheableAttributes.forEach { check(attributeName(it)).that(getValueToCheck(actual, it)).isTrue() }
    }

    /**
     * Asserts that the [AttributeCachingPath] is not caching the given [cacheableAttributes]
     *
     * @param cacheableAttributes Array of arguments whose values will be verified to be unassigned.
     */
    fun doesNotCache(vararg cacheableAttributes: CacheableAttribute) {
        checkNotNull(actual)
        cacheableAttributes.forEach { check(attributeName(it)).that(getValueToCheck(actual, it)).isFalse() }
    }

    /**
     * Asserts that the [AttributeCachingPath] has assigned all of its attributes.
     */
    fun hasCacheFilled() = caches(
        CacheableAttribute.BASIC,
        CacheableAttribute.DOS,
        CacheableAttribute.ACL_OWNER,
        CacheableAttribute.ACL_ENTRIES,
        CacheableAttribute.POSIX,
    )

    /**
     * Asserts that the [AttributeCachingPath] has no values cached.
     */
    fun hasEmptyCache() = doesNotCache(
        CacheableAttribute.BASIC,
        CacheableAttribute.DOS,
        CacheableAttribute.ACL_OWNER,
        CacheableAttribute.ACL_ENTRIES,
        CacheableAttribute.POSIX,
    )

    /**
     * Asserts that only the given variables representing the underlying attributes are cached.
     *
     * E.g. assertThat(path).onlyCaches(basic = true) makes sure that only the basic attributes are cached.
     */
    fun onlyCaches(vararg cacheableAttributes: CacheableAttribute) {
        checkNotNull(actual)
        val toAllow = hashSetOf<CacheableAttribute>()
        toAllow.addAll(cacheableAttributes)
        val toForbid = ALLOWED_ENTRIES.subtract(toAllow)

        toAllow.forEach { check(attributeName(it)).that(getValueToCheck(actual, it)).isTrue() }
        toForbid.forEach { check(attributeName(it)).that(getValueToCheck(actual, it)).isFalse() }
    }

    private fun attributeName(attr: CacheableAttribute): String = when (attr) {
        CacheableAttribute.BASIC -> "cachedBasicAttributes.initialized"
        CacheableAttribute.DOS -> "cachedDosAttributes.initialized"
        CacheableAttribute.ACL_OWNER -> "cachedAccessControlListOwner.initialized"
        CacheableAttribute.ACL_ENTRIES -> "cachedAccessControlListEntries.initialized"
        CacheableAttribute.POSIX -> "cachedPosixAttributes.initialized"
    }

    private fun getValueToCheck(path: AttributeCachingPath, attr: CacheableAttribute): Boolean = when (attr) {
        CacheableAttribute.BASIC -> path.cachedBasicAttributes.initialized
        CacheableAttribute.DOS -> path.cachedDosAttributes.initialized
        CacheableAttribute.ACL_OWNER -> path.cachedAccessControlListOwner.initialized
        CacheableAttribute.ACL_ENTRIES -> path.cachedAccessControlListEntries.initialized
        CacheableAttribute.POSIX -> path.cachedPosixAttributes.initialized
    }

    enum class CacheableAttribute {
        BASIC,
        DOS,
        ACL_OWNER,
        ACL_ENTRIES,
        POSIX,
    }

    companion object {
        private val ALLOWED_ENTRIES = CacheableAttribute.values().toSet()

        @JvmStatic
        fun assertThat(path: AttributeCachingPath): AttributeCachingPathSubject =
            assertAbout(::AttributeCachingPathSubject).that(path)
    }
}
