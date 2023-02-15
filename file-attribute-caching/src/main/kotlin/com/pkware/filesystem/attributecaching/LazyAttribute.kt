package com.pkware.filesystem.attributecaching

import kotlin.reflect.KProperty

/**
 * Stores a mutable attribute that is lazily calculated via [initializer] whenever the value is `null` and accessed.
 */
internal class LazyAttribute<T>(private val initializer: () -> T?) {

    private var value: T? = null

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? {
        if (value == null) value = initializer()
        return value
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T?) {
        value = newValue
    }
}
