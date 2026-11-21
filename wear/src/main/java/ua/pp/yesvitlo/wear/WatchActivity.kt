package ua.pp.yesvitlo.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ua.pp.yesvitlo.Download
import ua.pp.yesvitlo.Power
import ua.pp.yesvitlo.ScheduleRepository
import ua.pp.yesvitlo.clock
import ua.pp.yesvitlo.kyiv
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("watch", MODE_PRIVATE)
        val repository = ScheduleRepository(prefs)
        setContent {
            MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFF83EDA8), onPrimary = Color(0xFF00391E))) {
                AppScaffold {
                    WatchSchedule(
                        repository, prefs.getString("region", null), prefs.getString("queue", null)
                    ) { region, queue -> prefs.edit().putString("region", region).putString("queue", queue).apply() }
                }
            }
        }
    }
}

private enum class Page { REGION, QUEUE, SCHEDULE }
private val dateFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy")
private val Power.label: String get() = when (this) {
    Power.ON -> "Світло є"
    Power.OFF -> "Світла немає"
    Power.UNKNOWN -> "Немає даних"
}

@Composable
private fun WatchSchedule(
    repository: ScheduleRepository,
    savedRegion: String?,
    savedQueue: String?,
    save: (String, String) -> Unit
) {
    var region by rememberSaveable { mutableStateOf(savedRegion) }
    var queue by rememberSaveable { mutableStateOf(savedQueue) }
    var pendingRegion by rememberSaveable { mutableStateOf(savedRegion) }
    var page by rememberSaveable { mutableStateOf(if (savedRegion == null || savedQueue == null) Page.REGION else Page.SCHEDULE) }
    var tomorrow by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var regions by remember { mutableStateOf(repository.cachedRegions()) }
    var catalogLoading by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(ZonedDateTime.now(kyiv)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val requestedRegion = if (page == Page.QUEUE) pendingRegion else region
    var download by remember(requestedRegion) {
        mutableStateOf(requestedRegion?.let(repository::cachedSchedule)?.let { Download(it, true) })
    }
    var loading by remember(requestedRegion) { mutableStateOf(requestedRegion != null) }
    val today = now.toLocalDate()

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = ZonedDateTime.now(kyiv)
                delay(15_000)
            }
        }
    }
    LaunchedEffect(lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            catalogLoading = true
            regions = withContext(Dispatchers.IO) { repository.regions() }
            catalogLoading = false
        }
    }
    LaunchedEffect(lifecycle, requestedRegion, refresh, today) {
        val code = requestedRegion ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading = true
            download = withContext(Dispatchers.IO) { repository.schedule(code) }
            loading = false
        }
    }
    val schedule = download?.schedule
    val notice = schedule?.notice
    val days = schedule?.days.orEmpty().filter { it.date == today || it.date == today.plusDays(1) }
    val groups = schedule?.copy(days = days)?.groups.orEmpty()
    val date = today.plusDays(if (tomorrow) 1 else 0)
    val intervals = days.firstOrNull { it.date == date }?.groups?.get(queue)
    val regionName = regions.firstOrNull { it.code == region }?.name ?: region.orEmpty()
    BackHandler(page == Page.QUEUE || (page == Page.REGION && queue != null)) {
        page = if (page == Page.QUEUE && (queue == null || pendingRegion != region)) Page.REGION else Page.SCHEDULE
    }

    key(page, if (page == Page.SCHEDULE) tomorrow else requestedRegion) {
        val state = rememberTransformingLazyColumnState()
        ScreenScaffold(scrollState = state) { insets ->
            TransformingLazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                state = state,
                contentPadding = insets,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                when (page) {
                    Page.REGION -> {
                        item { WatchHeading("Оберіть регіон") }
                        if (regions.isEmpty()) item {
                            if (catalogLoading) WatchMessage("Завантажуємо регіони…")
                            else WatchButton("Повторити") { refresh++ }
                        }
                        for (item in regions) item(key = item.code) {
                            WatchButton(item.name) { pendingRegion = item.code; page = Page.QUEUE }
                        }
                    }
                    Page.QUEUE -> {
                        item { WatchHeading("Оберіть чергу") }
                        if (groups.isEmpty()) {
                            item { WatchMessage(if (loading) "Завантажуємо графік…" else "Черги недоступні") }
                            if (!loading) item { WatchButton("Повторити") { refresh++ } }
                        }
                        for (group in groups) item(key = group) {
                            WatchButton("Черга $group") {
                                val code = checkNotNull(pendingRegion)
                                save(code, group)
                                region = code
                                queue = group
                                tomorrow = false
                                page = Page.SCHEDULE
                            }
                        }
                        item { WatchButton("Інший регіон") { page = Page.REGION } }
                    }
                    Page.SCHEDULE -> {
                        item {
                            val current = if (!tomorrow) intervals?.firstOrNull { now.hour * 60 + now.minute in it.start until it.end } else null
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                WatchHeading(current?.power?.let { "За графіком" } ?: if (tomorrow) "Завтра" else "Сьогодні")
                                if (current != null) {
                                    Text(current.power.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                                    WatchMessage("До ${clock(current.end)}")
                                }
                                WatchMessage("$regionName · $queue")
                            }
                        }
                        item { WatchButton(if (tomorrow) "До сьогодні" else "Графік на завтра") { tomorrow = !tomorrow } }
                        if (notice != null) item {
                            WatchMessage((if (download?.offline == true || schedule.noticeStale) "Збережене попередження. " else "⚠ ") + notice)
                        }
                        item { WatchMessage("${date.format(dateFormat)} · час київський") }
                        if (intervals == null) item {
                            WatchMessage(when {
                                loading -> "Завантажуємо графік…"
                                download?.offline == true -> "Немає зв’язку"
                                else -> "Графік ще недоступний"
                            })
                        } else {
                            if (download?.offline == true || schedule?.stale == true || date.toString() in schedule?.retained.orEmpty()) {
                                item { WatchMessage("Збережений графік") }
                            }
                            for (interval in intervals) item(key = interval.start) {
                                val colors = MaterialTheme.colorScheme
                                val outage = interval.power == Power.OFF
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(if (outage) colors.errorContainer else colors.surfaceContainer, RoundedCornerShape(18.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val color = if (outage) colors.onErrorContainer else colors.onSurface
                                    Text("${clock(interval.start)}–${clock(interval.end)}", color = color,
                                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                    Text(interval.power.label, color = color, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item { WatchButton(if (loading) "Оновлюємо…" else "Оновити", enabled = !loading) { refresh++ } }
                        item { WatchButton("Змінити чергу") { pendingRegion = region; page = Page.QUEUE } }
                        item { WatchButton("Змінити регіон") { page = Page.REGION } }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchHeading(text: String) {
    Text(text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun WatchMessage(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WatchButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors()) { Text(text, maxLines = 3) }
}
