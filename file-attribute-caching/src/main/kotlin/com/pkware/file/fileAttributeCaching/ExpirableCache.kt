package com.pkware.file.fileAttributeCaching

import java.time.Clock
import java.util.function.Function

/**
 * A generic cache that expires after [flushInterval] milliseconds.
 *
 * The keys of the cache are Strings, the values can be anything.
 * The type of the cache values is defined when the cache is instantiated.
 *
 * @param flushInterval The time in milliseconds after which the cache is flushed.
 */
internal class ExpirableCache<K, V>(private val flushInterval: Long) {
    private val clock = Clock.systemUTC()
    private var lastFlushTime = clock.millis()
    private val cache = HashMap<K, V?>()

    /**
     * The size of the cache.
     */
    val size: Int
        get() {
            recycle()
            return cache.size
        }

    /**
     * First checks if the cache has expired or not. Then goes through the logic of [java.util.HashMap.computeIfAbsent]
     * using the [mappingFunction] to compute a value if it is absent for a given [key].
     *
     * @param key The key to look up in this [ExpirableCache].
     * @param mappingFunction The function to use when computing a new value if the [ExpirableCache] is expired, or it
     * does not exist. Must not be `null`.
     * @return The value associated with the [key] if it exists or `null` if it does not exist and the
     * [mappingFunction] could not create it.
     * @throws ConcurrentModificationException if the [mappingFunction] attempts to modify the [ExpirableCache].
     */
    @Throws(ConcurrentModificationException::class)
    fun computeIfExpiredOrAbsent(
        key: K,
        mappingFunction: Function<in K, out V?>
    ): V? {
        recycle()
        return cache.computeIfAbsent(key, mappingFunction)
    }

    /**
     * Sets the [value] of this [ExpirableCache] for the given [key].
     * @param key The key to lookup.
     * @param value The value to set.
     */
    operator fun set(key: K, value: V?) {
        cache[key] = value
    }

    /**
     * Removes the given [key] from this [ExpirableCache] if it exists and the cache is not expired.
     * @param key The key to remove.
     * @return The value associated with the removed key or `null` if it doesn't exist or the cache expired.
     */
    fun remove(key: K): V? {
        recycle()
        return cache.remove(key)
    }

    /**
     * Gets the given value associated with the [key] from this [ExpirableCache] if it exists and the cache is not
     * expired.
     *
     * @param key The key to lookup.
     * @return The value associated with the [key] or `null` if the key doesn't exist or the cache expired.
     */
    operator fun get(key: K): V? {
        recycle()
        return cache[key]
    }

    /**
     * Clears this [ExpirableCache].
     */
    fun clear() = cache.clear()

    /**
     * Determines whether the [ExpirableCache] should be expired or not based on the current [java.time.Clock.millis]
     * the given [flushInterval], and the last time the cache was flushed.
     *
     * If the cache is expired, it is cleared and the last flush time is updated to the current [java.time.Clock.millis].
     */
    private fun recycle() {
        val currentMillis = clock.millis()
        val shouldRecycle = currentMillis - lastFlushTime >= flushInterval
        if (!shouldRecycle) return
        lastFlushTime = currentMillis
        cache.clear()
    }
}
