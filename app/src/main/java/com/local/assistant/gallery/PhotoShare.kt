package com.local.assistant.gallery

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/**
 * Photos and videos sent on through the system's share sheet, to WhatsApp or any app that takes
 * them: the phone's own files, at their MediaStore addresses, not copies. The chosen app reads
 * them through a grant on the share intent, for those items only, as the viewer's "Open in
 * another app" does; this app can pass that on because it may read them itself.
 */
object PhotoShare {

    /**
     * What the share sheet is handed: one item as [Intent.ACTION_SEND], more as
     * [Intent.ACTION_SEND_MULTIPLE], under a [type] that covers them all.
     */
    data class Plan(val action: String, val type: String, val ids: List<Long>)

    /**
     * [items] (an id and its type, "image/jpeg", in the order to send them) as a [Plan]; null
     * when there's nothing to send. An id given twice goes once.
     */
    fun plan(items: List<Pair<Long, String>>): Plan? {
        val unique = items.distinctBy { it.first }
        if (unique.isEmpty()) return null
        val action = if (unique.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        return Plan(action, commonType(unique.map { it.second }), unique.map { it.first })
    }

    /**
     * One type for all of [types]: itself when they agree ("image/jpeg"), any of the kind when
     * only that does (any image, for a JPEG and a HEIC), and anything at all for photos with videos.
     * Apps pick what to offer by it; too narrow a type and an app would turn some of them down.
     */
    fun commonType(types: List<String>): String {
        val clean = types.map { it.trim().lowercase() }.filter { it.contains('/') }
        if (clean.isEmpty() || clean.size < types.size) return ANY
        if (clean.distinct().size == 1) return clean.first()
        val kinds = clean.map { it.substringBefore('/') }.distinct()
        return if (kinds.size == 1 && kinds.first() != "*") "${kinds.first()}/*" else ANY
    }

    /** The share sheet for [plan], each item at [uri]. */
    fun chooser(plan: Plan, uri: (Long) -> Uri): Intent {
        val uris = plan.ids.map(uri)
        val send = Intent(plan.action)
            .setType(plan.type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply {
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                // The grant covers what's in the clip; the share sheet also shows them from it.
                clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            }
        return Intent.createChooser(send, null)
    }

    private const val ANY = "*/*"
}
