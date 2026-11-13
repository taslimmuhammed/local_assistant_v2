package com.local.assistant.memory.prompt

/**
 * A reply as the chat ends up showing it ([com.local.assistant.chat.ChatSender]): text marked
 * provisional stands once confirmed and is dropped again when retracted, so an eval scores what
 * the user is left reading, not an opening that was taken back.
 */
class ShownReply {
    private val text = StringBuilder()
    private var standing = 0

    /** Takes in [event]; true when it was reply text. */
    fun add(event: TurnEvent): Boolean {
        when (event) {
            is TurnEvent.Text -> {
                text.append(event.delta)
                if (!event.provisional) standing = text.length
                return true
            }
            TurnEvent.Confirm -> standing = text.length
            TurnEvent.Retract -> text.setLength(standing)
            else -> Unit
        }
        return false
    }

    override fun toString(): String = text.toString()
}
