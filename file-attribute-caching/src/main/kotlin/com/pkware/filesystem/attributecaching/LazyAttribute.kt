package com.pkware.filesystem.attributecaching

/**
 * Stores a mutable attribute [value] that is lazily calculated via [initializer] whenever the value is `null` and
 * accessed.
 *
 * Can check the initialization/assignment status of the attribute via [assigned].
 *
 */
internal class LazyAttribute<T>(private val initializer: () -> T?) {

    /**
     * Shows whether the [value] has been assigned. `true` for initialized/assigned, default is `false`.
     *
     * If a value is assigned that means it has either had its initializer called via the invocation of its getter
     * or its setter.
     */
    var assigned: Boolean = false
        private set

    /**
     * The mutable attribute to store. If [assigned] is false the [initializer] function is called to set the
     * attribute.
     */
    var value: T? = null
        get() {
            if (assigned) return field

            field = initializer()
            assigned = true

            return field
        }
        set(value) {
            field = value
            assigned = true
        }

    /**
     * Copies the [value] of [other] into this [LazyAttribute] instance's [value] if [other] has a value assigned or
     * [forceInitialization] is `true`.
     *
     * If [forceInitialization] is `true` this forces [other] to call its [initializer] function if its own [value]
     * is not [assigned].
     *
     * @param other The other [LazyAttribute] to copy.
     * @param forceInitialization Forces a copy of [other]'s value/initialization when `true`, otherwise only copies
     * values that had already been assigned
     */
    fun copyValue(other: LazyAttribute<T>, forceInitialization: Boolean = false) {
        value = if (forceInitialization || other.assigned) {
            other.value
        } else {
            null
        }
        assigned = other.assigned
    }
}
