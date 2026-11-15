package com.local.assistant.ui.setup

import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.GalleryModel
import com.local.assistant.gallery.IndexService
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.llm.LlmService
import com.local.assistant.model.InstalledModel
import com.local.assistant.model.ModelCatalog
import com.local.assistant.model.Transfer
import com.local.assistant.model.TransferKind
import com.local.assistant.model.formatBytes
import com.local.assistant.voice.VoicePack
import com.local.assistant.voice.VoicePackInstaller
import java.io.File
import java.text.NumberFormat

/**
 * Everything a first setup does, as one value: the three downloads, the chat model's first load,
 * and the indexing that follows (photos, the faces in them, earlier chats). [SetupScreen] lists
 * it step by step, and the chat's pill names the one under way ([pill]).
 */
internal data class SetupState(
    val chatModel: InstalledModel?,
    val chatTransfer: Transfer?,
    val chatError: String?,
    /** Bytes of an earlier download that a new one picks up from. */
    val chatPartialBytes: Long,
    val engine: LlmService.State,
    /** The new model is loading and reading the first chat's prompt (`AppContainer.prepareFirstChat`). */
    val preparing: Boolean,
    val photoModel: InstalledModel?,
    val photoTransfer: Transfer?,
    val photoError: String?,
    val voice: File?,
    val voiceProgress: VoicePackInstaller.Progress?,
    val voiceError: String?,
    val photosAllowed: Boolean,
    val photos: GalleryIndex.Status,
    val findPeople: Boolean,
    val faces: FaceIndex.Status,
    /** Chunks of earlier chats waiting to be made searchable; null before the queue has counted. */
    val memoryLeft: Int?,
    /** The user paused the downloads (`AppContainer.pauseDownloads`). */
    val paused: Boolean = false,
    /** What the photo model's and the voice's stopped downloads already have. */
    val photoPartialBytes: Long = 0,
    val voicePartialBytes: Long = 0,
)

internal enum class StepKind { CHAT_MODEL, READY, PHOTO_MODEL, VOICE, PHOTOS, PEOPLE, MEMORY }

internal enum class StepStatus {
    /** Waiting its turn: after another step, or for something to arrive. */
    WAITING,

    /** Needs the user's tap to begin: the chat model's download. */
    START,
    RUNNING,

    /** Stopped for now: a warm phone, the app closed, or a download the user paused. */
    PAUSED,
    DONE,
    FAILED,
}

/** One line of the setup: what it is, how it's going, and how far it's got (null when that's unknown). */
internal data class SetupStep(
    val kind: StepKind,
    val title: String,
    val detail: String,
    val status: StepStatus,
    val fraction: Float? = null,
    /** The button that stops it while it runs: a download pauses, a copy is cancelled. */
    val stop: String? = null,
    /** Paused by the user, with a Resume. */
    val resume: Boolean = false,
)

/** The chat's one-line note while setup work goes on, and how far it's got. */
internal data class SetupPill(val text: String, val fraction: Float?)

private val numbers: NumberFormat get() = NumberFormat.getIntegerInstance()

private fun transferDetail(transfer: Transfer): String = when {
    transfer.checking -> "Checking the download"
    else -> buildString {
        append("${formatBytes(transfer.bytesDone)} of ${formatBytes(transfer.bytesTotal)}")
        transfer.etaSeconds?.let { append(" · ${formatBytes(transfer.bytesPerSecond)}/s · ${duration(it)} left") }
    }
}

internal fun duration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    seconds >= 60 -> "${seconds / 60} min"
    else -> "${seconds.coerceAtLeast(1)} s"
}

internal fun SetupState.steps(): List<SetupStep> = buildList {
    add(chatModelStep())
    add(readyStep())
    add(photoModelStep())
    add(voiceStep())
    if (photosAllowed) add(photosStep())
    if (photosAllowed && findPeople) add(peopleStep())
    memoryStep()?.let(::add)
}

