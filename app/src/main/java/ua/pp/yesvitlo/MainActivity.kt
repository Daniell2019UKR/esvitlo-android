package ua.pp.yesvitlo

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ThemeMode(val label: String) {
    SYSTEM("Як у системі"), LIGHT("Світла"), DARK("Темна")
}
private val locale = Locale.forLanguageTag("uk-UA")
private val calendarLabel = DateTimeFormatter.ofPattern("dd-MM-yyyy", locale)
private val updatedLabel = DateTimeFormatter.ofPattern("dd-MM-yyyy · HH:mm", locale)
private val syncOptions = listOf(30, 60, 120, 300, 600, 900, 1800, 3600)
private fun syncLabel(seconds: Int) = when {
    seconds == 3600 -> "1 год"
    seconds >= 60 -> "${seconds / 60} хв"
    else -> "$seconds с"
}

class MainActivity : ComponentActivity() {
    private var requestedPlace by mutableStateOf<String?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedPlace = intent.getStringExtra("place")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedPlace = intent.getStringExtra("place")
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
                    requestedPlace = requestedPlace, clearRequestedPlace = { requestedPlace = null },
                    repository = remember { ScheduleRepository(prefs) },
                    initialRegion = prefs.getString("region", "o") ?: "o",
                    savedQueue = { prefs.getString("queue:$it", null) },
                    saveRegion = { prefs.edit().putString("region", it).apply(); updateWidgets(this) },
                    saveQueue = { region, group -> prefs.edit().putString("queue:$region", group).apply(); updateWidgets(this) },
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
    requestedPlace: String?, clearRequestedPlace: () -> Unit,
    repository: ScheduleRepository, initialRegion: String, savedQueue: (String) -> String?,
    saveRegion: (String) -> Unit, saveQueue: (String, String) -> Unit,
    theme: ThemeMode, selectTheme: (ThemeMode) -> Unit
) {
    var region by rememberSaveable { mutableStateOf(initialRegion) }
    var group by rememberSaveable { mutableStateOf(savedQueue(initialRegion)) }
    var tomorrow by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var manualRefresh by remember { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var regions by remember { mutableStateOf(emptyList<Region>()) }
    var download by remember(region) { mutableStateOf<Download?>(null) }
    var loading by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(ZonedDateTime.now(kyiv)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("esvitlo", android.content.Context.MODE_PRIVATE) }
    val updateScope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updateStatus by remember { mutableStateOf("") }
    var updateBusy by remember { mutableStateOf(false) }
    var updatePrompt by remember { mutableStateOf(false) }
    fun checkUpdate(manual: Boolean) {
        if (updateBusy) return
        updateBusy = true
        updateScope.launch {
            try {
                update = withContext(Dispatchers.IO) { latestUpdate() }
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                updatePrompt = update != null
                updateStatus = if (update == null && manual) "Установлено останню версію." else ""
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (manual) updateStatus = "Не вдалося перевірити оновлення."
            } finally { updateBusy = false }
        }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (BuildConfig.GITHUB_UPDATES &&
                System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) >= 86_400_000L) checkUpdate(false)
        }
    }
    if (updatePrompt) update?.let { available ->
        AlertDialog(onDismissRequest = { updatePrompt = false }, title = { Text("Доступна версія ${available.version}") },
            text = { Text("Відкрийте сторінку релізу на GitHub, щоб завантажити APK.") },
            dismissButton = { TextButton(onClick = { updatePrompt = false }) { Text("Пізніше") } },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.url))) }
                    .onSuccess { updatePrompt = false }
                    .onFailure { updateStatus = "Не вдалося відкрити GitHub."; updatePrompt = false }
            }) { Text("Відкрити на GitHub") } })
    }
    var onboarding by rememberSaveable { mutableStateOf(!prefs.getBoolean("setupComplete", prefs.contains("queue:$initialRegion"))) }
    var places by remember { mutableStateOf(prefs.places()) }
    var activePlace by rememberSaveable { mutableStateOf(prefs.getString("activePlace", "home")) }
    var editingPlace by remember { mutableStateOf<Place?>(null) }
    var placeSchedules by remember { mutableStateOf(emptyMap<String, Schedule>()) }
    fun selectPlace(place: Place) {
        activePlace = place.id
        region = place.region
        group = place.queue
        prefs.edit().putString("activePlace", place.id).apply()
        saveRegion(place.region)
        place.queue?.let { saveQueue(place.region, it) }
    }
    fun persistPlaces(next: List<Place>) {
        places = next
        prefs.savePlaces(next)
        syncPush(context)
        syncOutageReminders(context)
        syncBackground(context, refresh = true)
        updateWidgets(context)
    }
    LaunchedEffect(requestedPlace) {
        val id = requestedPlace ?: return@LaunchedEffect
        places.find { it.id == id }?.let(::selectPlace)
        clearRequestedPlace()
    }
    var regionChosen by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var allQueues by rememberSaveable { mutableStateOf(false) }
    var queuesTomorrow by rememberSaveable { mutableStateOf(false) }
    var syncSeconds by remember {
        mutableIntStateOf(prefs.getInt("syncSeconds", 30).takeIf { it in syncOptions } ?: 30)
    }
    BackHandler(settings && !onboarding) { settings = false }
    BackHandler(allQueues) { allQueues = false }
    var notifications by remember { mutableStateOf(prefs.getBoolean(notificationSetting, false)) }
    var setupAddressFields by rememberSaveable(region) { mutableStateOf(arrayListOf<String>()) }
    val setupAddress = setupAddressFields.takeIf { it.size == 3 }?.let { PlaceAddress(it[0], it[1], it[2]) }
    var notificationModes by remember { mutableStateOf(NotificationType.entries.associateWith { prefs.notificationMode(it) }) }
    var notificationType by rememberSaveable { mutableStateOf<NotificationType?>(null) }
    var notificationHint by remember { mutableStateOf(false) }
    fun setNotifications(enabled: Boolean) {
        notifications = enabled
        if (enabled && places.none { it.alerts }) {
            places = places.map { if (it.id == activePlace) it.copy(alerts = true) else it }
            if (!onboarding) prefs.savePlaces(places)
        }
        prefs.edit().putBoolean(notificationSetting, enabled).apply()
        if (!onboarding) {
            syncPush(context)
            syncOutageReminders(context)
            syncBackground(context, refresh = enabled)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationHint = !it
        setNotifications(it)
    }
    val toggleNotifications: (Boolean) -> Unit = { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationHint = false
            setNotifications(enabled)
        }
    }
    fun openLink(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "https" && !uri.host.isNullOrEmpty())
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
    LaunchedEffect(Unit) {
        syncUpdateChecks(context)
        if (!onboarding) {
            syncPush(context)
            syncBackground(context)
        }
        while (true) {
            now = ZonedDateTime.now(kyiv)
            delay(15000)
        }
    }
    LaunchedEffect(refresh) { regions = withContext(Dispatchers.IO) { repository.regions() } }
    val monitoredRegions = places.map { it.region }.distinct().sorted()
    LaunchedEffect(lifecycle, region, refresh, now.toLocalDate(), syncSeconds, onboarding, regionChosen, monitoredRegions) {
        loading = true
        if (onboarding && !regionChosen) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                download = withContext(Dispatchers.IO) { repository.schedule(region) }
                if (!onboarding) download?.let { applyDownload(context, region, it) }
                loading = false
                manualRefresh = false
                if (!onboarding) {
                    val schedules = mutableMapOf<String, Schedule>()
                    download?.let { schedules[region] = it.schedule }
                    for (code in monitoredRegions.filter { it != region }) {
                        val nearby = withContext(Dispatchers.IO) { repository.schedule(code) }
                        schedules[code] = nearby.schedule
                        applyDownload(context, code, nearby)
                    }
                    placeSchedules = schedules
                }
                delay((syncSeconds * 1000L - (android.os.SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(1_000L))
            }
        }
    }

    val schedule = download?.schedule
    val today = now.toLocalDate()
    val date = today.plusDays(if (tomorrow) 1 else 0)
    val activeDays = schedule?.days.orEmpty().filter { it.date == today || it.date == today.plusDays(1) }
    val regionalGroups = schedule?.copy(days = activeDays)?.groups.orEmpty()
    val groups = (regionalGroups + listOfNotNull(group)).distinct()
    val intervals = group?.let { activeDays.firstOrNull { day -> day.date == date }?.groups?.get(it) }
    val previousIntervals = group?.let { schedule?.previousDays?.firstOrNull { day -> day.date == date }?.groups?.get(it) }
    val regionName = regions.firstOrNull { it.code == region }?.name
        ?: if (region == "o") "Одеса та область" else region
    val cached = download?.offline == true || schedule?.stale == true || date.toString() in schedule?.retained.orEmpty()
    val currentMinute = now.hour * 60 + now.minute

    val colors = MaterialTheme.colorScheme
    val scheduleScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val pullState = rememberPullToRefreshState()
    val refreshSchedule: () -> Unit = {
        if (!loading) {
            loading = true
            manualRefresh = true
            refresh++
        }
    }
    if (allQueues && !onboarding) {
        val queuesDate = today.plusDays(if (queuesTomorrow) 1 else 0)
        AllQueuesScreen(
            regionName = regionName,
            date = queuesDate.format(calendarLabel),
            day = activeDays.find { it.date == queuesDate },
            groups = regionalGroups,
            selected = group,
            tomorrow = queuesTomorrow,
            loading = loading,
            offline = download?.offline == true,
            cached = download?.offline == true || schedule?.stale == true ||
                queuesDate.toString() in schedule?.retained.orEmpty(),
            onDayChange = { queuesTomorrow = it },
            onBack = { allQueues = false }
        )
        return
    }
    Scaffold(containerColor = colors.surface, bottomBar = {
        if (!onboarding) NavigationBar {
            NavigationBarItem(selected = !settings, onClick = { settings = false },
                icon = { Text("▤", style = MaterialTheme.typography.titleLarge) }, label = { Text("Графік") })
            NavigationBarItem(selected = settings, onClick = { settings = true },
                icon = { Text("⚙", style = MaterialTheme.typography.titleLarge) }, label = { Text("Параметри") })
        }
    }) { insets ->
        val scheduleVisible = !settings && !onboarding
        val pullModifier = if (scheduleVisible) Modifier.pullToRefresh(
            isRefreshing = manualRefresh, state = pullState, onRefresh = refreshSchedule
        ) else Modifier
        Box(Modifier.fillMaxSize().padding(insets).then(pullModifier), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .verticalScroll(if (settings || onboarding) settingsScroll else scheduleScroll).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onboarding || settings) {
                    Text(if (onboarding) "Налаштуймо єСвітло" else "Параметри",
                        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    if (onboarding) Text("Оберіть регіон і чергу. За бажанням додайте адресу для пошуку черги.")
                    if (!onboarding) {
                        Text("Мої локації", style = MaterialTheme.typography.titleLarge)
                        for (place in places) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selectPlace(place) }, modifier = Modifier.weight(1f)) {
                                    Column {
                                        Text((if (place.id == activePlace) "✓ " else "") + place.name)
                                        place.address?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                                TextButton(onClick = { editingPlace = place }) { Text("Змінити") }
                            }
                        }
                        FilledTonalButton(onClick = {
                            editingPlace = Place(java.util.UUID.randomUUID().toString(), "", region, null, false)
                        }) { Text("Додати локацію") }
                    }
                    if (onboarding) FilledTonalButton(onClick = { picker = "region" }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (onboarding && !regionChosen) "Обрати регіон" else "Регіон · $regionName")
                    }
                    if (regions.isEmpty()) TextButton(onClick = { refresh++ }) { Text("Повторити завантаження регіонів") }
                    if (onboarding && regionChosen && regions.any { it.code == region && it.addressLookup }) {
                        OutlinedButton(onClick = { picker = "group" }, modifier = Modifier.fillMaxWidth(),
                            enabled = groups.isNotEmpty()) {
                            Text(group?.let { "Черга · $it" } ?: "Обрати чергу")
                        }
                        if (onboarding && loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (onboarding && !loading && groups.isEmpty()) {
                            Text("Черги поки недоступні. Їх можна вибрати в параметрах пізніше.")
                            TextButton(onClick = { refresh++ }) { Text("Повторити") }
                        }
                    }
                    if (!onboarding) {
                        OutlinedButton(onClick = { picker = "theme" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Тема · ${theme.label}  ›")
                        }
                        if (BuildConfig.GITHUB_UPDATES) {
                            OutlinedButton(onClick = { checkUpdate(true) }, enabled = !updateBusy,
                                modifier = Modifier.fillMaxWidth()) { Text("Перевірити оновлення застосунку") }
                        }
                        if (updateStatus.isNotEmpty()) Text(updateStatus)
                        OutlinedButton(onClick = { picker = "sync" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Частота синхронізації · ${syncLabel(syncSeconds)}  ›")
                        }
                        Text("Інтервал діє у відкритому застосунку. Фонові перевірки Android планує окремо.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Сповіщення про зміни графіка та попередження", Modifier.weight(1f).padding(end = 12.dp))
                        Switch(checked = notifications, onCheckedChange = toggleNotifications)
                    }
                    if (notificationHint) TextButton(onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("Дозволити сповіщення в налаштуваннях Android") }
                    if (onboarding && regionChosen && regions.any { it.code == region && it.addressLookup }) {
                        key(region) {
                            PlaceAddressFields(Place("home", "Дім", region, group, notifications,
                                address = setupAddress),
                                regions.find { it.code == region }, repository, groups) { draft ->
                                group = draft.queue
                                val address = draft.address ?: PlaceAddress()
                                setupAddressFields = arrayListOf(address.city, address.street, address.house)
                            }
                        }
                    }
                    if (!onboarding) {
                        OutlinedButton(onClick = { picker = "reminder" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Нагадування про світло")
                        }
                        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
                        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms() &&
                            places.any { prefs.hasReminders(it) }) TextButton(onClick = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}")))
                        }) { Text("Дозволити точний час нагадувань") }
                        Text("Типи сповіщень", style = MaterialTheme.typography.titleMedium)
                        for (type in NotificationType.entries) {
                            if (!BuildConfig.GITHUB_UPDATES && type == NotificationType.APP_UPDATE) continue
                            OutlinedButton(onClick = { notificationType = type }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(type.label)
                                    Text(notificationModes.getValue(type).label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (onboarding) {
                        Button(onClick = {
                            val home = Place("home", "Дім", region, group, notifications,
                                address = setupAddress?.trimmed()?.takeIf { it.complete })
                            selectPlace(home)
                            prefs.edit().putBoolean("setupComplete", true).apply()
                            persistPlaces(listOf(home) + places.filterNot { it.id == home.id })
                            onboarding = false
                        }, modifier = Modifier.fillMaxWidth(),
                            enabled = regionChosen && setupAddress?.let { it.empty || it.complete } != false &&
                                (group != null || setupAddress?.complete == true || !loading && groups.isEmpty())) {
                            Text(if (group != null || !regionChosen) "Почати" else "Продовжити без черги")
                        }
                    } else {
                        HorizontalDivider()
                        regions.find { it.code == region }?.source?.let { source ->
                            TextButton(onClick = { openLink(source) }) { Text("Сайт оператора ↗") }
                        }
                        TextButton(onClick = { openLink("https://github.com/Daniell2019UKR/esvitlo-android") }) { Text("GitHub застосунку ↗") }
                        TextButton(onClick = { openLink("https://t.me/dtekschedule_bot") }) { Text("Telegram-бот ↗") }
                        TextButton(onClick = { openLink("https://yesvitlo.pp.ua/") }) { Text("Сайт єСвітло ↗") }
                        TextButton(onClick = { openLink("https://yesvitlo.pp.ua/privacy.html") }) { Text("Політика конфіденційності ↗") }
                        TextButton(onClick = { picker = "changelog" }) { Text("Що нового") }
                        Text("Версія ${BuildConfig.VERSION_NAME} · збірка ${BuildConfig.VERSION_CODE}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_svitlo_mark), null, Modifier.size(28.dp), tint = colors.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("єСвітло", style = MaterialTheme.typography.titleLarge, color = colors.primary,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Button(onClick = refreshSchedule, enabled = !loading) { Text("↻  Оновити") }
                    }
                    Text(regionName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    if (places.size > 1) {
                        val pinned = places.take(3)
                        val selectedPlace = places.find { it.id == activePlace }
                        val visiblePlaces = if (selectedPlace != null && selectedPlace !in pinned)
                            pinned.take(2) + selectedPlace else pinned
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            visiblePlaces.forEach { place ->
                                FilterChip(selected = place.id == activePlace, onClick = { selectPlace(place) },
                                    modifier = Modifier.weight(1f), label = {
                                        Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    })
                            }
                            if (places.size > 3) IconButton(onClick = { picker = "places" }) {
                                Icon(painterResource(R.drawable.ic_more_locations), contentDescription = "Усі локації")
                            }
                        }
                    }
                    Text(group?.let { "Черга $it" } ?: "Чергу не вибрано", color = colors.onSurfaceVariant)
                    if (loading && !manualRefresh) LinearProgressIndicator(Modifier.fillMaxWidth())
                    schedule?.notice?.let { notice ->
                        OperatorNotice(notice, download?.offline == true || schedule.noticeStale)
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
                                    group == null -> "Оберіть чергу в параметрах, щоб бачити її графік."
                                    download?.offline == true -> "Немає зв’язку. На цю дату графік не збережено."
                                    else -> "На цю дату графік для вибраної черги наразі недоступний."
                                }, Modifier.padding(vertical = 20.dp))
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    for ((interval, previousPower) in scheduleRows(intervals, previousIntervals)) {
                                        IntervalRow(interval, !tomorrow && currentMinute in interval.start until interval.end,
                                            previousPower)
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
                    FilledTonalButton(onClick = {
                        queuesTomorrow = tomorrow
                        allQueues = true
                    }, modifier = Modifier.fillMaxWidth()) { Text("Усі черги регіону") }
                    Text("Час київський. Фактичні відключення можуть відрізнятися від графіка.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (scheduleVisible) PullToRefreshDefaults.Indicator(
                state = pullState, isRefreshing = manualRefresh, modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    notificationType?.let { type ->
        AlertDialog(
            onDismissRequest = { notificationType = null },
            title = { Text(type.label) },
            text = {
                Column(Modifier.selectableGroup()) {
                    for (mode in NotificationMode.entries) {
                        val selectedMode = notificationModes.getValue(type) == mode
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = selectedMode, role = Role.RadioButton, onClick = {
                                prefs.edit().putString("notificationMode:${type.key}", mode.name).apply()
                                notificationModes = notificationModes + (type to mode)
                                if (type == NotificationType.APP_UPDATE) syncPush(context)
                                if (type in reminderTypes) {
                                    syncOutageReminders(context)
                                    syncBackground(context, refresh = true)
                                }
                                notificationType = null
                            }
                        ), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedMode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(mode.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { notificationType = null }) { Text("Закрити") } }
        )
    }

    editingPlace?.let { place ->
        PlaceEditor(place, regions, repository,
            onClose = { editingPlace = null }, onSave = { updated ->
            persistPlaces(if (places.any { it.id == updated.id }) places.map { if (it.id == updated.id) updated else it }
                else places + updated)
            selectPlace(updated)
            editingPlace = null
        }, onDelete = if (places.size > 1 && places.any { it.id == place.id }) ({
            val remaining = places.filterNot { it.id == place.id }
            persistPlaces(remaining)
            if (activePlace == place.id) selectPlace(remaining.first())
            editingPlace = null
        }) else null)
    }
    if (picker == "places") {
        AlertDialog(onDismissRequest = { picker = null }, title = { Text("Мої локації") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(places, key = { it.id }) { place ->
                    TextButton(onClick = { selectPlace(place); picker = null }) {
                        Column {
                            Text(listOfNotNull(place.name, regions.find { it.code == place.region }?.name, place.queue).joinToString(" · "))
                            val day = placeSchedules[place.region]?.days?.find { it.date == today }
                            val slot = day?.groups?.get(place.queue)?.firstOrNull { currentMinute in it.start until it.end }
                            Text(when (slot?.power) {
                                Power.ON -> "За графіком світло є · до " + clock(slot.end)
                                Power.OFF -> "За графіком світла немає · до " + clock(slot.end)
                                else -> "Немає даних"
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { picker = null }) { Text("Закрити") } })
    } else if (picker == "changelog") {
        AlertDialog(onDismissRequest = { picker = null }, title = { Text("Що нового") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("• Адресу можна додати вже під час першого налаштування або в редакторі локації.")
                Text("• Можна вибрати кілька нагадувань до відключення та відновлення світла.")
            } },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("Закрити") } })
    } else if (picker == "reminder") {
        var outage by remember { mutableStateOf(prefs.defaultReminderMinutes(NotificationType.REMINDER).reminderSettingText()) }
        var restoration by remember { mutableStateOf(prefs.defaultReminderMinutes(NotificationType.RESTORATION).reminderSettingText()) }
        AlertDialog(onDismissRequest = { picker = null }, title = { Text("Нагадування для всіх локацій") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderMinutesField(outage, { outage = it }, "До відключення, хв")
                ReminderMinutesField(restoration, { restoration = it }, "До відновлення, хв")
                Text("Введіть час через кому, наприклад 60, 15. 0 або порожнє поле — вимкнено.",
                    style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = validReminderMinutes(outage) && validReminderMinutes(restoration), onClick = {
                prefs.edit()
                    .putString("reminders:reminder", parseReminderMinutes(outage).orEmpty().reminderSettingText())
                    .putString("reminders:restoration", parseReminderMinutes(restoration).orEmpty().reminderSettingText())
                    .apply()
                syncOutageReminders(context)
                syncBackground(context, refresh = true)
                picker = null
            }) { Text("Зберегти") } },
            dismissButton = { TextButton(onClick = { picker = null }) { Text("Скасувати") } })
    } else if (picker == "sync") {
        AlertDialog(onDismissRequest = { picker = null }, title = { Text("Частота синхронізації") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    syncOptions.forEach { seconds ->
                        TextButton(onClick = {
                            syncSeconds = seconds
                            prefs.edit().putInt("syncSeconds", seconds).apply()
                            picker = null
                        }) { Text(if (seconds == syncSeconds) "✓ ${syncLabel(seconds)}" else syncLabel(seconds)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("Закрити") } })
    } else if (picker == "theme") {
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
                                regionChosen = true
                                region = code
                                group = savedQueue(code)
                                if (!onboarding) { saveRegion(code); syncPush(context) }
                            } else {
                                group = code
                                if (!onboarding) { saveQueue(region, code); syncPush(context) }
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
private fun OperatorNotice(message: String, stale: Boolean) {
    var expanded by rememberSaveable(message) { mutableStateOf(false) }
    var truncated by remember(message) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.errorContainer,
        contentColor = colors.onErrorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_widget_warning), contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Text(if (stale) "Збережене попередження" else "Попередження оператора",
                    style = MaterialTheme.typography.titleSmall)
            }
            Text(message, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow })
            if (truncated || expanded) {
                TextButton(onClick = { expanded = !expanded },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer)) {
                    Text(if (expanded) "Згорнути  ↑" else "Читати далі  ↓")
                }
            }
        }
    }
}

@Composable
private fun IntervalRow(interval: Interval, current: Boolean, previousPower: Power?) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val change = when {
        previousPower == null -> null
        interval.power == Power.UNKNOWN -> "Дані зникли"
        previousPower == Power.UNKNOWN -> "Дані відновлено"
        interval.power == Power.OFF -> "Додано відключення"
        else -> "Скасовано відключення"
    }
    val outline = when {
        change == null -> Color.Transparent
        interval.power == Power.OFF -> colors.error
        previousPower == Power.OFF && interval.power == Power.ON ->
            if (colors.surface.luminance() < 0.5f) Color(0xFF71DBA0) else Color(0xFF187645)
        else -> colors.outline
    }
    val label = when (interval.power) {
        Power.ON -> "Світло є"
        Power.OFF -> "Відключення"
        Power.UNKNOWN -> "Немає даних"
    }
    Row(Modifier.fillMaxWidth()
        .background(if (current) colors.secondaryContainer else Color.Transparent, shape)
        .then(if (change != null) Modifier.border(1.dp, outline, shape) else Modifier)
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
            if (change != null) Text(change, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(formatDuration(interval.end - interval.start), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun formatDuration(minutes: Int) = listOfNotNull(
    (minutes / 60).takeIf { it > 0 }?.let { "$it год" },
    (minutes % 60).takeIf { it > 0 || minutes == 0 }?.let { "$it хв" }
).joinToString(" ")
