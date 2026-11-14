package ua.pp.yesvitlo

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.work.*
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

private fun Context.settings() = getSharedPreferences("esvitlo", Context.MODE_PRIVATE)
const val notificationSetting = "notifications"
private const val channel = "schedule_updates"
private const val workName = "schedule_sync"

fun syncBackground(context: Context, refresh: Boolean = false) {
    val manager = WorkManager.getInstance(context)
    if ((!context.settings().getBoolean(notificationSetting, false) || pushConfigured(context)) && !hasWidgets(context)) {
        manager.cancelUniqueWork(workName)
        manager.cancelUniqueWork("$workName-now")
        return
    }
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    manager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<ScheduleWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    if (refresh) manager.enqueueUniqueWork("$workName-now", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<ScheduleWorker>().setConstraints(constraints).build())
}

class ScheduleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val prefs = context.settings()
        val repository = ScheduleRepository(prefs)
        val region = prefs.getString("region", "o") ?: "o"
        val download = repository.schedule(region)
        applyDownload(context, region, download)
        return Result.success()
    }
}

private fun openApp(context: Context) = PendingIntent.getActivity(context, 0,
    Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

@Synchronized
fun applyDownload(context: Context, region: String, download: Download) {
    val prefs = context.settings()
    if (region != prefs.getString("region", "o")) return
    val schedule = download.schedule
    prefs.edit().putString("widgetChecked:$region",
        if (!download.offline && !schedule.stale) Instant.now().toString() else null).apply()
    updateWidgets(context, download)
    if (download.offline || pushConfigured(context) || !prefs.getBoolean(notificationSetting, false)) return
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    val group = prefs.getString("queue:$region", null) ?: schedule.groups.firstOrNull()
    val now = ZonedDateTime.now(kyiv).toLocalDate()
    val changes = mutableListOf<String>()
    val edit = prefs.edit()
    if (group != null && !schedule.stale) {
        for (day in schedule.days.filter { it.date in now..now.plusDays(1) && it.date.toString() !in schedule.retained }) {
            val intervals = day.groups[group] ?: continue
            val key = "seen:$region:$group:${day.date.toEpochDay() % 2}"
            val signature = "${day.date}:${intervals.joinToString()}"
            val previous = prefs.getString(key, null)
            if (previous != signature && (previous?.startsWith("${day.date}:") == true ||
                        day.date > now && prefs.getString("baseline:$region:$group", null) == now.toString())) {
                changes += if (previous?.startsWith("${day.date}:") == true)
                    "Оновлено графік на ${day.date.format(notificationDate)}" else "Додано графік на ${day.date.format(notificationDate)}"
            }
            edit.putString(key, signature)
        }
        edit.putString("baseline:$region:$group", now.toString())
    }
    if (schedule.noticeKnown && !schedule.noticeStale) {
        val normalized = schedule.notice.orEmpty().replace(Regex("\\s+"), " ").trim()
        val old = prefs.getString("notice:$region", null)
        if (old != normalized && (normalized.isNotEmpty() || old?.isNotEmpty() == true)) {
            changes += normalized.ifEmpty { "Оператор зняв попередження про відключення." }
        }
        edit.putString("notice:$region", normalized)
    }
    edit.apply()
    if (changes.isEmpty()) return
    manager.createNotificationChannel(NotificationChannel(channel, "Графіки та попередження", NotificationManager.IMPORTANCE_DEFAULT))
    val regionName = ScheduleRepository(prefs).cachedRegions().find { it.code == region }?.name ?: "єСвітло"
    val text = changes.joinToString("\n")
    manager.notify(region.hashCode(), Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_svitlo_mark).setContentTitle(regionName)
        .setContentText(changes.first()).setStyle(Notification.BigTextStyle().bigText(text))
        .setAutoCancel(true).setContentIntent(openApp(context)).build())
}

