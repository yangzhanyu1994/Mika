package com.wangzan101.mika.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.wangzan101.mika.core.calendar.CalendarEvent
import com.wangzan101.mika.core.calendar.CalendarReadPort
import com.wangzan101.mika.core.calendar.readCalendarDay
import java.time.ZoneId

/** Reads today's instances from CalendarContract. A withdrawn grant becomes permission_revoked. */
internal class AndroidCalendarToday(private val context: Context) : CalendarReadPort {
    override fun read(nowMs: Long, zone: ZoneId) = readCalendarDay(
        hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED,
        nowMs = nowMs,
        zone = zone,
    ) { start, end ->
        val cursor = CalendarContract.Instances.query(
            context.contentResolver,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_TIMEZONE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
            ),
            start,
            end,
        ) ?: return@readCalendarDay emptyList()
        cursor.use {
            val title = it.getColumnIndex(CalendarContract.Instances.TITLE)
            val begin = it.getColumnIndex(CalendarContract.Instances.BEGIN)
            val finish = it.getColumnIndex(CalendarContract.Instances.END)
            val eventZone = it.getColumnIndex(CalendarContract.Instances.EVENT_TIMEZONE)
            val allDay = it.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val location = it.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            buildList {
                var count = 0
                while (it.moveToNext() && count < 20) {
                    add(CalendarEvent(
                        title = if (title >= 0) it.getString(title).orEmpty() else "",
                        beginMs = if (begin >= 0) it.getLong(begin) else start,
                        endMs = if (finish >= 0) it.getLong(finish) else end,
                        allDay = allDay >= 0 && it.getInt(allDay) == 1,
                        eventZone = if (eventZone >= 0) it.getString(eventZone) else null,
                        location = if (location >= 0) it.getString(location)?.take(120) else null,
                    ))
                    count++
                }
            }
        }
    }
}
