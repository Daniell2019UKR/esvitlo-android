package ua.pp.yesvitlo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PlaceEditor(place: Place, regions: List<Region>, repository: ScheduleRepository,
                         addressAlertsAvailable: Boolean, watchSlotAvailable: Boolean,
                         onClose: () -> Unit, onSave: (Place) -> Unit, onDelete: (() -> Unit)?) {
    var draft by remember(place) { mutableStateOf(place) }
    var menu by remember { mutableStateOf("") }
    var queues by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    var outage by remember(place) { mutableStateOf(place.outageMinutes?.reminderSettingText().orEmpty()) }
    var restoration by remember(place) { mutableStateOf(place.restorationMinutes?.reminderSettingText().orEmpty()) }
    val provider = regions.find { it.code == draft.region }
    val addressComplete = draft.address?.complete == true
    LaunchedEffect(draft.region) {
        loading = true
        queues = withContext(Dispatchers.IO) { repository.schedule(draft.region).schedule.groups }
        loading = false
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Локація") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(40)) }, label = { Text("Назва") }, singleLine = true)
            Box {
                TextButton(onClick = { menu = "region" }) { Text(regions.find { it.code == draft.region }?.name ?: draft.region) }
                DropdownMenu(menu == "region", { menu = "" }) {
                    for (region in regions) DropdownMenuItem(text = { Text(region.name) }, onClick = {
                        if (region.code != draft.region) draft = draft.copy(region = region.code, queue = null,
                            address = null, addressWatch = null)
                        menu = ""
                    })
                }
            }
            Box {
                TextButton(onClick = { menu = "queue" }, enabled = !loading && queues.isNotEmpty()) {
                    Text(draft.queue?.let { "Черга $it" } ?: if (loading) "Завантажуємо черги…" else if (queues.isEmpty()) "Черги недоступні" else "Обрати чергу")
                }
                DropdownMenu(menu == "queue", { menu = "" }) {
                    for (queue in queues) DropdownMenuItem(text = { Text(queue) }, onClick = {
                        draft = draft.copy(queue = queue)
                        menu = ""
                    })
                }
            }
            key(draft.region) {
                PlaceAddressFields(draft, provider, repository, queues, addressAlertsAvailable, watchSlotAvailable) {
                    draft = it
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сповіщення", Modifier.weight(1f))
                Switch(draft.alerts, { draft = draft.copy(alerts = it) })
            }
            ReminderMinutesField(outage, { outage = it }, "До відключення, хв")
            Spacer(Modifier.height(8.dp))
            ReminderMinutesField(restoration, { restoration = it }, "До відновлення, хв")
            Text("Наприклад, 60, 15. Порожньо — як для всіх, 0 — вимкнено.", style = MaterialTheme.typography.bodySmall)
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Видалити локацію") }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(draft.copy(name = draft.name.trim(), outageMinutes = parseReminderMinutes(outage),
            restorationMinutes = parseReminderMinutes(restoration), address = draft.address?.trimmed()?.takeIf { it.complete })) },
            enabled = draft.name.isNotBlank() && validReminderMinutes(outage) && validReminderMinutes(restoration) &&
                (draft.address?.empty != false || addressComplete) &&
                (draft.queue != null || addressComplete || !loading && queues.isEmpty())) { Text("Зберегти") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Скасувати") } })
}

@Composable
internal fun ReminderMinutesField(value: String, onChange: (String) -> Unit, label: String) {
    val invalid = !validReminderMinutes(value)
    OutlinedTextField(value, { onChange(it.take(60)) }, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        isError = invalid, supportingText = if (invalid) { { Text("До 10 чисел від 1 до 180 через кому.") } } else null)
}

internal fun validReminderMinutes(value: String) = value.isBlank() || parseReminderMinutes(value) != null

