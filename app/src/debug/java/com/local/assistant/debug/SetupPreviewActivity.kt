package com.local.assistant.debug

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.local.assistant.appContainer
import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.llm.LlmService
import com.local.assistant.model.InstalledModel
import com.local.assistant.model.ModelCatalog
import com.local.assistant.model.Transfer
import com.local.assistant.model.TransferKind
import com.local.assistant.ui.setup.PermissionTour
import com.local.assistant.ui.setup.SetupActions
import com.local.assistant.ui.setup.SetupPage
import com.local.assistant.ui.setup.SetupPillView
import com.local.assistant.ui.setup.SetupState
import com.local.assistant.ui.setup.TourPreview
import com.local.assistant.ui.setup.pill
import com.local.assistant.ui.theme.LocalAssistantTheme
import com.local.assistant.ui.theme.installGlassBackdrop
import java.io.File

/**
 * Debug builds only: the first-run pages with made-up states, to look at on the phone without a
 * fresh install. Nothing here asks Android for anything or changes a setting.
 *
 *     adb shell am start -n com.taslim.localassistant.dev/com.local.assistant.debug.SetupPreviewActivity \
 *         --es show permissions --ei page 3 --es given LOCATION
 *     … --es show setup --es state fresh|downloading|paused|ready|pill
 */
class SetupPreviewActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val show = intent.getStringExtra("show") ?: "permissions"
        val page = intent.getIntExtra("page", 0)
        val given = intent.getStringExtra("given")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val state = intent.getStringExtra("state") ?: "fresh"
        val container = appContainer
        setContent {
            LocalAssistantTheme {
                when (show) {
                    "setup" -> {
                        val shown = states(state)
                        if (state == "pill") {
                            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 80.dp), contentAlignment = Alignment.TopCenter) {
                                shown.pill()?.let { SetupPillView(it) {} }
                            }
                        } else {
                            SetupPage(shown, SetupActions({}, {}, {}, {}), onStart = {}, onBack = null)
                        }
                    }
                    else -> PermissionTour(container, onDone = ::finish, preview = TourPreview(given, page))
                }
            }
        }
        installGlassBackdrop(this)
    }

    private fun states(name: String): SetupState {
        val fresh = SetupState(
            chatModel = null, chatTransfer = null, chatError = null, chatPartialBytes = 0,
            engine = LlmService.State.NoModel, preparing = false,
            photoModel = null, photoTransfer = null, photoError = null,
            voice = null, voiceProgress = null, voiceError = null,
            photosAllowed = true, photos = GalleryIndex.Status(), findPeople = true, faces = FaceIndex.Status(), memoryLeft = 0,
        )
        val photoModel = InstalledModel(File("photos"), 387_710_976L)
        val chatModel = InstalledModel(File("chat"), ModelCatalog.SIZE_BYTES)
        return when (name) {
            "downloading" -> fresh.copy(
                chatTransfer = Transfer(TransferKind.DOWNLOAD, 1_310_000_000L, ModelCatalog.SIZE_BYTES, bytesPerSecond = 9_400_000L),
                photoModel = photoModel,
                photos = GalleryIndex.Status(total = 4_212, indexed = 1_240, running = true),
                faces = FaceIndex.Status(photosDone = 1_240, photosLeft = 2_972),
            )
            "ready" -> fresh.copy(
                chatModel = chatModel, engine = LlmService.State.Ready("GPU", 8192),
                photoModel = photoModel,
                voiceProgress = com.local.assistant.voice.VoicePackInstaller.Progress.Downloading(Transfer(TransferKind.DOWNLOAD, 120_000_000L, 349_886_476L, bytesPerSecond = 8_000_000L)),
                photos = GalleryIndex.Status(total = 4_212, indexed = 3_105, running = true),
                faces = FaceIndex.Status(photosDone = 3_105, photosLeft = 1_107),
            )
            "paused" -> fresh.copy(
                chatPartialBytes = 1_310_000_000L,
                paused = true,
                photoPartialBytes = 120_000_000L,
            )
            "pill" -> fresh.copy(
                chatModel = chatModel, engine = LlmService.State.Ready("GPU", 8192), photoModel = photoModel,
                photos = GalleryIndex.Status(total = 4_212, indexed = 3_105, running = true),
            )
            else -> fresh
        }
    }
}
