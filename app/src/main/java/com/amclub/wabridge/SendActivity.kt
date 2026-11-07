package com.amclub.wabridge

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject

/** Opened from a "Tap to send" notification: opens WhatsApp with the approved text typed in. */
class SendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: ""
        val phone = intent.getStringExtra("phone") ?: ""
        val body = intent.getStringExtra("body") ?: ""
        if (phone.isNotEmpty() && body.isNotEmpty()) {
            val app = applicationContext
            // Prefer the live notification reply if it appeared since.
            val sentDirect = WaListener.instance?.sendReply(phone, body) == true
            if (!sentDirect) {
                try {
                    startActivity(Bridge.waIntent(this, phone, body))
                } catch (e: Exception) {
                    Toast.makeText(this, "WhatsApp not found", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
            }
            if (id.isNotEmpty()) {
                Thread {
                    Bridge.call(app, JSONObject().put("action", "result").put("id", id)
                        .put("status", if (sentDirect) "sent" else "handed_off")
                        .put("method", if (sentDirect) "notification_reply" else "deeplink"))
                }.start()
            }
        }
        finish()
    }
}
