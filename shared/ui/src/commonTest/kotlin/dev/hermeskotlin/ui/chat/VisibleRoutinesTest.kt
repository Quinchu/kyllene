package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TranscriptEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class VisibleRoutinesTest {
    @Test fun filteringKeepsRepliesFailuresAndMixedGroupsWithoutChangingTheTranscript() {
        val routine = TranscriptEvent.Routine("Example", "routine output")
        val label = TranscriptEvent.Label("Model changed")
        val failure = TranscriptEvent.Process("Failed", "details", failed = true)
        val group = TranscriptEvent.SystemMessages(listOf(routine, label, routine, failure), "first")
        val grouped = ChatMessage.Event("group", group)
        val reply = ChatMessage.Assistant("reply", "Actual assistant response")
        val failedTurn = ChatMessage.Event("failure", TranscriptEvent.FailedTurn("Failed turn"))
        val rows = listOf(ChatMessage.Event("routine", routine), grouped, reply, failedTurn,
            ChatMessage.User("user", "hello"), ChatMessage.Notice("notice", "Notice"),
            ChatMessage.Command("command", "/example", "output"))

        val hidden = rows.withVisibleRoutines(false)
        assertEquals(listOf("group", "reply", "failure", "user", "notice", "command"), hidden.map { it.key })
        assertSame(grouped, hidden.first())
        assertEquals(listOf(3 to label, 1 to failure), group.entries.visibleSystemEntries(false))
        assertEquals(listOf(4 to routine, 3 to label, 2 to routine, 1 to failure), group.entries.visibleSystemEntries(true))
        assertEquals(rows, rows.withVisibleRoutines(true))
        assertEquals(4, group.entries.size)
    }

    @Test fun routineOnlyGroupsDisappearAndLiveToggleRestoresHistoryAndNewRows() {
        val routine = TranscriptEvent.Routine("Example", "output")
        val group = ChatMessage.Event("group", TranscriptEvent.SystemMessages(listOf(routine, routine), "first"))
        val history = listOf(group, ChatMessage.Assistant("reply", "Response"))
        assertEquals(listOf("reply"), history.withVisibleRoutines(false).map { it.key })
        val live = history + ChatMessage.Event("live", routine)
        assertEquals(listOf("reply"), live.withVisibleRoutines(false).map { it.key })
        assertEquals(listOf("group", "reply", "live"), live.withVisibleRoutines(true).map { it.key })
        assertEquals(emptyList(), listOf(routine, routine).visibleSystemEntries(false))
    }

    @Test fun groupEntryKeysSurviveAnOlderPageAndTheSwitch() {
        val label = TranscriptEvent.Label("Changed")
        val routine = TranscriptEvent.Routine("Example", "output")
        val latest = listOf(label, routine)
        val olderPage = listOf(routine) + latest
        assertEquals(2 to label, latest.visibleSystemEntries(false).single())
        assertEquals(2 to label, olderPage.visibleSystemEntries(false).single())
        assertEquals(2 to label, olderPage.visibleSystemEntries(true)[1])
    }
}
