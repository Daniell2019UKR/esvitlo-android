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
private fun dayBar(day: List<Interval>): android.graphics.Bitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(192, 16, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val brush = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    brush.color = 0xff777d78.toInt()
    canvas.drawRoundRect(0f, 0f, 192f, 16f, 7f, 7f, brush)
    for (interval in day) {
        if (interval.power == Power.ON) continue
        brush.color = if (interval.power == Power.OFF) 0xffffa65c.toInt() else 0xffa7a5ad.toInt()
        val start = interval.start * 192f / 1440
        val end = interval.end * 192f / 1440
        canvas.drawRoundRect(start, 0f, end, 16f, 4f, 4f, brush)
    }
    return bitmap
}
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
    val stale = download?.offline == true || schedule?.stale != false || schedule?.retained.orEmpty()
        .any { it == today.toString() || it == today.plusDays(1).toString() } ||
        runCatching { Instant.parse(prefs.getString("widgetChecked:$region", null)).isBefore(Instant.now().minusSeconds(1800)) }.getOrDefault(true)
    val manager = AppWidgetManager.getInstance(context)
    fun outages(day: List<Interval>, rows: Int): String {
        if (day.isEmpty()) return "Немає даних"
        val off = day.filter { it.power == Power.OFF }
        val unknown = day.any { it.power == Power.UNKNOWN }
        if (off.isEmpty()) return if (unknown) "Неповні дані" else "Без відкл."
        val lines = off.map { "${clock(it.start)}–${clock(it.end)}" } +
            if (unknown) listOf("Неповні дані") else emptyList()
        return lines.take(rows).mapIndexed { index, line ->
            if (index == rows - 1 && lines.size > rows) "$line…" else line
        }.joinToString("\n")
    }
    widgetTypes.forEachIndexed { index, type ->
        val ids = manager.getAppWidgetIds(ComponentName(context, type))
        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                .takeIf { it > 0 } ?: if (index == 0) 150 else 300
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val limit = listOf(2, 4, 7)[index]
            val metrics = context.resources.displayMetrics
            val paint = android.graphics.Paint().apply {
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            }
            val roomy = width >= 240 && height >= 112
            val maxSize = if (roomy) 23 else 17
            val reserved = if (roomy) 77 else 47
            val textSize = (maxSize downTo 10).firstOrNull { size ->
                paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat(), metrics)
                paint.measureText("00:00–24:00…") <= (width - 28) * metrics.density / 2 &&
                    (height <= 0 || paint.fontSpacing <= (height - reserved) * metrics.density)
            } ?: 10
            paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, textSize.toFloat(), metrics)
            val rows = if (height > 0) (((height - reserved) * metrics.density) / paint.fontSpacing).toInt().coerceIn(1, limit) else limit
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_columns).apply {
                setTextViewText(R.id.widget_today, outages(intervals, rows))
                setTextViewText(R.id.widget_tomorrow, outages(tomorrow, rows))
                setInt(R.id.widget_today, "setMaxLines", rows)
                setInt(R.id.widget_tomorrow, "setMaxLines", rows)
                setTextViewTextSize(R.id.widget_today, android.util.TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
                setTextViewTextSize(R.id.widget_tomorrow, android.util.TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
                val labelSize = if (roomy) 14f else 11f
                setTextViewTextSize(R.id.widget_today_label, android.util.TypedValue.COMPLEX_UNIT_SP, labelSize)
                setTextViewTextSize(R.id.widget_tomorrow_label, android.util.TypedValue.COMPLEX_UNIT_SP, labelSize)
                if (roomy) {
                    setImageViewBitmap(R.id.widget_today_bar, dayBar(intervals))
                    setImageViewBitmap(R.id.widget_tomorrow_bar, dayBar(tomorrow))
                    setViewVisibility(R.id.widget_today_bar, android.view.View.VISIBLE)
                    setViewVisibility(R.id.widget_tomorrow_bar, android.view.View.VISIBLE)
                }
                val regionName = if (roomy) repository.cachedRegions().find { it.code == region }?.name else null
                setTextViewText(R.id.widget_note, listOfNotNull(regionName, group, if (stale) "Кеш" else null).joinToString(" · "))
                setViewVisibility(R.id.widget_warning, if (schedule?.notice != null) android.view.View.VISIBLE else android.view.View.GONE)
                setOnClickPendingIntent(R.id.widget_root, openApp(context))
            })
        }
    }
}
