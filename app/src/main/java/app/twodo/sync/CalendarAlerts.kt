package app.twodo.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import app.twodo.TwoDoApp
import app.twodo.model.CalendarEntry
import app.twodo.model.SpaceKind
import app.twodo.model.TodoList
import app.twodo.model.calendarEntries
import app.twodo.model.nextDaily
import app.twodo.model.nextReminder
import app.twodo.model.on
import app.twodo.model.remindersDue
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The daily schedule and reminders before timed entries. Each is one alarm, set for the next time
 * it's due and set again whenever the calendars or settings change, the phone restarts, or the clock
 * changes. Alarms are exact where Android allows it (see [set]).
 */
object CalendarAlerts {
    private const val ACTION_DAILY = "app.twodo.DAILY"
    private const val ACTION_REMIND = "app.twodo.REMIND"
    private val twelveHour = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /** Sets (or clears) both alarms for the next time they're due. */
    fun reschedule(context: Context) {
        val app = context.applicationContext as TwoDoApp
        val identity = app.identity
        val now = LocalDateTime.now()
        val daily = if (identity.notifyDaily) nextDaily(LocalTime.parse(identity.dailyTime), now) else null
        set(context, ACTION_DAILY, daily)
        val reminder = identity.reminderMinutes.takeIf { it > 0 }?.let { calendarEntries(app.repo.lists.value.values).nextReminder(it, now) }
        set(context, ACTION_REMIND, reminder)
    }

    private fun set(context: Context, action: String, at: LocalDateTime?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, AlertReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) return alarms.cancel(intent)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Exact, so a reminder comes when it says (USE_EXACT_ALARM: this is a calendar). Without it,
        // Android may hold an alarm back by up to an hour; then at least ask for a 10-minute window.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, millis, 10 * 60_000L, intent)
        }
    }

    fun onAlarm(context: Context, action: String?) {
        when (action) {
            ACTION_DAILY -> showDaily(context)
            ACTION_REMIND -> showReminders(context)
        }
        reschedule(context)
    }

    private fun showDaily(context: Context) {
        val app = context.applicationContext as TwoDoApp
        if (!app.identity.notifyDaily) return
        val lists = app.repo.lists.value
        val today = calendarEntries(lists.values).on(java.time.LocalDate.now())
        if (today.isEmpty() && app.identity.dailyOnlyIfSomething) return
        val is24Hour = DateFormat.is24HourFormat(context)
        val mixed = today.map { it.calendar.id }.distinct().size > 1
        Notifications.showDaily(
            context,
            today.map { e -> (e.item.time?.let { time(it, is24Hour) } ?: "All day") to (e.item.text + if (mixed) " · ${calendarName(e.calendar, lists)}" else "") },
            openId = today.firstOrNull()?.calendar?.id,
        )
    }

    private fun showReminders(context: Context) {
        val app = context.applicationContext as TwoDoApp
        val minutes = app.identity.reminderMinutes.takeIf { it > 0 } ?: return
        val lists = app.repo.lists.value
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        // Anything due since the last reminder, but not ones from long ago (e.g. the phone was off all day).
        val since = maxOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(app.identity.remindedUpTo), zone), now.minusHours(1))
        val due = calendarEntries(lists.values).remindersDue(minutes, after = since, upTo = now.plusMinutes(1))
        val is24Hour = DateFormat.is24HourFormat(context)
        due.forEach { e -> Notifications.showReminder(context, e.item.id, e.item.text, reminderText(e, now, is24Hour, lists), e.calendar.id) }
        app.identity.remindedUpTo = now.plusMinutes(1).atZone(zone).toInstant().toEpochMilli()
    }

    /** "3:30 PM · in 30 minutes · Family". */
    private fun reminderText(e: CalendarEntry, now: LocalDateTime, is24Hour: Boolean, lists: Map<String, TodoList>): String {
        val start = e.start ?: return ""
        val minutes = java.time.Duration.between(now, start).toMinutes().coerceAtLeast(0)
        val until = when {
            minutes < 1 -> "now"
            minutes < 60 -> "in $minutes minutes"
            minutes % 60 == 0L -> "in ${minutes / 60} hour${if (minutes >= 120) "s" else ""}"
            else -> "in ${minutes / 60} h ${minutes % 60} min"
        }
        return "${time(e.item.time!!, is24Hour)} · $until · ${calendarName(e.calendar, lists)}"
    }

    /** A group's calendar goes by the group's name; other diaries by their own. */
    private fun calendarName(calendar: TodoList, lists: Map<String, TodoList>): String =
        calendar.groupId?.let { lists[it] }?.takeIf { it.kind == SpaceKind.GROUP }?.name ?: calendar.name

    private fun time(hhmm: String, is24Hour: Boolean): String =
        if (is24Hour) hhmm else runCatching { LocalTime.parse(hhmm).format(twelveHour) }.getOrDefault(hhmm)
}

/** The daily schedule and reminder alarms going off. Private to the app. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CalendarAlerts.onAlarm(context, intent.action)
    }
}

/** The phone restarting, the app updating, or the clock changing: alarms are cleared or now wrong, so set them again. */
class AlertResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CalendarAlerts.reschedule(context)
    }
}
