package ua.pp.yesvitlo

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private const val alarmAction = "ua.pp.yesvitlo.OUTAGE_REMINDER"
internal val reminderTypes = listOf(NotificationType.REMINDER, NotificationType.RESTORATION)

internal fun parseReminderMinutes(value: String): List<Int>? {
    if (value.trim() == "0") return emptyList()
    val fields = value.split(',')
    if (fields.size > 10) return null
    return fields.map {
        val field = it.trim()
        if (field.length !in 1..3 || !field.all { digit -> digit in '0'..'9' }) return null
        field.toInt().takeIf { minutes -> minutes in 1..180 } ?: return null
    }.distinct().sortedDescending()
}

internal fun List<Int>.reminderSettingText() = if (isEmpty()) "0" else joinToString(", ")

internal fun SharedPreferences.defaultReminderMinutes(type: NotificationType): List<Int> =
    getString("reminders:${type.key}", null)?.let(::parseReminderMinutes) ?: when (type) {
        NotificationType.REMINDER -> listOf(getInt("defaultReminderMinutes", 0)).filter { it in 1..180 }
        else -> emptyList()
    }

private fun SharedPreferences.reminderMinutes(place: Place, type: NotificationType): List<Int> =
    if (notificationMode(type) == NotificationMode.OFF) emptyList()
    else (if (type == NotificationType.REMINDER) place.outageMinutes else place.restorationMinutes)
        ?: defaultReminderMinutes(type)

internal fun SharedPreferences.hasReminders(place: Place) =
    place.alerts && place.queue != null && reminderTypes.any { reminderMinutes(place, it).isNotEmpty() }

private fun SharedPreferences.hasRecentSchedule(region: String, now: Instant) =
    getString("widgetChecked:$region", null)?.let {
        runCatching { Instant.parse(it) >= now.minusSeconds(3600) }.getOrDefault(false)
    } ?: false

private data class Reminder(val type: NotificationType, val event: Instant, val lead: Int) {
    val trigger: Instant get() = event.minusSeconds(lead * 60L)
    fun sentKey(place: Place) = "lastReminder:${place.id}:${place.region}:${place.queue}:${type.key}:$lead"
}

private fun SharedPreferences.pendingReminders(schedule: Schedule, place: Place, today: LocalDate): List<Reminder> {
    if (schedule.stale || schedule.notice != null) return emptyList()
    val queue = place.queue ?: return emptyList()
    val leads = reminderTypes.associateWith { reminderMinutes(place, it) }
    return schedule.days.filter { it.date in today..today.plusDays(1) && it.date.toString() !in schedule.retained }
        .flatMap { day ->
            val intervals = day.groups[queue].orEmpty()
            intervals.flatMapIndexed { index, interval ->
                val previous = if (interval.start > 0) intervals.getOrNull(index - 1)?.power
                    else schedule.days.find { it.date == day.date.minusDays(1) && it.date.toString() !in schedule.retained }
                        ?.groups?.get(queue)?.lastOrNull()?.power
                val type = when {
                    interval.power == Power.OFF && (previous == Power.ON || interval.start == 0 && previous == null) ->
                        NotificationType.REMINDER
                    interval.power == Power.ON && previous == Power.OFF -> NotificationType.RESTORATION
                    else -> return@flatMapIndexed emptyList()
                }
                val event = day.date.atTime(LocalTime.ofSecondOfDay(interval.start * 60L)).atZone(kyiv).toInstant()
                leads.getValue(type).map { Reminder(type, event, it) }
            }
        }.filter { getLong(it.sentKey(place), 0L) != it.event.toEpochMilli() &&
            (it.type != NotificationType.REMINDER || getLong("lastReminder:${place.id}", 0L) != it.event.toEpochMilli()) }
}

private fun reminderIntent(context: Context, place: Place, trigger: Instant? = null) =
    Intent(context, OutageReminderReceiver::class.java).setAction(alarmAction)
        .setData(Uri.Builder().scheme("esvitlo").authority("reminder").appendPath(place.id).build())
        .putExtra("trigger", trigger?.toEpochMilli() ?: 0L)

@Synchronized
internal fun syncOutageReminders(context: Context, region: String? = null, latest: Schedule? = null) {
    val prefs = context.getSharedPreferences("esvitlo", Context.MODE_PRIVATE)
    val repository = ScheduleRepository(prefs)
    val alarm = context.getSystemService(AlarmManager::class.java)
    val now = Instant.now()
    val today = now.atZone(kyiv).toLocalDate()
    val enabled = prefs.getBoolean(notificationSetting, false)
    for (place in prefs.places().filter { region == null || region == it.region }) {
        val old = PendingIntent.getBroadcast(context, 0, reminderIntent(context, place),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        val cached = if (region != null) latest else
            repository.cachedSchedule(place.region).takeIf { prefs.hasRecentSchedule(place.region, now) }
        val trigger = if (enabled && cached != null && prefs.hasReminders(place))
            prefs.pendingReminders(cached, place, today).map { it.trigger }.filter { it > now }.minOrNull() else null
        if (trigger == null) {
            old?.let(alarm::cancel)
            continue
        }
        val pending = PendingIntent.getBroadcast(context, 0, reminderIntent(context, place, trigger),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms())
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
        }
    }
}

class OutageReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == alarmAction) {
            val prefs = context.getSharedPreferences("esvitlo", Context.MODE_PRIVATE)
            val place = prefs.places().find { it.id == intent.data?.lastPathSegment } ?: return
            val schedule = ScheduleRepository(prefs).cachedSchedule(place.region)
            val trigger = Instant.ofEpochMilli(intent.getLongExtra("trigger", 0L))
            val now = Instant.now()
            if (trigger != Instant.EPOCH && prefs.getBoolean(notificationSetting, false) && prefs.hasReminders(place) &&
                prefs.hasRecentSchedule(place.region, now) && schedule != null &&
                (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                val manager = context.getSystemService(NotificationManager::class.java)
                val due = prefs.pendingReminders(schedule, place, now.atZone(kyiv).toLocalDate())
                    .filter { it.trigger >= trigger && it.trigger <= now && it.event > now }
                    .groupBy { it.type to it.event }.values.map { events -> events.minBy { it.lead } }
                if (manager.areNotificationsEnabled()) for (reminder in due) {
                    val channel = manager.channelFor(prefs.notificationMode(reminder.type)) ?: continue
                    val local = reminder.event.atZone(kyiv)
                    val event = if (reminder.type == NotificationType.REMINDER) "світла не буде" else "світло відновлять"
                    val body = "Черга ${place.queue} · за графіком $event з ${clock(local.hour * 60 + local.minute)}"
                    val open = PendingIntent.getActivity(context, place.id.hashCode(),
                        Intent(context, MainActivity::class.java).putExtra("place", place.id)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    manager.notify("reminder:${place.id}:${reminder.type.key}", 0, Notification.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_svitlo_mark).setContentTitle(place.name)
                        .setContentText(body).setAutoCancel(true).setContentIntent(open).build())
                    prefs.edit().putLong(reminder.sentKey(place), reminder.event.toEpochMilli()).apply()
                }
            }
        }
        syncOutageReminders(context)
        syncBackground(context, refresh = intent.action != alarmAction)
    }
}
