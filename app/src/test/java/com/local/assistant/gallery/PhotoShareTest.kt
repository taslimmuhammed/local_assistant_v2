package com.local.assistant.gallery

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the share sheet is handed for the photos picked from a card. */
class PhotoShareTest {

    @Test
    fun `one photo is a single send, more are a multiple send, in the order given`() {
        val one = PhotoShare.plan(listOf(7L to "image/jpeg"))!!
        assertEquals(Intent.ACTION_SEND, one.action)
        assertEquals("image/jpeg", one.type)
        assertEquals(listOf(7L), one.ids)

        val three = PhotoShare.plan(listOf(9L to "image/jpeg", 3L to "image/jpeg", 5L to "image/jpeg"))!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, three.action)
        assertEquals(listOf(9L, 3L, 5L), three.ids)
    }

    @Test
    fun `nothing left to send is no plan`() {
        assertNull(PhotoShare.plan(emptyList()))
    }

    @Test
    fun `a photo given twice goes once`() {
        val plan = PhotoShare.plan(listOf(4L to "image/png", 4L to "image/png"))!!
        assertEquals(Intent.ACTION_SEND, plan.action)
        assertEquals(listOf(4L), plan.ids)
    }

    @Test
    fun `the type covers every item, no narrower`() {
        assertEquals("image/jpeg", PhotoShare.commonType(listOf("image/jpeg", "image/jpeg")))
        assertEquals("image/*", PhotoShare.commonType(listOf("image/jpeg", "image/heic")))
        assertEquals("video/*", PhotoShare.commonType(listOf("video/mp4", "video/3gpp")))
        assertEquals("*/*", PhotoShare.commonType(listOf("image/jpeg", "video/mp4")))
        assertEquals("image/jpeg", PhotoShare.commonType(listOf("IMAGE/JPEG", " image/jpeg ")))
    }

    @Test
    fun `an unreadable type widens to anything`() {
        assertEquals("*/*", PhotoShare.commonType(listOf("image/jpeg", "")))
        assertEquals("*/*", PhotoShare.commonType(emptyList()))
        assertEquals("*/*", PhotoShare.commonType(listOf("*/*", "image/png")))
    }

    @Test
    fun `photos with a video go as anything`() {
        val plan = PhotoShare.plan(listOf(1L to "image/jpeg", 2L to "video/mp4"))!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, plan.action)
        assertEquals("*/*", plan.type)
    }
}
