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
import java.util.concurrent.TimeUnit

internal const val reminderDefault = "defaultReminderMinutes"
private const val alarmAction = "ua.pp.yesvitlo.OUTAGE_REMINDER"
internal fun SharedPreferences.reminderLead(place: Place) = place.reminderMinutes ?: getInt(reminderDefault, 0)

private fun SharedPreferences.hasRecentSchedule(region: String, now: Instant) =
    getString("widgetChecked:$region", null)?.let {
        runCatching { Instant.parse(it) >= now.minusSeconds(3600) }.getOrDefault(false)
    } ?: false

private fun starts(schedule: Schedule, queue: String, today: LocalDate): List<Instant> {
    if (schedule.stale || schedule.notice != null) return emptyList()
    return schedule.days.filter { it.date in today..today.plusDays(1) && it.date.toString() !in schedule.retained }
        .flatMap { day ->
            val intervals = day.groups[queue].orEmpty()
            intervals.mapIndexedNotNull { index, interval ->
                if (interval.power != Power.OFF) return@mapIndexedNotNull null
                val previous = if (interval.start > 0) intervals.getOrNull(index - 1)?.power
                    else schedule.days.find { it.date == day.date.minusDays(1) && it.date.toString() !in schedule.retained }
                        ?.groups?.get(queue)?.lastOrNull()?.power
                if (previous != Power.ON && !(interval.start == 0 && previous == null)) return@mapIndexedNotNull null
                day.date.atTime(LocalTime.ofSecondOfDay(interval.start * 60L)).atZone(kyiv).toInstant()
            }
        }.sorted()
}

private fun reminderIntent(context: Context, place: Place, start: Instant? = null) =
    Intent(context, OutageReminderReceiver::class.java).setAction(alarmAction)
        .setData(Uri.Builder().scheme("esvitlo").authority("reminder").appendPath(place.id).build())
        .putExtra("start", start?.toEpochMilli() ?: 0L)

@Synchronized
internal fun syncOutageReminders(context: Context, region: String? = null, latest: Schedule? = null) {
    val prefs = context.getSharedPreferences("esvitlo", Context.MODE_PRIVATE)
    val repository = ScheduleRepository(prefs)
    val alarm = context.getSystemService(AlarmManager::class.java)
    val now = Instant.now()
    val today = now.atZone(kyiv).toLocalDate()
    val enabled = prefs.getBoolean(notificationSetting, false) &&
        prefs.notificationMode(NotificationType.REMINDER) != NotificationMode.OFF
    for (place in prefs.places().filter { region == null || region == it.region }) {
        val old = PendingIntent.getBroadcast(context, 0, reminderIntent(context, place),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        val lead = prefs.reminderLead(place)
        val cached = if (region != null) latest else
            repository.cachedSchedule(place.region).takeIf { prefs.hasRecentSchedule(place.region, now) }
        val start = if (enabled && place.alerts && lead in 1..180 && cached != null && place.queue != null)
            starts(cached, place.queue, today).firstOrNull {
                it > now && it.minusSeconds(lead * 60L) > now &&
                    prefs.getLong("lastReminder:${place.id}", 0L) != it.toEpochMilli()
            } else null
        if (start == null) {
            old?.let(alarm::cancel)
            continue
        }
        val trigger = start.minusSeconds(lead * 60L).toEpochMilli()
        val pending = PendingIntent.getBroadcast(context, 0, reminderIntent(context, place, start),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms())
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }
}

class OutageReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == alarmAction) {
            val prefs = context.getSharedPreferences("esvitlo", Context.MODE_PRIVATE)
            val place = prefs.places().find { it.id == intent.data?.lastPathSegment } ?: return
            val schedule = ScheduleRepository(prefs).cachedSchedule(place.region)
            val start = Instant.ofEpochMilli(intent.getLongExtra("start", 0L))
            val now = Instant.now()
            val remaining = start.toEpochMilli() - now.toEpochMilli()
            val lead = prefs.reminderLead(place)
            val mode = prefs.notificationMode(NotificationType.REMINDER)
            if (prefs.getBoolean(notificationSetting, false) && place.alerts && lead > 0 &&
                remaining in 1..TimeUnit.MINUTES.toMillis(lead.toLong()) &&
                prefs.getLong("lastReminder:${place.id}", 0L) != start.toEpochMilli() &&
                prefs.hasRecentSchedule(place.region, now) &&
                schedule != null && place.queue != null && start in starts(schedule, place.queue, now.atZone(kyiv).toLocalDate()) &&
                (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = manager.channelFor(mode)
                if (channel != null && manager.areNotificationsEnabled()) {
                    val local = start.atZone(kyiv)
                    val body = "Черга ${place.queue} · за графіком світла не буде з ${clock(local.hour * 60 + local.minute)}"
                    val open = PendingIntent.getActivity(context, place.id.hashCode(),
                        Intent(context, MainActivity::class.java).putExtra("place", place.id)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    manager.notify("reminder:${place.id}", 0, Notification.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_svitlo_mark).setContentTitle(place.name)
                        .setContentText(body).setAutoCancel(true).setContentIntent(open).build())
                    prefs.edit().putLong("lastReminder:${place.id}", start.toEpochMilli()).apply()
                }
            }
        }
        syncOutageReminders(context)
        syncBackground(context, refresh = intent.action != alarmAction)
    }
}
