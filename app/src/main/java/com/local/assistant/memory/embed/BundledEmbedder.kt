package com.local.assistant.memory.embed

import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * EmbeddingGemma as shipped with the app: the fast-follow asset pack `:embedder_pack`, which
 * Play downloads by itself right after install and unpacks to internal storage, where
 * LiteRT-LM opens it by path. Nothing to host, nothing for the user to do.
 *
 * Absent from an app installed outside Play (a debug APK): then the user's own download or
 * import is the only embedder, as before. One the user picked themselves always comes first.
 */
class BundledEmbedder(
    context: Context,
    /** The pack has arrived: there is an embedder now, and the archive can be indexed. */
    private val onReady: () -> Unit,
) {
    sealed interface State {
        /** Not delivered: installed outside Play, or Play could not fetch it. */
        data object Unavailable : State

        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State

        /** Play holds big downloads back until the phone is on Wi-Fi. */
        data object WaitingForWifi : State

        data class Ready(val file: File) : State
    }

    private val manager: AssetPackManager? =
        runCatching { AssetPackManagerFactory.getInstance(context.applicationContext) }.getOrNull()

    private val _state = MutableStateFlow<State>(locate()?.let(State::Ready) ?: State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The model file, once Play has delivered it. */
    val file: File? get() = (_state.value as? State.Ready)?.file

    private val listener = AssetPackStateUpdateListener { update -> onUpdate(update) }

    /** Asks Play for the pack when it hasn't arrived yet; does nothing outside Play. */
    fun ensure() {
        val manager = manager ?: return
        if (file != null) return
        manager.registerListener(listener)
        manager.fetch(listOf(PACK))
            .addOnSuccessListener { states -> states.packStates()[PACK]?.let(::onUpdate) }
            .addOnFailureListener { Log.i(TAG, "No embedder pack from Play: ${it.message}") }
    }

    private fun onUpdate(update: AssetPackState) {
        if (update.name() != PACK) return
        _state.value = when (update.status()) {
            AssetPackStatus.COMPLETED -> locate()?.let(State::Ready) ?: State.Unavailable
            AssetPackStatus.PENDING, AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING ->
                State.Downloading(update.bytesDownloaded(), update.totalBytesToDownload())
            AssetPackStatus.WAITING_FOR_WIFI, AssetPackStatus.REQUIRES_USER_CONFIRMATION -> State.WaitingForWifi
            else -> State.Unavailable
        }
        if (_state.value is State.Ready) {
            manager?.unregisterListener(listener)
            Log.i(TAG, "Embedder pack delivered: ${file?.path}")
            onReady()
        }
    }

    private fun locate(): File? = runCatching { manager?.getPackLocation(PACK)?.assetsPath() }
        .getOrNull()
        ?.let { File(it, "$DIRECTORY/$FILE_NAME") }
        ?.takeIf { it.isFile }

    companion object {
        private const val TAG = "BundledEmbedder"

        /** `embedder_pack/build.gradle.kts`, and where the file sits inside the pack's assets. */
        const val PACK = "embedder_pack"
        const val DIRECTORY = "embedders"
        const val FILE_NAME = "embeddinggemma-300m_wi8.litertlm"
    }
}
