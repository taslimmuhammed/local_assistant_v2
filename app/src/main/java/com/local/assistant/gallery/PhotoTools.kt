package com.local.assistant.gallery

import com.local.assistant.memory.tools.ActionResult
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolError

/**
 * find_photos: the phone's photos that match what the user asked for. The model only gets the
 * counts back; the photos themselves travel in the chip and are drawn under the reply, so no
 * image goes through the model and no image tokens are spent.
 */
class PhotoTools(private val index: GalleryIndex, private val ready: () -> Boolean) {

    internal suspend fun find(query: String): ActionResult {
        if (!ready()) throw ToolError("Photo search isn't set up. Tell the user to open Photos in the menu to turn it on.")
        val (indexed, remaining) = index.counts()
        if (indexed == 0) {
            throw ToolError("The photos aren't ready for search yet. Tell the user they get ready while Photos in the menu is open, and overnight while charging.")
        }
        val found = index.searchForChat(query.take(MAX_QUERY))
            ?: throw ToolError("Photo search couldn't start. Tell the user to try again in a moment.")
        val result = linkedMapOf<String, Any?>("ok" to true, "found" to found.ids.size)
        // Said so the model doesn't call it a full answer while most of the library is unseen.
        if (remaining > 0) result["not_yet_searchable"] = remaining
        return ActionResult(
            result,
            MemoryChip(MemoryChip.Kind.PHOTOS, "Photos", found.query, photos = found).takeIf { found.ids.isNotEmpty() },
        )
    }

    private companion object {
        const val MAX_QUERY = 120
    }
}
