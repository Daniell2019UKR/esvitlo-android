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
private val widgetTypes = listOf(StatusWidget::class.java, NextWidget::class.java, ScheduleWidget::class.java)
private fun hasWidgets(context: Context) = widgetTypes.any {
    AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
}

abstract class PowerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateWidgets(context)
        syncBackground(context, refresh = true)
    }
    override fun onDisabled(context: Context) { syncBackground(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, options: android.os.Bundle) {
        updateWidgets(context)
    }
}

class StatusWidget : PowerWidget()
class NextWidget : PowerWidget()
class ScheduleWidget : PowerWidget()

fun updateWidgets(context: Context, download: Download? = null) {
    if (!hasWidgets(context)) return
    val prefs = context.settings()
    val repository = ScheduleRepository(prefs)
    val region = prefs.getString("region", "o") ?: "o"
    val schedule = download?.schedule ?: repository.cachedSchedule(region)
    val group = prefs.getString("queue:$region", null) ?: schedule?.groups?.firstOrNull()
    val now = ZonedDateTime.now(kyiv)
    val today = now.toLocalDate()
    val intervals = group?.let { schedule?.days?.find { day -> day.date == today }?.groups?.get(it) }.orEmpty()
    val tomorrow = group?.let { schedule?.days?.find { day -> day.date == today.plusDays(1) }?.groups?.get(it) }.orEmpty()
    val minute = now.hour * 60 + now.minute
    val current = intervals.find { minute in it.start until it.end }
    val laterToday = intervals.firstOrNull { it.start > minute }
    val next = laterToday ?: tomorrow.firstOrNull { it.start != 0 || current?.power != it.power }
    val nextDay = next != null && laterToday == null
    val status = when (current?.power) {
        Power.ON -> "Світло є"
        Power.OFF -> "Відключення"
        else -> "Немає даних"
    }
    val stale = download?.offline == true || schedule?.stale != false || today.toString() in schedule?.retained.orEmpty() ||
        runCatching { Instant.parse(prefs.getString("widgetChecked:$region", null)).isBefore(Instant.now().minusSeconds(1800)) }.getOrDefault(true)
    val manager = AppWidgetManager.getInstance(context)
    val regionName = repository.cachedRegions().find { it.code == region }?.name ?: "єСвітло"
    fun outages(day: List<Interval>): String {
        if (day.isEmpty()) return "немає графіка"
        val off = day.filter { it.power == Power.OFF }
        val unknown = day.any { it.power == Power.UNKNOWN }
        val result = off.joinToString(" · ") { "${clock(it.start)}–${clock(it.end)}" }
        return when {
            result.isEmpty() && unknown -> "неповні дані"
            result.isEmpty() -> "без відключень"
            else -> result + if (unknown) " · неповні дані" else ""
        }
    }
    widgetTypes.forEachIndexed { index, type ->
        val ids = manager.getAppWidgetIds(ComponentName(context, type))
        if (ids.isEmpty()) return@forEachIndexed
        val title = if (index == 0) "єСвітло" else regionName
        val details = when (index) {
            0 -> status
            1 -> "$status\n" + if (next == null) "Змін у графіку не видно" else {
                val change = when (next.power) {
                    Power.ON -> "Світло за графіком"
                    Power.OFF -> "Відключення"
                    Power.UNKNOWN -> "Немає даних"
                }
                "$change ${if (nextDay) "завтра " else ""}о ${clock(next.start)}"
            }
            else -> "Сьогодні: ${outages(intervals)}\nЗавтра: ${outages(tomorrow)}"
        }
        manager.updateAppWidget(ids, RemoteViews(context.packageName, R.layout.widget_power).apply {
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_body, details)
            setInt(R.id.widget_body, "setMaxLines", if (index == 0) 1 else if (index == 1) 3 else 8)
            setTextViewText(R.id.widget_note, listOfNotNull(group?.let { "Черга $it" },
                if (stale) "Збережені дані" else null, if (schedule?.notice != null && index != 0) "Є попередження" else null).joinToString(" · "))
            setOnClickPendingIntent(R.id.widget_root, openApp(context))
        })
    }
}
