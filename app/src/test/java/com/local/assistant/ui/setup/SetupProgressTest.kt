package com.local.assistant.ui.setup

import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.llm.LlmService
import com.local.assistant.model.InstalledModel
import com.local.assistant.model.Transfer
import com.local.assistant.model.TransferKind
import com.local.assistant.voice.VoicePackInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupProgressTest {

    private val model = InstalledModel(File("model.litertlm"), 3_659_530_240L)
    private val photoModel = InstalledModel(File("photos.litertlm"), 387_710_976L)

    /** A new install, photos allowed, before anything is fetched. */
    private val fresh = SetupState(
        chatModel = null,
        chatTransfer = null,
        chatError = null,
        chatPartialBytes = 0,
        engine = LlmService.State.NoModel,
        preparing = false,
        photoModel = null,
        photoTransfer = null,
        photoError = null,
        voice = null,
        voiceProgress = null,
        voiceError = null,
        photosAllowed = true,
        photos = GalleryIndex.Status(),
        findPeople = true,
        faces = FaceIndex.Status(),
        memoryLeft = 0,
    )

    private fun SetupState.step(kind: StepKind) = steps().single { it.kind == kind }

    @Test
    fun `a new install waits on the chat model's download, which the user starts`() {
        assertEquals(StepStatus.START, fresh.step(StepKind.CHAT_MODEL).status)
        assertEquals(StepStatus.WAITING, fresh.step(StepKind.READY).status)
        assertEquals(StepStatus.WAITING, fresh.step(StepKind.PHOTOS).status)
        // Nothing earlier to make searchable on a new install.
        assertTrue(fresh.steps().none { it.kind == StepKind.MEMORY })
        assertNull(fresh.pill())
    }

    @Test
    fun `a download shows how far it's got, and a part left behind is picked up`() {
        val downloading = fresh.copy(chatTransfer = Transfer(TransferKind.DOWNLOAD, 1_000_000_000L, 4_000_000_000L, bytesPerSecond = 10_000_000L))
        val step = downloading.step(StepKind.CHAT_MODEL)
        assertEquals(StepStatus.RUNNING, step.status)
        assertEquals(0.25f, step.fraction!!, 0.001f)
        assertTrue(step.detail, "left" in step.detail)

        val resumable = fresh.copy(chatPartialBytes = 1_000_000_000L).step(StepKind.CHAT_MODEL)
        assertEquals(StepStatus.START, resumable.status)
        assertNotNull(resumable.fraction)
    }

    @Test
    fun `a download pauses, a copy cancels, and neither while it's checked`() {
        val downloading = fresh.copy(chatTransfer = Transfer(TransferKind.DOWNLOAD, 1_000_000_000L, 4_000_000_000L))
        assertEquals("Pause", downloading.step(StepKind.CHAT_MODEL).stop)
        assertEquals("Cancel", fresh.copy(chatTransfer = Transfer(TransferKind.IMPORT, 10, 100)).step(StepKind.CHAT_MODEL).stop)
        assertNull(fresh.copy(chatTransfer = Transfer(TransferKind.DOWNLOAD, 100, 100, checking = true)).step(StepKind.CHAT_MODEL).stop)
    }

    @Test
    fun `paused downloads say so, each with a Resume, and the chat's note leads there`() {
        val paused = fresh.copy(chatPartialBytes = 1_000_000_000L, paused = true)
        val chat = paused.step(StepKind.CHAT_MODEL)
        assertEquals(StepStatus.START, chat.status)
        assertTrue(chat.detail, chat.detail.startsWith("Paused"))

        val later = fresh.copy(chatModel = model, engine = LlmService.State.Ready("GPU", 8192), paused = true, photoPartialBytes = 100_000_000L)
        val photo = later.step(StepKind.PHOTO_MODEL)
        assertEquals(StepStatus.PAUSED, photo.status)
        assertTrue(photo.resume)
        // The voice hadn't begun: it waits its turn, as before.
        assertEquals(StepStatus.WAITING, later.step(StepKind.VOICE).status)
        assertEquals("Downloads paused · tap to resume", later.pill()!!.text)
        // Bytes left by a Cancel, with nothing paused, don't make it paused.
        assertEquals(StepStatus.WAITING, later.copy(paused = false).step(StepKind.PHOTO_MODEL).status)
    }

    @Test
    fun `the first load runs after the download, then it's ready`() {
        val loading = fresh.copy(chatModel = model, engine = LlmService.State.Loading(0, null), preparing = true)
        assertEquals(StepStatus.RUNNING, loading.step(StepKind.READY).status)
        val ready = loading.copy(engine = LlmService.State.Ready("GPU", 8192), preparing = false)
        assertEquals(StepStatus.DONE, ready.step(StepKind.READY).status)
        val failed = loading.copy(engine = LlmService.State.Failed("Out of memory"))
        assertEquals(StepStatus.FAILED, failed.step(StepKind.READY).status)
    }

    @Test
    fun `photos are counted as they're indexed, and a warm phone pauses it`() {
        val indexing = fresh.copy(photoModel = photoModel, photos = GalleryIndex.Status(total = 4000, indexed = 1000, running = true))
        val step = indexing.step(StepKind.PHOTOS)
        assertEquals(StepStatus.RUNNING, step.status)
        assertEquals(0.25f, step.fraction!!, 0.001f)
        assertEquals(StepStatus.PAUSED, indexing.copy(photos = indexing.photos.copy(cooling = true)).step(StepKind.PHOTOS).status)
        assertEquals(StepStatus.DONE, indexing.copy(photos = GalleryIndex.Status(total = 4000, indexed = 4000)).step(StepKind.PHOTOS).status)
    }

    @Test
    fun `no photo steps without photos allowed, and no faces without Find people`() {
        assertTrue(fresh.copy(photosAllowed = false).steps().none { it.kind == StepKind.PHOTOS || it.kind == StepKind.PEOPLE })
        assertTrue(fresh.copy(findPeople = false).steps().none { it.kind == StepKind.PEOPLE })
    }

    @Test
    fun `the chat's note names what goes on behind it, and only what's worth one`() {
        val chatting = fresh.copy(chatModel = model, engine = LlmService.State.Ready("GPU", 8192))
        assertNull(chatting.pill())

        val photoDownload = chatting.copy(photoTransfer = Transfer(TransferKind.DOWNLOAD, 50, 100))
        assertEquals("Downloading photo search · 50%", photoDownload.pill()!!.text)

        val voice = chatting.copy(photoModel = photoModel, voiceProgress = VoicePackInstaller.Progress.Unpacking(0.4f))
        assertEquals("Unpacking the natural voice", voice.pill()!!.text)

        val indexing = chatting.copy(photoModel = photoModel, photos = GalleryIndex.Status(total = 4000, indexed = 1000, running = true))
        assertTrue(indexing.pill()!!.text.startsWith("Indexing photos"))
        // A few new photos, as the nightly pass does them: no note.
        assertNull(chatting.copy(photoModel = photoModel, photos = GalleryIndex.Status(total = 4000, indexed = 3990, running = true)).pill())
    }
}
