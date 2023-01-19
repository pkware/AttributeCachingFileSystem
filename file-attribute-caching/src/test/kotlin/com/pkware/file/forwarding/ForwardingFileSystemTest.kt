package com.pkware.file.forwarding

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.FileSystem
import java.nio.file.Paths

class ForwardingFileSystemTest {

    @Test
    fun getPathVarargsAreCorrectlyForwarded() {
        val mock: FileSystem = mock()
        val stub = StubFileSystem(mock)

        // Varargs with no entries
        var path = Paths.get("first")
        whenever(mock.getPath("first")).thenReturn(path)
        assertThat(stub.getPath("first")).isSameInstanceAs(path)

        // Varargs with a single entry
        path = Paths.get("first", "second")
        whenever(mock.getPath("first", "second")).thenReturn(path)
        assertThat(stub.getPath("first", "second")).isSameInstanceAs(path)

        // Varargs with a multiple entries
        path = Paths.get("first", "second", "third")
        whenever(mock.getPath("first", "second", "third")).thenReturn(path)
        assertThat(stub.getPath("first", "second", "third")).isSameInstanceAs(path)
    }
}
