package com.amclub.wabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Runs as a foreground service where Android allows it, so aggressive OEM battery managers
 * (Xiaomi, Oppo, etc.) don't kill it.
 */
class WaListener : NotificationListenerService() {

    private class ReplyHandle(val action: Notification.Action, val key: String, val at: Long)

    companion object {
        @Volatile
        var instance: WaListener? = null
        private val replyHandles = ConcurrentHashMap<String, ReplyHandle>()
        private const val FG_ID = 77
        private const val FG_CHANNEL = "wab_status"

        fun hasReplyHandle(phone: String) = replyHandles.containsKey(phone)
    }

    private var executor: ScheduledExecutorService? = null
    @Volatile private var lastHeartbeat = 0L
    @Volatile private var lastPollStart = 0L

    // ---------- lifecycle ----------
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Bridge.prefs(this).edit().putLong("listener_connected_at", System.currentTimeMillis()).apply()
        Bridge.diag(this, "listener connected")
        goForeground()
        startExecutor()
        try {
            activeNotifications?.forEach { safeHandle(it) }
        } catch (e: Throwable) {
            Bridge.diag(this, "initial scan failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onListenerDisconnected() {
        Bridge.diag(this, "listener disconnected")
        instance = null
        executor?.shutdownNow()
        executor = null
        super.onListenerDisconnected()
        // ask Android to bind us again
        try {
            requestRebind(android.content.ComponentName(this, WaListener::class.java))
        } catch (e: Throwable) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started by the app's main screen (which is in the foreground, so this is allowed).
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        Bridge.diag(this, "service destroyed")
        instance = null
        executor?.shutdownNow()
        super.onDestroy()
    }

    private fun goForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(FG_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(FG_CHANNEL, "Bridge running", NotificationManager.IMPORTANCE_MIN))
            }
            val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, FG_CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Birbal is running")
                .setContentText("Watching candidate WhatsApp and your calendar")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(FG_ID, n)
            }
            Bridge.prefs(this).edit().putBoolean("fg_on", true).remove("fg_error").apply()
        } catch (e: Throwable) {
            Bridge.prefs(this).edit().putBoolean("fg_on", false)
                .putString("fg_error", e.javaClass.simpleName).apply()
            Log.w(Bridge.TAG, "foreground failed", e)
        }
    }

    @Synchronized
    private fun startExecutor() {
        executor?.shutdownNow()
        val ex = Executors.newSingleThreadScheduledExecutor()
        executor = ex
        ex.scheduleWithFixedDelay({ safePoll() }, 2, 60, TimeUnit.SECONDS)
    }

    /** Restart the poll loop if it has stalled (e.g. a hung network call or a killed thread). */
    private fun watchdog() {
        val stalled = System.currentTimeMillis() - lastPollStart > 3 * 60_000L
        if (executor == null || executor?.isShutdown == true || stalled) {
            Bridge.diag(this, "watchdog restarted sync loop")
            startExecutor()
        }
    }

    // ---------- notifications ----------
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        safeHandle(sbn)
        watchdog()
    }

    private fun safeHandle(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Throwable) {
            Bridge.diag(this, "handle error: ${e.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in Bridge.WA_PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) {
            Bridge.diag(this, "skip: group chat"); return
        }

        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)

        // WhatsApp's conversation shortcut id is the chat JID, e.g. 919913009659@s.whatsapp.net
        val shortcut = n.shortcutId ?: ""
        val jidType = if (shortcut.contains("@")) shortcut.substringAfter("@") else "none"
        if (shortcut.endsWith("@g.us") || shortcut.contains("broadcast") || shortcut.contains("newsletter")) {
            Bridge.diag(this, "skip: group/broadcast ($jidType)"); return
        }
        var phone = if (shortcut.endsWith("@s.whatsapp.net")) Bridge.digits(shortcut.substringBefore("@")) else ""
        val titleStr = title?.toString()?.trim() ?: ""
        val titleIsNumber = Bridge.looksLikeNumber(titleStr)
        // Unsaved contacts can also appear as "~ PushName" in newer WhatsApp versions.
        val titleIsPushName = titleStr.startsWith("~")
        if (phone.isEmpty() && titleIsNumber) phone = Bridge.digits(titleStr)
        if (phone.length < 8) {
            Bridge.diag(this, "skip: no phone (jid=$jidType, titleNumber=$titleIsNumber, pushName=$titleIsPushName)")
            return
        }

        // Rule: forward unsaved numbers, plus anyone on the candidate list even if saved.
        val known = Bridge.knownPhones(this).contains(phone)
        if (!titleIsNumber && !titleIsPushName && !known) {
            Bridge.diag(this, "skip: saved contact (jid=$jidType)"); return
        }

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
                out.add(msg(phone, titleStr, text, time, reply != null))
                if (time > newest) newest = time
            }
        } else {
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            if (text.isNotEmpty() && !Regex("^\\d+ new messages?$").matches(text)) {
                val time = if (n.`when` > 0) n.`when` else System.currentTimeMillis()
                if (time > lastTs) {
                    out.add(msg(phone, titleStr, text, time, reply != null))
                    newest = time
                }
            }
        }
        if (out.isEmpty()) {
            Bridge.diag(this, "seen +${phone.takeLast(4)}: no new messages"); return
        }
        p.edit().putLong("ts_$phone", newest).apply()
        Bridge.enqueue(this, out)
        Bridge.diag(this, "queued ${out.size} msg from +${phone.takeLast(4)}")
        // Upload now, then check back quickly for Birbal's decision (draft or booking).
        executor?.execute { safePoll() }
        executor?.schedule({ safePoll() }, 20, TimeUnit.SECONDS)
        executor?.schedule({ safePoll() }, 45, TimeUnit.SECONDS)
    }

    private fun msg(phone: String, title: String, text: String, time: Long, canReply: Boolean) =
        JSONObject()
            .put("phone", phone)
            .put("chat_title", title)
            .put("body", text)
            .put("msg_time", time)
            .put("can_reply", canReply)
            .put("dedup_key", "$phone|$time|${text.hashCode()}")

    /** Send text through a live WhatsApp notification's Reply action. Returns true on success. */
    fun sendReply(phone: String, text: String): Boolean {
        val h = replyHandles[phone] ?: return false
        val stillShowing = try {
            activeNotifications?.any { it.key == h.key } == true
        } catch (e: Throwable) { false }
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
            Bridge.diag(this, "sent reply to +${phone.takeLast(4)}")
            true
        } catch (e: Throwable) {
            Bridge.diag(this, "reply failed: ${e.javaClass.simpleName}")
            false
        }
    }

    // ---------- sync loop ----------
    private fun safePoll() {
        lastPollStart = System.currentTimeMillis()
        Bridge.prefs(this).edit().putLong("listener_alive", lastPollStart).apply()
        try {
            poll()
        } catch (e: Throwable) {
            Bridge.diag(this, "sync error: ${e.javaClass.simpleName}")
        }
    }

    private fun poll() {
        if (!Bridge.isConfigured(this)) return
        val p = Bridge.prefs(this)
        val now = System.currentTimeMillis()

        Bridge.flushQueue(this)

        if (now - p.getLong("contacts_at", 0L) > 30 * 60_000L) Bridge.refreshContacts(this)

        if (now - lastHeartbeat > 5 * 60_000L) {
            Bridge.heartbeat(this, true, "listener")
            lastHeartbeat = now
        }

        // Calendar: share busy times every 10 minutes, and create any calls Birbal booked.
        try {
            if (now - p.getLong("calendar_synced", 0L) > 10 * 60_000L) CalendarSync.sync(this)
            CalendarSync.applyPending(this)
        } catch (e: Throwable) {
            Bridge.diag(this, "calendar error: ${e.javaClass.simpleName}")
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
            val nid = 2000 + ((id.toLongOrNull() ?: 0L) % 100000L).toInt()
            when (o.optString("status")) {
                "draft" -> draftIds.add(id)
                "approved" -> {
                    if (sendReply(phone, body)) {
                        Bridge.call(this, JSONObject().put("action", "result").put("id", id)
                            .put("status", "sent").put("method", "notification_reply"))
                        Bridge.cancel(this, nid)
                    } else if (!notified.contains(id)) {
                        val name = o.optString("name").takeIf { it.isNotEmpty() && it != "null" } ?: "+$phone"
                        val tap = Intent(this, SendActivity::class.java)
                            .putExtra("id", id).putExtra("phone", phone).putExtra("body", body)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val pi = PendingIntent.getActivity(this, nid, tap,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        Bridge.notify(this, nid, "Tap to send to $name", body, pi)
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
                val c = draftIds.size
                Bridge.notify(this, 1000, "$c WhatsApp ${if (c == 1) "reply" else "replies"} to approve",
                    "Birbal drafted ${if (c == 1) "a reply" else "replies"} for candidates. Tap to review.", open)
            } else {
                Bridge.cancel(this, 1000)
            }
        }
    }
}
