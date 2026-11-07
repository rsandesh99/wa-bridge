package com.amclub.wabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Shared helpers: settings, the cloud API, local notifications and WhatsApp deep links. */
object Bridge {
    const val TAG = "WaBridge"
    const val ENDPOINT = "https://vskdxxpaeclzjuynbisr.supabase.co/functions/v1/wa-bridge"
    const val CHANNEL = "wab_approvals"
    val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("wab", Context.MODE_PRIVATE)

    fun token(ctx: Context): String = prefs(ctx).getString("token", "") ?: ""

    fun isConfigured(ctx: Context) = token(ctx).length >= 20

    /** POST a JSON body to the bridge. Returns the parsed response, or null on any failure. Never call on the main thread. */
    fun call(ctx: Context, body: JSONObject): JSONObject? {
        val tok = token(ctx)
        if (tok.isEmpty()) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-device-token", tok)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            prefs(ctx).edit().putLong("last_sync", System.currentTimeMillis())
                .putInt("last_code", code).apply()
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code: $text")
                null
            } else JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "call failed", e)
            prefs(ctx).edit().putInt("last_code", -1).apply()
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---- on-device diagnostics (no message text; names of saved contacts are never stored) ----
    @Synchronized
    fun diag(ctx: Context, entry: String) {
        val p = prefs(ctx)
        val arr = JSONArray(p.getString("diag", "[]"))
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        arr.put("$stamp $entry")
        val out = JSONArray()
        val start = maxOf(0, arr.length() - 15)
        for (i in start until arr.length()) out.put(arr.get(i))
        p.edit().putString("diag", out.toString()).apply()
    }

    fun diagList(ctx: Context): List<String> {
        val arr = try { JSONArray(prefs(ctx).getString("diag", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.reversed()
    }

    fun heartbeat(ctx: Context, listenerConnected: Boolean, source: String) {
        call(ctx, JSONObject().put("action", "heartbeat")
            .put("device", android.os.Build.MODEL ?: "phone")
            .put("listener_connected", listenerConnected)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("diag", ("[$source] " + diagList(ctx).take(12).joinToString(" | ")).take(1800)))
    }

    // ---- outgoing queue of inbound messages that failed to upload ----
    @Synchronized
    fun enqueue(ctx: Context, msgs: List<JSONObject>) {
        if (msgs.isEmpty()) return
        val p = prefs(ctx)
        val arr = JSONArray(p.getString("queue", "[]"))
        msgs.forEach { arr.put(it) }
        // keep the queue bounded
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - 300)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString("queue", trimmed.toString()).apply()
    }

    /** Upload anything queued. Called from background threads only. */
    @Synchronized
    fun flushQueue(ctx: Context) {
        val p = prefs(ctx)
        val arr = JSONArray(p.getString("queue", "[]"))
        if (arr.length() == 0) return
        var i = 0
        while (i < arr.length()) {
            val batch = JSONArray()
            var j = i
            while (j < arr.length() && j < i + 40) { batch.put(arr.get(j)); j++ }
            val res = call(ctx, JSONObject().put("action", "inbound").put("messages", batch))
            if (res == null || !res.optBoolean("ok")) {
                // keep the rest for the next try
                val rest = JSONArray()
                for (k in i until arr.length()) rest.put(arr.get(k))
                p.edit().putString("queue", rest.toString()).apply()
                return
            }
            i = j
        }
        p.edit().putString("queue", "[]").apply()
    }

    // ---- contacts synced from the cloud (candidates) ----
    fun knownPhones(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("known_phones", emptySet()) ?: emptySet()

    fun nameFor(ctx: Context, phone: String): String? {
        val raw = prefs(ctx).getString("contact_names", "{}") ?: "{}"
        return try { JSONObject(raw).optString(phone).ifEmpty { null } } catch (e: Exception) { null }
    }

    fun refreshContacts(ctx: Context) {
        val res = call(ctx, JSONObject().put("action", "contacts")) ?: return
        val items = res.optJSONArray("items") ?: return
        val phones = HashSet<String>()
        val names = JSONObject()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val ph = o.optString("phone")
            if (ph.isNotEmpty()) {
                phones.add(ph)
                val role = o.optString("role")
                names.put(ph, o.optString("name") + if (role.isNotEmpty() && role != "null") " · $role" else "")
            }
        }
        prefs(ctx).edit().putStringSet("known_phones", phones)
            .putString("contact_names", names.toString())
            .putLong("contacts_at", System.currentTimeMillis()).apply()
    }

    fun digits(s: CharSequence?): String = s?.toString()?.filter { it.isDigit() } ?: ""

    /** Unsaved WhatsApp chats show the number as the title, e.g. "+91 99130 09659". */
    fun looksLikeNumber(title: CharSequence?): Boolean {
        val t = title?.toString()?.trim() ?: return false
        return Regex("^\\+?[0-9][0-9 ()\\-]{6,}$").matches(t)
    }

    // ---- WhatsApp deep link ----
    fun waIntent(ctx: Context, phone: String, text: String): Intent {
        val enc = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=$enc"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = ctx.packageManager
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                pm.getPackageInfo(pkg, 0)
                intent.setPackage(pkg)
                break
            } catch (e: Exception) {
                // not installed
            }
        }
        return intent
    }

    // ---- local notifications ----
    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Replies to approve", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    fun notify(ctx: Context, id: Int, title: String, text: String, intent: PendingIntent) {
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing", e)
        }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }
}
