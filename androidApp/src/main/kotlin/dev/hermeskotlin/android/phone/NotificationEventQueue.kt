package dev.hermeskotlin.android.phone

import java.util.UUID

/** Process-local only. All access, including revocation, is serialized on this object. */
internal class NotificationEventQueue(private val capacity: Int = 100) {
    init { require(capacity > 0) }
    data class Event(val cursor: Long, val packageName: String, val postedAt: Long, val title: String?, val text: String?)
    data class Page(val generation: String, val events: List<Event>, val next: Long, val overflow: Boolean, val hasMore: Boolean)

    private val events = ArrayDeque<Event>()
    private var generation = UUID.randomUUID().toString()
    private var cursor = 0L
    private var droppedThrough = 0L
    private var enabled = false

    @Synchronized fun setEnabled(on: Boolean) {
        if (!on && enabled) {
            events.clear()
            generation = UUID.randomUUID().toString()
            cursor = 0
            droppedThrough = 0
        }
        enabled = on
    }

    @Synchronized fun post(packageName: String, ownPackage: String, postedAt: Long, title: String?, text: String?) {
        if (!enabled || packageName == ownPackage) return
        if (cursor == Long.MAX_VALUE) { setEnabled(false); setEnabled(true) }
        if (events.size == capacity) droppedThrough = events.removeFirst().cursor
        events.addLast(Event(++cursor, packageName.take(256), postedAt, title?.take(1024), text?.take(4096)))
    }

    @Synchronized fun page(after: Long, limit: Int): Page {
        require(enabled && after >= 0 && limit in 1..50)
        val matching = events.filter { it.cursor > after }
        val selected = matching.take(limit)
        return Page(generation, selected, selected.lastOrNull()?.cursor ?: after.coerceAtMost(cursor),
            after < droppedThrough || after > cursor, matching.size > selected.size)
    }
}
