package com.local.assistant.ui.memory

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeTest {

    @Test
    fun `a lambda after a notice is its undo, not what runs when it goes`() {
        val notice = Notice("Deleted “Buy milk”") { }

        assertNotNull(notice.undo)
        assertNull(notice.settle)
    }
}