private fun SetupState.chatModelStep(): SetupStep {
    val title = "Chat model · ${ModelCatalog.DISPLAY_NAME}"
    return when {
        chatModel != null -> SetupStep(StepKind.CHAT_MODEL, title, "Here · ${formatBytes(chatModel.sizeBytes)}, runs with no internet", StepStatus.DONE)
        chatTransfer != null -> SetupStep(
            StepKind.CHAT_MODEL,
            title,
            if (chatTransfer.kind == TransferKind.IMPORT) "Copying · ${transferDetail(chatTransfer)}" else transferDetail(chatTransfer),
            StepStatus.RUNNING,
            chatTransfer.fraction.takeIf { chatTransfer.bytesTotal > 0 && !chatTransfer.checking },
            // All of it is here while it's checked.
            stop = when {
                chatTransfer.checking -> null
                chatTransfer.kind == TransferKind.IMPORT -> "Cancel"
                else -> "Pause"
            },
        )
        chatError != null -> SetupStep(StepKind.CHAT_MODEL, title, chatError, StepStatus.FAILED)
        chatPartialBytes > 0 -> SetupStep(
            StepKind.CHAT_MODEL,
            title,
            if (paused) {
                "Paused · ${formatBytes(chatPartialBytes)} of ${formatBytes(ModelCatalog.SIZE_BYTES)}; Resume picks up from there"
            } else {
                "${formatBytes(chatPartialBytes)} of ${formatBytes(ModelCatalog.SIZE_BYTES)} already here; the rest picks up from there"
            },
            StepStatus.START,
            (chatPartialBytes.toFloat() / ModelCatalog.SIZE_BYTES).coerceIn(0f, 1f),
        )
        else -> SetupStep(StepKind.CHAT_MODEL, title, "${formatBytes(ModelCatalog.SIZE_BYTES)}, once. Best on Wi-Fi.", StepStatus.START)
    }
}

private fun SetupState.readyStep(): SetupStep {
    val title = "Getting it ready"
    return when {
        chatModel == null -> SetupStep(StepKind.READY, title, "Loads into memory and reads its instructions, about 15 s", StepStatus.WAITING)
        engine is LlmService.State.Failed -> SetupStep(StepKind.READY, title, engine.message, StepStatus.FAILED)
        preparing -> SetupStep(
            StepKind.READY,
            title,
            if (engine is LlmService.State.Ready) "Reading its instructions…" else "Loading the model into memory…",
            StepStatus.RUNNING,
        )
        else -> SetupStep(StepKind.READY, title, "Ready to chat", StepStatus.DONE)
    }
}

private fun SetupState.photoModelStep(): SetupStep {
    val title = "Photo and memory search"
    return when {
        photoModel != null -> SetupStep(StepKind.PHOTO_MODEL, title, "Here · ${formatBytes(photoModel.sizeBytes)}", StepStatus.DONE)
        photoTransfer != null -> SetupStep(
            StepKind.PHOTO_MODEL,
            title,
            transferDetail(photoTransfer),
            StepStatus.RUNNING,
            photoTransfer.fraction.takeIf { photoTransfer.bytesTotal > 0 && !photoTransfer.checking },
        )
        photoError != null -> SetupStep(StepKind.PHOTO_MODEL, title, photoError, StepStatus.FAILED)
        paused && photoPartialBytes > 0 -> pausedStep(StepKind.PHOTO_MODEL, title, photoPartialBytes, GalleryModel.SIZE_BYTES)
        else -> SetupStep(StepKind.PHOTO_MODEL, title, "${formatBytes(GalleryModel.SIZE_BYTES)}, downloads after the chat model", StepStatus.WAITING)
    }
}

private fun SetupState.voiceStep(): SetupStep {
    val title = "Natural voice · ${VoicePack.KOKORO.title}"
    return when {
        voice != null -> SetupStep(StepKind.VOICE, title, "Here · answers can be read aloud", StepStatus.DONE)
        voiceProgress is VoicePackInstaller.Progress.Downloading -> SetupStep(
            StepKind.VOICE,
            title,
            transferDetail(voiceProgress.transfer),
            StepStatus.RUNNING,
            voiceProgress.transfer.fraction.takeIf { voiceProgress.transfer.bytesTotal > 0 && !voiceProgress.transfer.checking },
        )
        voiceProgress is VoicePackInstaller.Progress.Unpacking -> SetupStep(StepKind.VOICE, title, "Unpacking", StepStatus.RUNNING, voiceProgress.fraction)
        voiceError != null -> SetupStep(StepKind.VOICE, title, voiceError, StepStatus.FAILED)
        paused && voicePartialBytes > 0 -> pausedStep(StepKind.VOICE, title, voicePartialBytes, VoicePack.KOKORO.archiveBytes)
        else -> SetupStep(StepKind.VOICE, title, "${formatBytes(VoicePack.KOKORO.archiveBytes)}, downloads after the photo model", StepStatus.WAITING)
    }
}

private fun SetupState.photosStep(): SetupStep {
    val title = "Indexing your photos"
    val counted = "${numbers.format(photos.indexed)} of ${numbers.format(photos.total)}"
    val fraction = if (photos.total > 0) photos.indexed.toFloat() / photos.total else null
    return when {
        photoModel == null -> SetupStep(StepKind.PHOTOS, title, "Starts when the photo model is here", StepStatus.WAITING)
        photos.cooling -> SetupStep(StepKind.PHOTOS, title, "$counted · paused while the phone cools", StepStatus.PAUSED, fraction)
        photos.running && photos.total == 0 -> SetupStep(StepKind.PHOTOS, title, "Finding your photos…", StepStatus.RUNNING)
        photos.running -> SetupStep(StepKind.PHOTOS, title, "$counted · on this phone only", StepStatus.RUNNING, fraction)
        photos.total > 0 && photos.remaining == 0 -> SetupStep(StepKind.PHOTOS, title, "${numbers.format(photos.total)} photos, searchable by what's in them", StepStatus.DONE)
        photos.total > 0 -> SetupStep(
            StepKind.PHOTOS,
            title,
            "$counted · carries on while the app is open, or at night on the charger",
            StepStatus.PAUSED,
            fraction,
        )
        else -> SetupStep(StepKind.PHOTOS, title, "Starting…", StepStatus.WAITING)
    }
}

