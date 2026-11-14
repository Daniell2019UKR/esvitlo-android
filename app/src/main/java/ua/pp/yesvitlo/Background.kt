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
private val widgetTypes = listOf(StatusWidget::class.java, NextWidget::class.java, ScheduleWidget::class.java,
    NarrowWidget::class.java, WideWidget::class.java)
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
class NarrowWidget : PowerWidget()
class WideWidget : PowerWidget()

private fun liveWidget(context: Context, today: List<Interval>, tomorrow: List<Interval>,
                       now: ZonedDateTime, note: String, warning: Boolean, narrow: Boolean): RemoteViews {
    val minute = now.hour * 60 + now.minute
    val current = today.firstOrNull { minute in it.start until it.end }
    val upcomingToday = today.firstOrNull { it.start > minute }
    val upcoming = upcomingToday ?: tomorrow.firstOrNull()
    val colors = context.resources
    return RemoteViews(context.packageName, if (narrow) R.layout.widget_live else R.layout.widget_live_rows).apply {
        if (narrow) {
            val inset = (5 * colors.displayMetrics.density).toInt()
            setViewPadding(android.R.id.background, inset, inset, inset, inset)
        }
        setTextViewText(R.id.widget_note, note)
        setViewVisibility(R.id.widget_warning, if (warning) android.view.View.VISIBLE else android.view.View.GONE)
        fun bind(timeId: Int, stateId: Int, iconId: Int, interval: Interval?, nextDay: Boolean = false) {
            setTextViewText(timeId, interval?.let {
                "${clock(it.start)}–${if (narrow) "\n" else ""}${clock(it.end)}"
            } ?: "—")
            val state = when (interval?.power) {
                Power.ON -> "Світло є"
                Power.OFF -> "Світла немає"
                Power.UNKNOWN -> "Немає даних"
                null -> "Немає графіка"
            }
            setTextViewText(stateId, if (narrow) when (interval?.power) {
                Power.ON -> "Є світло"
                Power.OFF -> "Вимкн."
                else -> "Н/д"
            } else if (nextDay) "Завтра · $state" else state)
            if (narrow) {
                setInt(timeId, "setMaxLines", 2)
                setTextViewTextSize(timeId, android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            } else {
                setImageViewResource(iconId, when (interval?.power) {
                    Power.ON -> R.drawable.ic_widget_power
                    Power.OFF -> R.drawable.ic_widget_power_off
                    else -> R.drawable.ic_widget_warning
                })
            }
            setTextColor(timeId, colors.getColor(
                if (interval?.power == Power.OFF) R.color.widget_off else R.color.widget_text, context.theme))
        }
        bind(R.id.live_current_time, R.id.live_current_state, R.id.live_current_icon, current)
        bind(R.id.live_next_time, R.id.live_next_state, R.id.live_next_icon, upcoming,
            nextDay = upcomingToday == null && upcoming != null)
        setOnClickPendingIntent(android.R.id.background, openApp(context))
    }
}

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
        val narrow = type == NarrowWidget::class.java
        if ((type == NextWidget::class.java || narrow) && ids.isNotEmpty()) {
            val stamp = schedule?.updated?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val age = stamp?.let { java.time.Duration.between(it, now.toInstant()).toMinutes() }
            val updated = when {
                stale -> "Збережений графік"
                stamp == null || age == null || age < 0 -> null
                age < 1 -> "Оновлено щойно"
                age < 60 -> "Оновлено $age хв тому"
                age < 1440 -> "Оновлено ${age / 60} год тому"
                else -> "Оновлено ${stamp.atZone(kyiv).toLocalDate().format(notificationDate)}"
            }
            val note = if (narrow) group.orEmpty() else listOfNotNull(group?.let { "Черга $it" }, updated).joinToString(" · ")
            manager.updateAppWidget(ids, liveWidget(context, intervals, tomorrow, now, note, schedule?.notice != null, narrow))
            return@forEachIndexed
        }
        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                .takeIf { it > 0 } ?: if (index == 0) 150 else 300
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val limit = if (index == 0) 2 else 7
            val metrics = context.resources.displayMetrics
            val paint = android.graphics.Paint()
            val roomy = width >= 240 && height >= 112
            val detailed = type == WideWidget::class.java || roomy
            val maxSize = if (roomy) 23 else 17
            val reserved = if (roomy) 50 else 47
            val textSize = ((if (detailed) 14 else maxSize) downTo 10).firstOrNull { size ->
                paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat(), metrics)
                paint.measureText(if (detailed) "00:00–24:00  Немає" else "00:00–24:00…") <= (width - if (detailed) 54 else 40) * metrics.density / 2 &&
                    (height <= 0 || paint.fontSpacing <= (height - reserved) * metrics.density)
            } ?: 10
            paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, textSize.toFloat(), metrics)
            val rowHeight = paint.fontSpacing / metrics.density + if (detailed) 9 else 0
            val rows = if (height > 0) ((height - reserved) / rowHeight).toInt().coerceIn(1, limit) else limit
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_columns).apply {
                fun bindDay(textId: Int, blocksId: Int, day: List<Interval>) {
                    val sp = android.util.TypedValue.COMPLEX_UNIT_SP
                    if (!detailed || day.isEmpty()) {
                        setTextViewText(textId, outages(day, rows))
                        setInt(textId, "setMaxLines", rows)
                        setTextViewTextSize(textId, sp, textSize.toFloat())
                        return
                    }
                    setViewVisibility(textId, android.view.View.GONE)
                    setViewVisibility(blocksId, android.view.View.VISIBLE)
                    removeAllViews(blocksId)
                    val shown = if (day.size > rows && rows > 1) rows - 1 else rows
                    fun block(label: String, outage: Boolean = false) {
                        addView(blocksId, RemoteViews(context.packageName, R.layout.widget_interval_block).apply {
                            setTextViewText(R.id.widget_block_text, label)
                            setTextViewTextSize(R.id.widget_block_text, sp, textSize.toFloat())
                            if (outage) setTextColor(R.id.widget_block_text, context.getColor(R.color.widget_off))
                        })
                    }
                    for ((index, interval) in day.take(shown).withIndex()) {
                        val state = when (interval.power) {
                            Power.ON -> "Є"
                            Power.OFF -> "Немає"
                            Power.UNKNOWN -> "Н/д"
                        }
                        val more = if (rows == 1 && index == 0 && day.size > 1) "…" else ""
                        block(clock(interval.start) + "–" + clock(interval.end) + "  " + state + more, interval.power == Power.OFF)
                    }
                    if (rows > shown) block("Ще " + (day.size - shown))
                }
                bindDay(R.id.widget_today, R.id.widget_today_blocks, intervals)
                bindDay(R.id.widget_tomorrow, R.id.widget_tomorrow_blocks, tomorrow)
                val labelSize = if (roomy) 14f else 11f
                setTextViewTextSize(R.id.widget_today_label, android.util.TypedValue.COMPLEX_UNIT_SP, labelSize)
                setTextViewTextSize(R.id.widget_tomorrow_label, android.util.TypedValue.COMPLEX_UNIT_SP, labelSize)
                val regionName = if (roomy) repository.cachedRegions().find { it.code == region }?.name else null
                setTextViewText(R.id.widget_note, listOfNotNull(regionName, group, if (stale) "Кеш" else null).joinToString(" · "))
                setViewVisibility(R.id.widget_warning, if (schedule?.notice != null) android.view.View.VISIBLE else android.view.View.GONE)
                setOnClickPendingIntent(android.R.id.background, openApp(context))
            })
        }
    }
}
