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
     * Asserts that the [AttributeCachingPath] has no values cached.
     */
    fun hasEmptyCache() = onlyCaches()

    /**
     * Asserts that only the given variables representing the underlying attributes are cached.
     *
     * E.g. assertThat(path).onlyCaches(basic = true) makes sure that only the basic attributes are cached.
     */
    fun onlyCaches(vararg cacheableAttributes: CacheableAttribute) {
        checkNotNull(actual)
        val toAllow = cacheableAttributes.toSet()
        val toForbid = ALLOWED_ENTRIES.subtract(toAllow)

        toAllow.forEach { check(attributeName(it)).that(getValueToCheck(actual, it)).isTrue() }
        toForbid.forEach { check(attributeName(it)).that(getValueToCheck(actual, it)).isFalse() }
    }

    private fun attributeName(attr: CacheableAttribute): String = when (attr) {
        CacheableAttribute.BASIC -> "cachedBasicAttributes.assigned"
        CacheableAttribute.DOS -> "cachedDosAttributes.assigned"
        CacheableAttribute.ACL_OWNER -> "cachedAccessControlListOwner.assigned"
        CacheableAttribute.ACL_ENTRIES -> "cachedAccessControlListEntries.assigned"
        CacheableAttribute.POSIX -> "cachedPosixAttributes.assigned"
    }

    private fun getValueToCheck(path: AttributeCachingPath, attr: CacheableAttribute): Boolean = when (attr) {
        CacheableAttribute.BASIC -> path.cachedBasicAttributes.assigned
        CacheableAttribute.DOS -> path.cachedDosAttributes.assigned
        CacheableAttribute.ACL_OWNER -> path.cachedAccessControlListOwner.assigned
        CacheableAttribute.ACL_ENTRIES -> path.cachedAccessControlListEntries.assigned
        CacheableAttribute.POSIX -> path.cachedPosixAttributes.assigned
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
