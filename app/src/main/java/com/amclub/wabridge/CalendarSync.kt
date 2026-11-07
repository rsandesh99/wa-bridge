package com.amclub.wabridge

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uses the phone's own calendar (which Android syncs with Google Calendar):
 * - shares busy times for the next 14 days so Birbal never proposes a clashing slot
 * - creates the calls Birbal books (with 60 and 10 minute reminders)
 */
object CalendarSync {
    private const val OWNER = "rsandesh99@gmail.com"

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Upload busy times for the next 14 days. */
    fun sync(ctx: Context): Boolean {
        if (!hasPermission(ctx)) return false
        val now = System.currentTimeMillis()
        val end = now + 14L * 24 * 3600 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 3600_000L)
            ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.VISIBLE,
        )
        val events = JSONArray()
        ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && events.length() < 400) {
                val availability = c.getInt(5)
                val selfStatus = c.getInt(6)
                val visible = c.getInt(7)
                if (visible == 0) continue
                if (availability == CalendarContract.Events.AVAILABILITY_FREE) continue
                if (selfStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val begin = c.getLong(1)
                events.put(JSONObject()
                    .put("key", "${c.getLong(0)}-$begin")
                    .put("start", begin)
                    .put("end", c.getLong(2))
                    .put("title", c.getString(3) ?: "")
                    .put("all_day", c.getInt(4) == 1))
            }
        }
        val res = Bridge.call(ctx, JSONObject().put("action", "calendar_sync")
            .put("device", android.os.Build.MODEL ?: "phone")
            .put("events", events))
        val ok = res?.optBoolean("ok") == true
        if (ok) Bridge.prefs(ctx).edit().putLong("calendar_synced", System.currentTimeMillis()).apply()
        Bridge.diag(ctx, if (ok) "calendar synced (${events.length()} entries)" else "calendar sync failed")
        return ok
    }

    /** The calendar Birbal writes to: the primary calendar of the owner's Google account. */
    private fun targetCalendarId(ctx: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        var best: Long? = null
        var bestScore = -1
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(4)
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                var score = 0
                if (c.getString(1).equals(OWNER, ignoreCase = true)) score += 4
                if (c.getInt(3) == 1) score += 2
                if (c.getString(2) == "com.google") score += 1
                if (score > bestScore) { bestScore = score; best = c.getLong(0) }
            }
        }
        return best
    }

    /** Create the calendar entries Birbal has booked. */
    fun applyPending(ctx: Context) {
        if (!hasPermission(ctx)) return
        val res = Bridge.call(ctx, JSONObject().put("action", "calendar_pending")) ?: return
        val items = res.optJSONArray("items") ?: return
        if (items.length() == 0) return
        val calId = targetCalendarId(ctx)
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val id = o.optString("id")
            try {
                if (calId == null) throw IllegalStateException("no writable calendar")
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.TITLE, o.optString("title"))
                    put(CalendarContract.Events.DESCRIPTION, o.optString("description"))
                    put(CalendarContract.Events.DTSTART, o.optLong("start_ms"))
                    put(CalendarContract.Events.DTEND, o.optLong("end_ms"))
                    put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Kolkata")
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }
                val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?: throw IllegalStateException("insert failed")
                val eventId = ContentUris.parseId(uri)
                for (mins in listOf(60, 10)) {
                    ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, eventId)
                        put(CalendarContract.Reminders.MINUTES, mins)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    })
                }
                Bridge.call(ctx, JSONObject().put("action", "calendar_result").put("id", id).put("status", "done"))
                Bridge.diag(ctx, "booked: ${o.optString("title")}")
            } catch (e: Throwable) {
                Bridge.call(ctx, JSONObject().put("action", "calendar_result").put("id", id)
                    .put("status", "failed").put("error", e.javaClass.simpleName + ": " + (e.message ?: "")))
                Bridge.diag(ctx, "booking failed: ${e.javaClass.simpleName}")
            }
        }
        Bridge.prefs(ctx).edit().putLong("calendar_synced", 0L).apply() // force a fresh sync
    }
}