private val notificationDate = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
private fun hasWidgets(context: Context) =
    AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NextWidget::class.java)).isNotEmpty()

class NextWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateWidgets(context)
        syncBackground(context, refresh = true)
    }

    override fun onDisabled(context: Context) = syncBackground(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        updateWidgets(context)
    }
}

fun updateWidgets(context: Context, download: Download? = null) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, NextWidget::class.java))
    if (ids.isEmpty()) return
    val prefs = context.settings()
    val repository = ScheduleRepository(prefs)
    val region = prefs.getString("region", "o") ?: "o"
    val schedule = download?.schedule ?: repository.cachedSchedule(region)
    val group = prefs.getString("queue:$region", null) ?: schedule?.groups?.firstOrNull()
    val now = ZonedDateTime.now(kyiv)
    val today = now.toLocalDate()
    val tomorrow = today.plusDays(1)
    fun intervals(date: java.time.LocalDate) =
        schedule?.days?.find { it.date == date }?.groups?.get(group).orEmpty()

    val currentDay = intervals(today)
    val nextDay = intervals(tomorrow)
    val stale = download?.offline == true || schedule?.stale != false ||
        schedule?.retained.orEmpty().any { it == today.toString() || it == tomorrow.toString() } ||
        runCatching { Instant.parse(prefs.getString("widgetChecked:$region", null)).isBefore(now.toInstant().minusSeconds(1800)) }.getOrDefault(true)
    val updated = if (stale && (currentDay.isNotEmpty() || nextDay.isNotEmpty())) "Збережений графік" else "Графік відключень"
    val regionName = repository.cachedRegions().find { it.code == region }?.name ?: "єСвітло"
    val views = RemoteViews(context.packageName, R.layout.widget_reference)
    views.setTextViewText(R.id.reference_title, listOfNotNull(regionName, group).joinToString(" · "))
    views.setTextViewText(R.id.reference_updated, updated)
    views.setViewVisibility(R.id.reference_warning, if (schedule?.notice != null) android.view.View.VISIBLE else android.view.View.GONE)

    fun bind(row: Int, icon: Int, status: Int, range: Int, day: List<Interval>, label: String, highlighted: Boolean) {
        val outages = day.filter { it.power == Power.OFF }
        val unknown = day.isEmpty() || day.any { it.power == Power.UNKNOWN }
        val background = if (highlighted) R.drawable.widget_state_on else R.drawable.widget_state_neutral
        val color = context.getColor(if (highlighted) R.color.widget_on_text else R.color.widget_text)
        views.setInt(row, "setBackgroundResource", background)
        views.setTextColor(status, color)
        views.setTextColor(range, color)
        views.setInt(icon, "setColorFilter", color)
        views.setImageViewResource(icon, when {
            outages.isNotEmpty() -> R.drawable.ic_widget_power_off
            unknown -> R.drawable.ic_widget_warning
            else -> R.drawable.ic_widget_power
        })
        val summary = when {
            day.isEmpty() -> "Немає графіка"
            outages.isEmpty() && unknown -> "Неповні дані"
            outages.isEmpty() -> "Без відключень"
            else -> {
                val first = outages.first()
                val extra = outages.size - 1
                clock(first.start) + "–" + clock(first.end) + if (extra > 0) " +$extra" else ""
            }
        }
        views.setTextViewText(status, label)
        views.setTextViewText(range, summary)
        views.setContentDescription(row, if (unknown && outages.isNotEmpty()) "$label, $summary, неповні дані" else "$label, $summary")
    }

    bind(R.id.current_row, R.id.current_icon, R.id.current_status, R.id.current_range, currentDay, "Сьогодні", false)
    bind(R.id.next_row, R.id.next_icon, R.id.next_status, R.id.next_range, nextDay, "Завтра", true)
    views.setOnClickPendingIntent(android.R.id.background, openApp(context))
    manager.updateAppWidget(ids, views)
}
