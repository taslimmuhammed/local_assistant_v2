package com.local.assistant.gallery

import com.local.assistant.ui.gallery.clock
import com.local.assistant.ui.gallery.itemCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLabelsTest {

    @Test
    fun lengthsReadLikeAPlayer() {
        assertEquals("0:00", clock(0))
        assertEquals("0:09", clock(9_999))
        assertEquals("1:23", clock(83_000))
        assertEquals("1:02:03", clock(3_723_000))
    }

    @Test
    fun countsSayPhotosAndVideos() {
        assertEquals("30 photos", itemCount(30, 0))
        assertEquals("1 photo", itemCount(1, 0))
        assertEquals("1 video", itemCount(1, 1))
        assertEquals("28 photos and 2 videos", itemCount(30, 2))
        assertEquals("1 photo and 1 video", itemCount(2, 1))
    }

    @Test
    fun videoLocationTags() {
        assertEquals(12.458 to 75.959, iso6709("+12.4580+075.9590/"))
        assertEquals(-33.8688 to 151.2093, iso6709("-33.8688+151.2093+012.000/"))
        assertNull(iso6709("+00.0000+000.0000/"))
        assertNull(iso6709("somewhere"))
    }
}
