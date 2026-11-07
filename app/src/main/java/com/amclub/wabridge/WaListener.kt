package com.amclub.wabridge

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Reads WhatsApp notifications, forwards messages from unsaved numbers (and known candidates)
 * to the cloud, and sends approved replies through the notification's Reply action.
 */
class WaListener : NotificationListenerService() {

    private class ReplyHandle(val action: Notification.Action, val key: String, val at: Long)

    companion object {
        @Volatile
        var instance: WaListener? = null
        private val replyHandles = ConcurrentHashMap<String, ReplyHandle>()

        fun hasReplyHandle(phone: String) = replyHandles.containsKey(phone)
    }

    private var executor: ScheduledExecutorService? = null
    private var lastHeartbeat = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        val ex = Executors.newSingleThreadScheduledExecutor()
        executor = ex
        ex.scheduleWithFixedDelay({ safePoll() }, 3, 60, TimeUnit.SECONDS)
        // pick up anything already showing
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (e: Exception) {
            Log.w(Bridge.TAG, "initial scan failed", e)
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        executor?.shutdownNow()
        executor = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        executor?.shutdownNow()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(Bridge.TAG, "handle failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in Bridge.WA_PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return

        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)

        // WhatsApp's conversation shortcut id is the chat JID, e.g. 919913009659@s.whatsapp.net
        val shortcut = n.shortcutId ?: ""
        if (shortcut.endsWith("@g.us") || shortcut.contains("broadcast") || shortcut.contains("newsletter")) return
        var phone = if (shortcut.endsWith("@s.whatsapp.net")) Bridge.digits(shortcut.substringBefore("@")) else ""
        val titleIsNumber = Bridge.looksLikeNumber(title)
        if (phone.isEmpty() && titleIsNumber) phone = Bridge.digits(title)
        if (phone.length < 8) return

        // Rule: forward unsaved numbers, plus anyone on the candidate list even if saved.
        val known = Bridge.knownPhones(this).contains(phone)
        if (!titleIsNumber && !known) return

        // Remember the Reply action so approved replies can be sent from here.
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        if (reply != null) replyHandles[phone] = ReplyHandle(reply, sbn.key, System.currentTimeMillis())

        // Collect messages (MessagingStyle bundles), skipping our own.
        val p = Bridge.prefs(this)
        val lastTs = p.getLong("ts_$phone", 0L)
        var newest = lastTs
        val out = ArrayList<JSONObject>()
        val arr: Array<Parcelable>? = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr != null && arr.isNotEmpty()) {
            for (item in arr) {
                val b = item as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString()?.trim() ?: continue
                val time = b.getLong("time", 0L)
                val fromOther = b.getCharSequence("sender") != null ||
                    (Build.VERSION.SDK_INT >= 28 && b.get("sender_person") != null)
                if (!fromOther || text.isEmpty()) continue
                if (time <= lastTs) continue
                out.add(msg(phone, title, text, time, reply != null))
                if (time > newest) newest = time
            }
        } else {
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            if (text.isNotEmpty() && !Regex("^\\d+ new messages?$").matches(text)) {
                val time = if (n.`when` > 0) n.`when` else System.currentTimeMillis()
                if (time > lastTs) {
                    out.add(msg(phone, title, text, time, reply != null))
                    newest = time
                }
            }
        }
        if (out.isEmpty()) return
        p.edit().putLong("ts_$phone", newest).apply()
        Bridge.enqueue(this, out)
        executor?.execute { safePoll() }
    }

    private fun msg(phone: String, title: CharSequence?, text: String, time: Long, canReply: Boolean) =
        JSONObject()
            .put("phone", phone)
            .put("chat_title", title?.toString() ?: "")
            .put("body", text)
            .put("msg_time", time)
            .put("can_reply", canReply)
            .put("dedup_key", "$phone|$time|${text.hashCode()}")

    /** Send text through a live WhatsApp notification's Reply action. Returns true on success. */
    fun sendReply(phone: String, text: String): Boolean {
        val h = replyHandles[phone] ?: return false
        val stillShowing = try {
            activeNotifications?.any { it.key == h.key } == true
        } catch (e: Exception) { false }
        if (!stillShowing) {
            replyHandles.remove(phone)
            return false
        }
        val inputs = h.action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            h.action.actionIntent.send(this, 0, intent)
            replyHandles.remove(phone)
            true
        } catch (e: Exception) {
            Log.w(Bridge.TAG, "reply failed", e)
            false
        }
    }

    private fun safePoll() {
        try {
            poll()
        } catch (e: Exception) {
            Log.w(Bridge.TAG, "poll failed", e)
        }
    }

    private fun poll() {
        if (!Bridge.isConfigured(this)) return
        val p = Bridge.prefs(this)
        val now = System.currentTimeMillis()

        Bridge.flushQueue(this)

        if (now - p.getLong("contacts_at", 0L) > 30 * 60_000L) Bridge.refreshContacts(this)

        if (now - lastHeartbeat > 5 * 60_000L) {
            Bridge.call(this, JSONObject().put("action", "heartbeat")
                .put("device", Build.MODEL ?: "phone")
                .put("listener_connected", true)
                .put("app_version", BuildConfig.VERSION_NAME))
            lastHeartbeat = now
        }

        val res = Bridge.call(this, JSONObject().put("action", "pending")) ?: return
        val items = res.optJSONArray("items") ?: return
        val draftIds = ArrayList<String>()
        val notified = HashSet(p.getStringSet("tap_notified", emptySet()) ?: emptySet())
        var changed = false
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val id = o.optString("id")
            val phone = o.optString("phone")
            val body = o.optString("body")
            when (o.optString("status")) {
                "draft" -> draftIds.add(id)
                "approved" -> {
                    if (sendReply(phone, body)) {
                        Bridge.call(this, JSONObject().put("action", "result").put("id", id)
                            .put("status", "sent").put("method", "notification_reply"))
                        Bridge.cancel(this, 2000 + (id.toLongOrNull() ?: 0L).toInt() % 100000)
                    } else if (!notified.contains(id)) {
                        val name = o.optString("name").takeIf { it.isNotEmpty() && it != "null" } ?: "+$phone"
                        val tap = Intent(this, SendActivity::class.java)
                            .putExtra("id", id).putExtra("phone", phone).putExtra("body", body)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val pi = PendingIntent.getActivity(this, (id.toLongOrNull() ?: 0L).toInt(), tap,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        Bridge.notify(this, 2000 + (id.toLongOrNull() ?: 0L).toInt() % 100000,
                            "Tap to send to $name", body, pi)
                        notified.add(id)
                        changed = true
                    }
                }
            }
        }
        if (changed) p.edit().putStringSet("tap_notified", notified).apply()

        // One summary notification when the set of drafts changes.
        val sig = draftIds.sorted().joinToString(",")
        if (sig != p.getString("draft_sig", "")) {
            p.edit().putString("draft_sig", sig).apply()
            if (draftIds.isNotEmpty()) {
                val open = PendingIntent.getActivity(this, 1,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = draftIds.size
                Bridge.notify(this, 1000, "$n WhatsApp ${if (n == 1) "reply" else "replies"} to approve",
                    "Claude drafted ${if (n == 1) "a reply" else "replies"} for candidates. Tap to review.", open)
            } else {
                Bridge.cancel(this, 1000)
            }
        }
    }
}
