package com.pkware.file.fileAttributeCaching

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ExpirableCacheTest {

    private val attributeCache = ExpirableCache<String, String>(
        TimeUnit.SECONDS.toMillis(1)
    )

    // Setup cache with two default values for each test
    @BeforeEach
    fun setUp() {
        attributeCache["test"] = "happy"
        attributeCache["test2"] = "happy2"
    }

    @Test
    fun `cache expires properly`() {
        // sleep enough time to expire the cache
        Thread.sleep(2000)

        assertThat(attributeCache["test"]).isEqualTo(null)
        assertThat(attributeCache.size).isEqualTo(0)
    }

    @Test
    fun `cache returns active entries properly`() {
        // add a third cache entry
        attributeCache["test3"] = "deadbeef"

        // verify cache size and content
        assertThat(attributeCache.size).isEqualTo(3)
        assertThat(attributeCache["test"]).isEqualTo("happy")
        assertThat(attributeCache["test2"]).isEqualTo("happy2")
        assertThat(attributeCache["test3"]).isEqualTo("deadbeef")
    }

    @Test
    fun `computeIfExpiredOrAbsent adds absent entry`() {
        // add a third cache entry if it's absent
        attributeCache.computeIfExpiredOrAbsent("test3") { "huge" }

        // verify cache size and content
        assertThat(attributeCache.size).isEqualTo(3)
        assertThat(attributeCache["test"]).isEqualTo("happy")
        assertThat(attributeCache["test2"]).isEqualTo("happy2")
        assertThat(attributeCache["test3"]).isEqualTo("huge")
    }

    @Test
    fun `computeIfExpiredOrAbsent does not overwrite active entry`() {
        // attempt to overwrite the existing second cache entry
        attributeCache.computeIfExpiredOrAbsent("test2") { "huge" }

        // verify cache size and content
        assertThat(attributeCache.size).isEqualTo(2)
        assertThat(attributeCache["test"]).isEqualTo("happy")
        assertThat(attributeCache["test2"]).isEqualTo("happy2")
    }

    @Test
    fun `computeIfExpiredOrAbsent overwrite expired cache values`() {
        // sleep enough time to expire the cache
        Thread.sleep(2000)

        // verify cache is expired
        assertThat(attributeCache.size).isEqualTo(0)
        // compute cache entry since its expired
        attributeCache.computeIfExpiredOrAbsent("test2") { "huge" }

        // verify only new entry was added
        assertThat(attributeCache.size).isEqualTo(1)
        assertThat(attributeCache["test2"]).isEqualTo("huge")
    }

    @Test
    fun `cache can expire multiple times and still add values`() {
        // sleep enough time to expire the cache
        Thread.sleep(2000)

        // verify cache is expired
        assertThat(attributeCache.size).isEqualTo(0)
        // compute cache entry since its expired
        attributeCache.computeIfExpiredOrAbsent("test") { "huge" }
        // verify only new entry was added
        assertThat(attributeCache.size).isEqualTo(1)
        assertThat(attributeCache["test"]).isEqualTo("huge")

        // sleep enough time to expire the cache again
        Thread.sleep(2000)

        // verify cache is properly expired again
        assertThat(attributeCache.size).isEqualTo(0)
        assertThat(attributeCache["test"]).isEqualTo(null)

        // compute cache entry since its expired
        attributeCache.computeIfExpiredOrAbsent("test") { "huge2" }
        // verify only new entry was added
        assertThat(attributeCache.size).isEqualTo(1)
        assertThat(attributeCache["test"]).isEqualTo("huge2")
    }
}
