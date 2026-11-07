package com.amclub.wabridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "WA Bridge"
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statusBox)
        root.addView(header("Replies to approve"))
        root.addView(listBox)
        root.addView(header("Send a new message"))
        root.addView(composeBox())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        refresh()
    }

    // ---------- status & setup ----------
    private fun listenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, WaListener::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    private fun batteryOk(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun renderStatus() {
        statusBox.removeAllViews()
        val p = Bridge.prefs(this)

        if (!Bridge.isConfigured(this)) {
            statusBox.addView(header("Setup"))
            statusBox.addView(label("Paste the setup code Claude gave you:"))
            val input = EditText(this).apply {
                hint = "wab_…"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                isSingleLine = true
            }
            statusBox.addView(input)
            statusBox.addView(button("Save setup code") {
                val code = input.text.toString().trim()
                if (code.length < 20) {
                    toast("That code looks too short")
                } else {
                    p.edit().putString("token", code).apply()
                    Thread {
                        val ok = Bridge.call(this, JSONObject().put("action", "contacts")) != null
                        if (ok) Bridge.refreshContacts(this)
                        runOnUiThread {
                            if (ok) toast("Connected") else {
                                p.edit().remove("token").apply()
                                toast("Code rejected or no internet. Check and try again.")
                            }
                            renderStatus(); refresh()
                        }
                    }.start()
                }
            })
        }

        statusBox.addView(header("Status"))
        val listener = listenerEnabled()
        statusBox.addView(check(listener, "Notification access",
            if (listener) "WhatsApp messages are being read" else "Required. On Android 13+, first open App info → ⋮ → Allow restricted settings."))
        if (!listener) {
            statusBox.addView(button("Open notification access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            statusBox.addView(button("Open App info (to allow restricted settings)") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        val battery = batteryOk()
        statusBox.addView(check(battery, "Battery optimisation off",
            if (battery) "The app can keep syncing in the background" else "Recommended, otherwise Android may pause syncing"))
        if (!battery) {
            statusBox.addView(button("Turn off battery optimisation") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            })
        }
        val last = p.getLong("last_sync", 0L)
        val code = p.getInt("last_code", 0)
        val queued = try { JSONArray(p.getString("queue", "[]")).length() } catch (e: Exception) { 0 }
        val syncText = if (last == 0L) "Not synced yet" else
            "Last sync " + SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(last)) +
                (if (code in 200..299) "" else " (error $code)") +
                (if (queued > 0) " · $queued message(s) waiting to upload" else "")
        statusBox.addView(label(syncText).apply { setTextColor(Color.GRAY) })
    }

    // ---------- pending replies ----------
    private fun refresh() {
        if (!Bridge.isConfigured(this)) {
            listBox.removeAllViews()
            listBox.addView(label("Finish setup first."))
            return
        }
        Thread {
            Bridge.flushQueue(this)
            val res = Bridge.call(this, JSONObject().put("action", "pending"))
            runOnUiThread { renderList(res); renderStatus() }
        }.start()
    }

    private fun renderList(res: JSONObject?) {
        listBox.removeAllViews()
        if (res == null) {
            listBox.addView(label("Couldn't reach the server. Pull to retry with the button below."))
            listBox.addView(button("Retry") { refresh() })
            return
        }
        val items = res.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) {
            listBox.addView(label("Nothing to approve right now."))
        }
        for (i in 0 until items.length()) listBox.addView(card(items.getJSONObject(i)))
        listBox.addView(button("Refresh") { refresh() })
    }

    private fun card(o: JSONObject): View {
        val id = o.optString("id")
        val phone = o.optString("phone")
        val body = o.optString("body")
        val name = o.optString("name").takeIf { it.isNotEmpty() && it != "null" } ?: "+$phone"
        val role = o.optString("role").takeIf { it.isNotEmpty() && it != "null" }
        val reason = o.optString("reason").takeIf { it.isNotEmpty() && it != "null" }
        val status = o.optString("status")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#F3F4F6"))
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, dp(6))
        box.layoutParams = lp

        box.addView(TextView(this).apply {
            text = name + (role?.let { " · $it" } ?: "")
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
        })
        box.addView(label("+$phone" + if (status == "approved") " · approved, waiting to send" else "").apply { setTextColor(Color.GRAY) })
        if (reason != null) box.addView(label(reason).apply { setTextColor(Color.DKGRAY); textSize = 13f })
        box.addView(TextView(this).apply {
            text = body
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            setTextIsSelectable(true)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(smallButton("Reject") { decide(id, "reject", null) { refresh() } })
        row.addView(smallButton("Edit") { editDialog(id, phone, body) })
        row.addView(smallButton(if (status == "approved") "Send" else "Approve & send") { approveAndSend(id, phone, body, null) })
        box.addView(row)
        return box
    }

    private fun editDialog(id: String, phone: String, body: String) {
        val input = EditText(this).apply {
            setText(body)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Edit reply")
            .setView(input)
            .setPositiveButton("Approve & send") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.isNotEmpty()) approveAndSend(id, phone, txt, txt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun decide(id: String, decision: String, newBody: String?, then: (Boolean) -> Unit) {
        Thread {
            val body = JSONObject().put("action", "decide").put("id", id).put("decision", decision)
            if (newBody != null) body.put("body", newBody)
            val r = Bridge.call(this, body)
            val ok = r?.optBoolean("ok") == true
            runOnUiThread {
                if (!ok) toast("Couldn't update. Check internet.")
                then(ok)
            }
        }.start()
    }

    private fun approveAndSend(id: String, phone: String, text: String, edited: String?) {
        decide(id, "approve", edited) { ok ->
            if (ok) sendNow(id, phone, text)
        }
    }

    /** Send via the live notification if possible, otherwise open WhatsApp with the text typed. */
    private fun sendNow(id: String, phone: String, text: String) {
        val direct = WaListener.instance?.sendReply(phone, text) == true
        if (direct) {
            toast("Sent")
        } else {
            try {
                startActivity(Bridge.waIntent(this, phone, text))
            } catch (e: Exception) {
                toast("WhatsApp not found")
                return
            }
        }
        Thread {
            Bridge.call(this, JSONObject().put("action", "result").put("id", id)
                .put("status", if (direct) "sent" else "handed_off")
                .put("method", if (direct) "notification_reply" else "deeplink"))
            runOnUiThread { refresh() }
        }.start()
    }

    // ---------- compose ----------
    private fun composeBox(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val phone = EditText(this).apply {
            hint = "Phone with country code, e.g. 919876543210"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val text = EditText(this).apply {
            hint = "Message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
        }
        box.addView(phone)
        box.addView(text)
        box.addView(button("Send on WhatsApp") {
            val ph = Bridge.digits(phone.text)
            val t = text.text.toString().trim()
            if (ph.length < 10 || t.isEmpty()) {
                toast("Enter a phone number and a message")
            } else {
                Thread {
                    val r = Bridge.call(this, JSONObject().put("action", "compose").put("phone", ph).put("body", t))
                    val id = r?.optString("id") ?: ""
                    runOnUiThread {
                        if (id.isEmpty()) toast("Couldn't save. Check internet.") else {
                            phone.setText(""); text.setText("")
                            sendNow(id, ph, t)
                        }
                    }
                }.start()
            }
        })
        return box
    }

    // ---------- tiny UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun header(s: String) = TextView(this).apply {
        text = s
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun check(ok: Boolean, title: String, detail: String) = TextView(this).apply {
        text = (if (ok) "✓ " else "✗ ") + title + "\n" + detail
        textSize = 14f
        setTextColor(if (ok) Color.parseColor("#166534") else Color.parseColor("#B91C1C"))
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun smallButton(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }
}
