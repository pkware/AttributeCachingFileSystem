package com.pkware.filesystem.attributecaching

/**
 * Stores a mutable attribute [value] that is lazily calculated via [initializer] whenever the value is `null` and
 * accessed.
 *
 * Can check the initialization status of the attribute via [initialized].
 *
 * If the mutable attribute [value] is set to `null` its [initialized] status is set to `false`.
 *
 */
internal class LazyAttribute<T>(private val initializer: () -> T?) {

    private var lazyValue: T? = null

    /**
     * Shows whether the [value] is initialized or not. `true` for initialized, default is `false`.
     *
     * If a value is initialized that means it is not `null`, without having to check/access it directly, as performing
     * that action can trigger the [initializer] function to be called.
     */
    var initialized: Boolean = false
        private set

    /**
     * The mutable attribute to store. If [initialized] is false the [initializer] function is called to set the
     * attribute.
     *
     * The attribute can be `null`.
     *
     * If the mutable attribute is set to `null` its [initialized] status is set to `false`.
     */
    var value: T?
        get() {
            if (initialized) return lazyValue

            lazyValue = initializer()
            initialized = lazyValue != null

            return lazyValue
        }
        set(value) {
            lazyValue = value
            initialized = value != null
        }

    /**
     * Copies the [value] of [other] into this [LazyAttribute] instance's [value] if [other] is initialized or
     * [forceCopyAndInitOther] is `true`.
     *
     * If [forceCopyAndInitOther] is `true` we also force [other] to call its [initializer] function if its own [value]
     * is `null`.
     *
     * @param other The other [LazyAttribute] to copy.
     * @param forceCopyAndInitOther `true` if you want to force copying of [other] and initialize [other]'s value if it
     * is `null`. Default is `false`.
     */
    fun copyValue(other: LazyAttribute<T>, forceCopyAndInitOther: Boolean = false) {
        if (forceCopyAndInitOther) {
            lazyValue = other.value
        } else if (other.initialized) {
            lazyValue = other.lazyValue
        }
        initialized = other.initialized
    }
}