@Composable
private fun PlaceAddressFields(place: Place, provider: Region?, repository: ScheduleRepository, queues: List<String>,
                               alertsAvailable: Boolean, slotAvailable: Boolean, onChange: (Place) -> Unit) {
    var expanded by remember { mutableStateOf(place.address != null) }
    var found by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(place.addressWatch != null) }
    val scope = rememberCoroutineScope()
    val address = place.address ?: PlaceAddress()
    fun edit(value: PlaceAddress) {
        found = emptyList(); status = ""; verified = false
        onChange(place.copy(address = value, addressWatch = null))
    }
    if (!expanded) {
        TextButton(onClick = { expanded = true }) { Text("Додати адресу") }
        return
    }
    Text("Адреса локації", style = MaterialTheme.typography.titleSmall)
    AddressFields(address, !searching, ::edit)
    if (provider?.addressLookup == true || provider?.outageLookup == true) {
        TextButton(enabled = !searching && address.complete, onClick = {
            searching = true
            val (city, street, house) = address.trimmed()
            scope.launch {
                val update = withContext(Dispatchers.IO) {
                    val status = if (provider.outageLookup) runCatching {
                        repository.addressOutage(place.region, city, street, house)
                    }.getOrNull() else null
                    val groups = if (provider.addressLookup) runCatching {
                        repository.addressQueues(place.region, city, street, house)
                    }.getOrDefault(emptyList()) else emptyList()
                    status to groups
                }
                found = update.second.filter { queues.isEmpty() || it in queues }
                if (provider.outageLookup) verified = verified || update.first?.status in setOf("active", "no_emergency")
                status = listOfNotNull(update.first?.displayText(),
                    if (found.isNotEmpty()) "Оберіть запропоновану чергу або залиште поточну."
                    else if (provider.addressLookup) "Чергу можна вибрати вручну." else null)
                    .joinToString("\n").ifBlank { "Не вдалося перевірити адресу. Спробуйте пізніше." }
                searching = false
            }
        }) { Text(if (searching) "Перевіряємо…" else "Перевірити адресу") }
    }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    for (queue in found) TextButton(onClick = { onChange(place.copy(queue = queue)); found = emptyList() }) { Text("Обрати $queue") }
    if (provider?.outageLookup == true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Сповіщення про аварії за адресою", Modifier.weight(1f))
            Switch(checked = place.addressWatch != null,
                enabled = !searching && (place.addressWatch != null || verified && alertsAvailable && slotAvailable),
                onCheckedChange = { enabled ->
                    onChange(place.copy(addressWatch = if (enabled) java.util.UUID.randomUUID().toString() else null))
                })
        }
        Text(when {
            place.addressWatch != null -> "Сервер перевіряє цю адресу, доки сповіщення увімкнено."
            !alertsAvailable -> "Спочатку увімкніть сповіщення застосунку."
            !slotAvailable -> "Можна відстежувати до 12 адрес."
            !verified -> "Перевірте адресу перед увімкненням."
            else -> "Після ввімкнення адреса передаватиметься серверу для перевірок."
        }, style = MaterialTheme.typography.bodySmall)
    } else if (provider != null) Text("Підписка на аварії від цього оператора поки недоступна.",
        style = MaterialTheme.typography.bodySmall)
    if (!address.empty) TextButton(enabled = !searching, onClick = {
        edit(PlaceAddress())
        expanded = false
    }) { Text("Прибрати адресу") }
}

@Composable
private fun AddressFields(address: PlaceAddress, enabled: Boolean, onChange: (PlaceAddress) -> Unit) {
    OutlinedTextField(address.city, { onChange(address.copy(city = it.take(100))) }, label = { Text("Населений пункт") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled)
    OutlinedTextField(address.street, { onChange(address.copy(street = it.take(100))) }, label = { Text("Вулиця") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled)
    OutlinedTextField(address.house, { onChange(address.copy(house = it.take(100))) }, label = { Text("Будинок") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled)
}

private fun AddressOutage.displayText() = when (status) {
    "active" -> listOfNotNull("Аварійне відключення", reason,
        startedAt?.let { "Початок: $it" }, estimatedRestoration?.let { "Орієнтовне відновлення: $it" }).joinToString("\n")
    "no_emergency" -> "Оператор не повідомив про аварію за цією адресою."
    "not_found" -> "Оператор не знайшов цей будинок."
    "ambiguous" -> "Адреса неоднозначна. Уточніть її на сайті оператора. Для однойменних населених пунктів додайте громаду або РЕМ у дужках."
    else -> "Не вдалося перевірити аварії. Спробуйте пізніше."
}

@Composable
internal fun AddressOutageDialog(region: String, saved: PlaceAddress?, repository: ScheduleRepository, onClose: () -> Unit) {
    var address by remember(saved) { mutableStateOf(saved ?: PlaceAddress()) }
    var result by remember { mutableStateOf<AddressOutage?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text("Аварії за адресою") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AddressFields(address, !loading) { address = it; result = null }
            result?.let { outage ->
                val text = outage.displayText()
                if (outage.status == "active") Surface(color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text(text, Modifier.padding(12.dp))
                } else Text(text)
            }
        }
    }, confirmButton = {
        TextButton(enabled = !loading && address.complete, onClick = {
            loading = true
            result = null
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    val (city, street, house) = address.trimmed()
                    try { repository.addressOutage(region, city, street, house) }
                    catch (_: java.io.IOException) { AddressOutage("unavailable", null, null, null) }
                    catch (_: org.json.JSONException) { AddressOutage("unavailable", null, null, null) }
                }
                loading = false
            }
        }) { Text(if (loading) "Перевіряємо…" else "Перевірити") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Закрити") } })
}