private fun SetupState.peopleStep(): SetupStep {
    val title = "Finding people"
    val all = faces.photosDone + faces.photosLeft
    val fraction = if (all > 0) faces.photosDone.toFloat() / all else null
    return when {
        photoModel == null || all == 0 -> SetupStep(StepKind.PEOPLE, title, "Faces are grouped as the photos are indexed", StepStatus.WAITING)
        faces.photosLeft == 0 -> SetupStep(StepKind.PEOPLE, title, "Done · name them in Photos", StepStatus.DONE)
        photos.running -> SetupStep(StepKind.PEOPLE, title, "${numbers.format(faces.photosLeft)} photos left · faces stay on this phone", StepStatus.RUNNING, fraction)
        else -> SetupStep(StepKind.PEOPLE, title, "${numbers.format(faces.photosLeft)} photos left · carries on with the photos", StepStatus.PAUSED, fraction)
    }
}

/** Only with earlier chats to go through: a new install has none. */
private fun pausedStep(kind: StepKind, title: String, have: Long, size: Long) = SetupStep(
    kind,
    title,
    "Paused · ${formatBytes(have)} of ${formatBytes(size)}",
    StepStatus.PAUSED,
    (have.toFloat() / size).coerceIn(0f, 1f),
    resume = true,
)

private fun SetupState.memoryStep(): SetupStep? {
    val left = memoryLeft ?: return null
    if (left <= 0) return null
    return SetupStep(
        StepKind.MEMORY,
        "Making earlier chats searchable",
        if (photoModel == null) "${numbers.format(left)} left · after the photo model" else "${numbers.format(left)} left",
        if (photoModel == null) StepStatus.WAITING else StepStatus.RUNNING,
    )
}

/**
 * The chat's note while setup carries on in the background, or null: the downloads after the
 * chat model, and indexing when there's enough of it to notice ([IndexService.BACKLOG], as for
 * its notification). The nightly pass over a few new photos isn't worth one.
 */
internal fun SetupState.pill(): SetupPill? {
    // Paused after the chat model was in: the way to Resume is the setup page the pill opens.
    if (paused && chatModel != null && (photoPartialBytes > 0 || voicePartialBytes > 0)) return SetupPill("Downloads paused · tap to resume", null)
    photoTransfer?.let { return SetupPill("Downloading photo search · ${percent(it)}", it.fraction.takeIf { _ -> it.bytesTotal > 0 }) }
    when (voiceProgress) {
        is VoicePackInstaller.Progress.Downloading ->
            return SetupPill("Downloading the natural voice · ${percent(voiceProgress.transfer)}", voiceProgress.transfer.fraction.takeIf { voiceProgress.transfer.bytesTotal > 0 })
        is VoicePackInstaller.Progress.Unpacking -> return SetupPill("Unpacking the natural voice", voiceProgress.fraction)
        null -> Unit
    }
    if (photosAllowed && photoModel != null && (photos.running || photos.cooling) && photos.remaining >= IndexService.BACKLOG) {
        val fraction = if (photos.total > 0) photos.indexed.toFloat() / photos.total else null
        val paused = if (photos.cooling) " · paused, phone warm" else ""
        return SetupPill("Indexing photos · ${numbers.format(photos.indexed)} of ${numbers.format(photos.total)}$paused", fraction)
    }
    if (photosAllowed && findPeople && photos.running && faces.photosLeft >= IndexService.BACKLOG) {
        val all = faces.photosDone + faces.photosLeft
        return SetupPill("Finding people · ${numbers.format(faces.photosLeft)} photos left", if (all > 0) faces.photosDone.toFloat() / all else null)
    }
    if (photoModel != null && (memoryLeft ?: 0) >= MEMORY_WORTH_A_NOTE) {
        return SetupPill("Making earlier chats searchable · ${numbers.format(memoryLeft)} left", null)
    }
    return null
}

private fun percent(transfer: Transfer): String =
    if (transfer.checking || transfer.bytesTotal <= 0) "checking" else "${(transfer.fraction * 100).toInt()}%"

/** Fewer earlier messages than this are done in seconds. */
private const val MEMORY_WORTH_A_NOTE = 50
