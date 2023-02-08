package com.pkware.file

import java.nio.file.NoSuchFileException
import kotlin.reflect.KProperty

/**
 * Stores a mutable attribute that is lazily calculated via [initializer] whenever the value is `null` and accessed.
 */
internal class LazyAttribute<T>(private val initializer: () -> T?) {

    private var value: T? = null

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? {
        if (value == null) value = exceptionSwallower(initializer)
        return value
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T?) {
        value = newValue
    }

    private fun <T> exceptionSwallower(lambda: () -> T): T? {
        return try {
            lambda()
        } catch (exception: NoSuchFileException) {
            // Swallow NoSuchFileExceptions and skip cache initializations for now, since checking if we exist, and
            // if we are a regular file do incur OTHER_IOPS penalties.
            null
        }
    }
}
