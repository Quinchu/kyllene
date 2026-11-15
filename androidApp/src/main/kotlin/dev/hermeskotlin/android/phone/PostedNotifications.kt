package dev.hermeskotlin.android.phone

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.enabledPhoneTools
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.android.ext.android.get

/** Owns no persistent notification data. Android may start the process solely for the listener. */
class PostedNotifications(private val context: Context, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val queue = NotificationEventQueue()
    private var connected = false
    private var revocations = 0L

    fun start() {
        scope.launch { settings.settings.collect { synchronized(this@PostedNotifications) { refresh() } } }
    }

    private fun access() = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    private fun refresh(): Boolean {
        val current = settings.settings.value
        if (current != null && current.notificationRevocations != revocations) {
            queue.setEnabled(false)
            revocations = current.notificationRevocations
        }
        val on = connected && access() && PhoneTool.Notifications in current?.enabledPhoneTools().orEmpty()
        queue.setEnabled(on)
        return on
    }

    @Synchronized fun connected(on: Boolean) {
        connected = on
        refresh()
    }

    @Synchronized fun post(sbn: StatusBarNotification) {
        if (!refresh() || sbn.packageName == context.packageName) return
        val extras = sbn.notification.extras
        queue.post(sbn.packageName, context.packageName, sbn.postTime,
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString())
    }

    @Synchronized fun read(args: JsonObject): JsonObject {
        val value = args["after"]
        val after = if (value == null || value == JsonNull) 0L else {
            if (value !is JsonPrimitive || value.isString) throw PhoneToolException("after must be a nonnegative whole number.")
            value.content.toLongOrNull()?.takeIf { it >= 0 } ?: throw PhoneToolException("after must be a nonnegative whole number.")
        }
        val limit = args.int("limit") ?: 20
        if (limit !in 1..50) throw PhoneToolException("limit must be from 1 to 50.")
        if (!refresh()) throw PhoneToolException("Notifications are off, notification access is missing, or the listener is not connected.")
        val generationValue = args["generation"]
        val generation = if (generationValue == null || generationValue == JsonNull) null else {
            if (generationValue !is JsonPrimitive || !generationValue.isString) throw PhoneToolException("generation must be a string.")
            generationValue.content.takeIf { it.length in 1..64 }
                ?: throw PhoneToolException("generation must be 1 to 64 characters.")
        }
        return notificationPageJson(queue, after, limit, generation)

    }
}

/** Budget both the JSON result and its escaped form when embedded in an answer. */
internal fun notificationPageJson(queue: NotificationEventQueue, after: Long, limit: Int, generation: String?): JsonObject {
    var page = queue.page(after, limit)
    if (generation != null && generation != page.generation) page = queue.page(0, limit).copy(overflow = true)
    fun render(count: Int): JsonObject = buildJsonObject {
        val selected = page.events.take(count)
        put("generation", page.generation)
        put("next", selected.lastOrNull()?.cursor ?: page.next.let { if (page.events.isEmpty()) it else 0L })
        put("overflow", page.overflow)
        put("has_more", page.hasMore || count < page.events.size)
        put("events", buildJsonArray {
            selected.forEach { event -> add(buildJsonObject {
                put("cursor", event.cursor)
                put("package", event.packageName)
                put("posted_at_ms", event.postedAt)
                event.title?.let { put("title", it) }
                event.text?.let { put("text", it) }
            }) }
        })
    }
    for (count in page.events.size downTo 0) {
        val result = render(count)
        val encoded = result.toString()
        val embedded = JsonPrimitive(encoded).toString()
        if (encoded.length <= 59_000 && encoded.toByteArray(Charsets.UTF_8).size <= 59_000 &&
            embedded.length <= 59_000 && embedded.toByteArray(Charsets.UTF_8).size <= 59_000) {
            check(count > 0 || page.events.isEmpty()) { "Notification event exceeds result budget" }
            return result
        }
    }
    error("Notification page exceeds result budget")
}

/** No notification actions, dismissal, active-notification snapshot, or persistence. */
class PostedNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); get<PostedNotifications>().connected(true) }
    override fun onListenerDisconnected() { get<PostedNotifications>().connected(false); super.onListenerDisconnected() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { get<PostedNotifications>().post(sbn) }
}
