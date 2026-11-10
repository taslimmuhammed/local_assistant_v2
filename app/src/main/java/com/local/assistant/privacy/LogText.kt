package com.local.assistant.privacy

/**
 * The user's own words and data in a log line: what they asked, a reply, a tool's arguments, a
 * place's or a document's name. A debuggable build shows them, since that is where the logs are
 * read to tune the app; a release build puts [HIDDEN] in their place, since logcat goes into bug
 * reports and to anything reading the phone over adb (heading for production, the user,
 * 10 Nov 2026). The rest of the line — counts, ids, timings — stays in both.
 */
object LogText {

    /** Set once as the app starts, from the build: debuggable or not. */
    @Volatile
    var showsUserText: Boolean = false

    /** [value] in a debuggable build, [HIDDEN] in a release one. */
    fun of(value: Any?): String = if (showsUserText) value.toString() else HIDDEN

    /**
     * An exception to log alongside, only in a debuggable build: its message can carry the text
     * that failed (an FTS syntax error quotes the query).
     */
    fun trace(e: Throwable): Throwable? = e.takeIf { showsUserText }

    const val HIDDEN = "…"
}
