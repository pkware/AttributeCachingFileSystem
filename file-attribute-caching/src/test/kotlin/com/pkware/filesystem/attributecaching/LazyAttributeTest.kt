package com.pkware.filesystem.attributecaching

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LazyAttributeTest {

    @Test
    fun `initializes when value is used`() {
        val tracker = InitializedTracker()
        val lazyAttribute = LazyAttribute {
            tracker.initialize()
        }

        assertThat(tracker.calls).isEqualTo(0)
        assertThat(lazyAttribute.value).isEqualTo(1)
    }

    @Test
    fun `value is only initialized once`() {
        val tracker = InitializedTracker()
        val lazyAttribute = LazyAttribute {
            tracker.initialize()
        }

        assertThat(tracker.calls).isEqualTo(0)
        assertThat(lazyAttribute.value).isEqualTo(1)
        assertThat(lazyAttribute.value).isEqualTo(1)
        assertThat(tracker.calls).isEqualTo(1)
    }

    @Test
    fun `does not initialize on default copy`() {
        val tracker = InitializedTracker()
        val lazyAttribute = LazyAttribute {
            tracker.initialize()
        }
        val lazyCopy = LazyAttribute {
            tracker.initialize()
        }

        assertThat(tracker.calls).isEqualTo(0)

        lazyCopy.copyValue(lazyAttribute)
        assertThat(tracker.calls).isEqualTo(0)
    }

    @Test
    fun `initializes on copy forcedInitialization`() {
        val tracker = InitializedTracker()
        val lazyAttribute = LazyAttribute {
            tracker.initialize()
        }
        val lazyCopy = LazyAttribute {
            tracker.initialize()
        }

        assertThat(tracker.calls).isEqualTo(0)

        lazyCopy.copyValue(lazyAttribute, false)
        assertThat(tracker.calls).isEqualTo(0)

        lazyCopy.copyValue(lazyAttribute, true)
        assertThat(tracker.calls).isEqualTo(1)
    }
}

class InitializedTracker(var calls: Int = 0) {
    fun initialize(): Int = ++calls
}
