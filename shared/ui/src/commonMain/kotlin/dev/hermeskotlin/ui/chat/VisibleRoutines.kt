package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TranscriptEvent

/** A presentation-only filter: the original transcript and group boundaries remain untouched. */
internal fun List<ChatMessage>.withVisibleRoutines(showRoutines: Boolean): List<ChatMessage> =
    filter { message -> (message as? ChatMessage.Event)?.event?.isVisible(showRoutines) != false }

private fun TranscriptEvent.isVisible(showRoutines: Boolean): Boolean = when (this) {
    is TranscriptEvent.Routine -> showRoutines
    is TranscriptEvent.SystemMessages -> entries.any { it.isVisible(showRoutines) }
    else -> true
}

/** Keys count from the unfiltered newest entry, so toggling or paging older entries preserves disclosure state. */
internal fun List<TranscriptEvent>.visibleSystemEntries(showRoutines: Boolean): List<Pair<Int, TranscriptEvent>> =
    mapIndexedNotNull { index, event ->
        if (event.isVisible(showRoutines)) (size - index) to event else null
    }
