package ua.pp.yesvitlo

import android.os.Bundle
import android.os.Build
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ThemeMode(val label: String) {
    SYSTEM("Як у системі"), LIGHT("Світла"), DARK("Темна")
}
private val locale = Locale.forLanguageTag("uk-UA")
private val calendarLabel = DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)
private val updatedLabel = DateTimeFormatter.ofPattern("dd-MM-yyyy · HH:mm", locale)
private const val refreshIntervalMillis = 30_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("esvitlo", MODE_PRIVATE)
        setContent {
            var theme by remember { mutableStateOf(ThemeMode.entries.find { it.name == prefs.getString("theme", null) } ?: ThemeMode.SYSTEM) }
            val systemDark = isSystemInDarkTheme()
            val dark = theme == ThemeMode.DARK || theme == ThemeMode.SYSTEM && systemDark
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(0x00ffffff, 0x00000000) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            ExpressiveTheme(dark) {
                ScheduleScreen(
                    repository = remember { ScheduleRepository(prefs) },
                    initialRegion = prefs.getString("region", "o") ?: "o",
                    savedQueue = { prefs.getString("queue:$it", null) },
                    saveRegion = { prefs.edit().putString("region", it).apply() },
                    saveQueue = { region, group -> prefs.edit().putString("queue:$region", group).apply() },
                    theme = theme,
                    selectTheme = { theme = it; prefs.edit().putString("theme", it.name).apply() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFA4EAB3), onPrimary = Color(0xFF10391F),
            surface = Color(0xFF101512), surfaceContainer = Color(0xFF1A231D))
        else -> lightColorScheme(primary = Color(0xFF23683D), onPrimary = Color.White,
            surface = Color(0xFFF5FBF5), surfaceContainer = Color(0xFFE5EFE6))
    }
    MaterialExpressiveTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleScreen(
    repository: ScheduleRepository, initialRegion: String, savedQueue: (String) -> String?,
    saveRegion: (String) -> Unit, saveQueue: (String, String) -> Unit,
    theme: ThemeMode, selectTheme: (ThemeMode) -> Unit
) {
    var region by rememberSaveable { mutableStateOf(initialRegion) }
    var group by rememberSaveable { mutableStateOf(savedQueue(initialRegion)) }
    var tomorrow by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var regions by remember { mutableStateOf(emptyList<Region>()) }
    var download by remember(region) { mutableStateOf<Download?>(null) }
    var loading by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(ZonedDateTime.now(kyiv)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now(kyiv)
            delay(15000)
        }
    }
    LaunchedEffect(refresh) { regions = withContext(Dispatchers.IO) { repository.regions() } }
    LaunchedEffect(lifecycle, region, refresh, now.toLocalDate()) {
        loading = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                download = withContext(Dispatchers.IO) { repository.schedule(region) }
                loading = false
                delay((refreshIntervalMillis - (android.os.SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(1_000L))
            }
        }
    }

    val schedule = download?.schedule
    val today = now.toLocalDate()
    val date = today.plusDays(if (tomorrow) 1 else 0)
    val activeDays = schedule?.days.orEmpty().filter { it.date == today || it.date == today.plusDays(1) }
    val groups = (schedule?.copy(days = activeDays)?.groups.orEmpty() + listOfNotNull(group)).distinct()
    val selected = group ?: groups.firstOrNull()
    val intervals = selected?.let { activeDays.firstOrNull { day -> day.date == date }?.groups?.get(it) }
    val regionName = regions.firstOrNull { it.code == region }?.name
        ?: if (region == "o") "Одеса та область" else region
    val cached = download?.offline == true || schedule?.stale == true || date.toString() in schedule?.retained.orEmpty()
    val currentMinute = now.hour * 60 + now.minute

    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.surface) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_svitlo_mark), null, Modifier.size(28.dp), tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("єСвітло", style = MaterialTheme.typography.titleLarge, color = colors.primary,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Оновити") }
                }
                Text(regionName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { picker = "region" }) { Text("Регіон") }
                    FilledTonalButton(onClick = { picker = "group" }, enabled = groups.isNotEmpty()) {
                        Text(selected?.let { "Черга $it" } ?: "Черга")
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                schedule?.notice?.let { notice ->
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Text(if (download?.offline == true || schedule.noticeStale) "Збережене попередження\n$notice" else notice,
                            Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
                Surface(shape = RoundedCornerShape(32.dp), color = colors.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("Сьогодні", "Завтра").forEachIndexed { index, label ->
                                SegmentedButton(
                                    selected = tomorrow == (index == 1), onClick = { tomorrow = index == 1 },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2), label = { Text(label) }
                                )
                            }
                        }
                        Text(date.format(calendarLabel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (cached && intervals != null) Text("Збережений графік · актуальність не підтверджено", color = colors.onSurfaceVariant)
                        if (intervals == null) {
                            Text(when {
                                loading -> "Завантажуємо графік…"
                                download?.offline == true -> "Немає зв’язку. На цю дату графік не збережено."
                                else -> "На цю дату графік для вибраної черги наразі недоступний."
                            }, Modifier.padding(vertical = 20.dp))
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                intervals.forEach { interval ->
                                    IntervalRow(interval, !tomorrow && currentMinute in interval.start until interval.end)
                                }
                            }
                            HorizontalDivider()
                            schedule?.updated?.takeIf { date.toString() !in schedule.retained }?.let { stamp ->
                                runCatching { Instant.parse(stamp).atZone(kyiv).format(updatedLabel) }.getOrNull()?.let {
                                    Text("Графік оновлено $it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Text("Час київський. Фактичні відключення можуть відрізнятися від графіка.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { picker = "theme" }) { Text("Тема · ${theme.label}") }
            }
        }
    }

    if (picker == "theme") {
        AlertDialog(onDismissRequest = { picker = null }, title = { Text("Тема застосунку") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        TextButton(onClick = { selectTheme(mode); picker = null }) {
                            Text(if (mode == theme) "✓ ${mode.label}" else mode.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("Закрити") } })
    } else if (picker != null) {
        val choosingRegion = picker == "region"
        AlertDialog(onDismissRequest = { picker = null },
            title = { Text(if (choosingRegion) "Оберіть регіон" else "Оберіть чергу") },
            text = {
                val options = if (choosingRegion) regions.map { it.code to it.name } else groups.map { it to it }
                if (options.isEmpty()) Text("Список поки недоступний. Спробуйте оновити дані.")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options, key = { it.first }) { (code, name) ->
                        TextButton(onClick = {
                            if (choosingRegion) {
                                region = code
                                group = savedQueue(code)
                                saveRegion(code)
                            } else {
                                group = code
                                saveQueue(region, code)
                            }
                            picker = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(name, Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("Закрити") } })
    }
}

@Composable
private fun IntervalRow(interval: Interval, current: Boolean) {
    val label = when (interval.power) {
        Power.ON -> "Світло є"
        Power.OFF -> "Відключення"
        Power.UNKNOWN -> "Немає даних"
    }
    val duration = interval.end - interval.start
    val durationText = listOfNotNull(
        (duration / 60).takeIf { it > 0 }?.let { "$it год" },
        (duration % 60).takeIf { it > 0 }?.let { "$it хв" }
    ).joinToString(" ")
    Row(Modifier.fillMaxWidth()
        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(16.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${clock(interval.start)} – ${clock(interval.end)}",
                color = when (interval.power) {
                    Power.ON -> MaterialTheme.colorScheme.primary
                    Power.OFF -> MaterialTheme.colorScheme.error
                    Power.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (current) "$label · зараз" else label, style = MaterialTheme.typography.bodySmall)
        }
        Text(durationText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun clock(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
