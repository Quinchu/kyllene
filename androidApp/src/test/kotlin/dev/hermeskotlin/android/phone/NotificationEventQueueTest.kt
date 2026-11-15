package dev.hermeskotlin.android.phone

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotificationEventQueueTest {
    @Test fun boundedSerializedPagesAndGenerationMismatch() {
        val q = NotificationEventQueue()
        q.setEnabled(true)
        repeat(30) { q.post("包\"", "self", it.toLong(), "\u0001😀".repeat(300), "\u0000\\😀".repeat(900)) }
        val generation = q.page(0, 1).generation
        var next = 0L
        val seen = mutableListOf<Long>()
        do {
            val page = notificationPageJson(q, next, 50, generation)
            val raw = page.toString()
            val wrapped = JsonPrimitive(raw).toString()
            assertTrue(raw.length <= 59000 && raw.toByteArray(Charsets.UTF_8).size <= 59000)
            assertTrue(wrapped.length <= 59000 && wrapped.toByteArray(Charsets.UTF_8).size <= 59000)
            seen += page.getValue("events").jsonArray.map { it.jsonObject.getValue("cursor").jsonPrimitive.long }
            next = page.getValue("next").jsonPrimitive.long
            val more = page.getValue("has_more").jsonPrimitive.boolean
        } while (more)
        assertEquals((1L..30L).toList(), seen)
        q.setEnabled(false); q.setEnabled(true)
        repeat(4) { q.post("new", "self", 1, null, null) }
        val restarted = notificationPageJson(q, 2, 50, generation)
        assertTrue(restarted.getValue("overflow").jsonPrimitive.boolean)
        assertEquals(1L, restarted.getValue("events").jsonArray.first().jsonObject.getValue("cursor").jsonPrimitive.long)
    }

    @Test fun optInSelfFilterPaginationAndOverflow() {
        val q = NotificationEventQueue(2)
        q.post("other", "self", 1, "ignored", null)
        q.setEnabled(true)
        q.post("self", "self", 2, "secret", null)
        assertTrue(q.page(0, 20).events.isEmpty())
        q.post("one", "self", 3, "first", "body")
        q.post("two", "self", 4, "second", null)
        q.post("three", "self", 5, null, null)
        val first = q.page(0, 1)
        assertTrue(first.overflow)
        assertTrue(first.hasMore)
        assertEquals(2L, first.next)
        assertEquals("two", first.events.single().packageName)
        val second = q.page(first.next, 1)
        assertFalse(second.overflow)
        assertFalse(second.hasMore)
        assertEquals(3L, second.next)
    }

    @Test fun revokeClearsAndChangesGeneration() {
        val q = NotificationEventQueue()
        q.setEnabled(true)
        q.post("other", "self", 1, "private", "content")
        val before = q.page(0, 20)
        q.setEnabled(false)
        q.post("other", "self", 2, "ignored", null)
        q.setEnabled(true)
        val after = q.page(0, 20)
        assertNotEquals(before.generation, after.generation)
        assertTrue(after.events.isEmpty())
        assertTrue(q.page(1, 20).overflow)
    }

    @Test fun concurrentPostsStayBoundedAndUnique() {
        val q = NotificationEventQueue(100)
        q.setEnabled(true)
        val workers = (1..4).map { worker -> Thread {
            repeat(100) { q.post("app$worker", "self", it.toLong(), null, null) }
        }.apply { start() } }
        workers.forEach { it.join() }
        val first = q.page(0, 50)
        val second = q.page(first.next, 50)
        val cursors = (first.events + second.events).map { it.cursor }
        assertTrue(first.overflow)
        assertEquals(100, cursors.size)
        assertEquals(100, cursors.toSet().size)
        assertEquals(400L, cursors.last())
    }

    @Test fun boundsContentAndRejectsInvalidPage() {
        val q = NotificationEventQueue()
        q.setEnabled(true)
        q.post("x".repeat(300), "self", 1, "a".repeat(2000), "b".repeat(5000))
        val event = q.page(0, 50).events.single()
        assertEquals(256, event.packageName.length)
        assertEquals(1024, event.title!!.length)
        assertEquals(4096, event.text!!.length)
        assertThrows(IllegalArgumentException::class.java) { q.page(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { q.page(0, 51) }
    }
}
