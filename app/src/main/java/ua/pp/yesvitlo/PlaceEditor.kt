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
                         onClose: () -> Unit, onSave: (Place) -> Unit, onDelete: (() -> Unit)?) {
    var draft by remember(place) { mutableStateOf(place) }
    var menu by remember { mutableStateOf("") }
    var queues by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    var outage by remember(place) { mutableStateOf(place.outageMinutes?.reminderSettingText().orEmpty()) }
    var restoration by remember(place) { mutableStateOf(place.restorationMinutes?.reminderSettingText().orEmpty()) }
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
                        if (region.code != draft.region) draft = draft.copy(region = region.code, queue = null)
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
            if (regions.any { it.code == draft.region && it.addressLookup }) {
                key(draft.region) {
                    AddressPicker(draft.region, repository, queues) { draft = draft.copy(queue = it) }
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
            restorationMinutes = parseReminderMinutes(restoration))) },
            enabled = draft.name.isNotBlank() && validReminderMinutes(outage) && validReminderMinutes(restoration) &&
                (draft.queue != null || !loading && queues.isEmpty())) { Text("Зберегти") }
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
private fun AddressPicker(region: String, repository: ScheduleRepository, queues: List<String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var city by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    var house by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reset = { found = emptyList(); status = "" }
    if (!expanded) {
        TextButton(onClick = { expanded = true }) { Text("Знайти чергу за адресою") }
        return
    }
    OutlinedTextField(city, { city = it.take(100); reset() }, label = { Text("Населений пункт") }, singleLine = true, enabled = !searching)
    OutlinedTextField(street, { street = it.take(100); reset() }, label = { Text("Вулиця") }, singleLine = true, enabled = !searching)
    OutlinedTextField(house, { house = it.take(30); reset() }, label = { Text("Будинок") }, singleLine = true, enabled = !searching)
    TextButton(enabled = !searching && city.isNotBlank() && street.isNotBlank() && house.isNotBlank(), onClick = {
        searching = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.addressQueues(region, city, street, house) } }
            found = result.getOrDefault(emptyList()).filter { it in queues }
            status = when {
                result.isFailure -> "Пошук недоступний. Оберіть чергу вручну."
                found.isEmpty() -> "Чергу не знайдено. Оберіть її вручну."
                else -> "Підтвердьте чергу за даними оператора."
            }
            searching = false
        }
    }) { Text(if (searching) "Шукаємо…" else "Знайти") }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    for (queue in found) TextButton(onClick = { onChoose(queue); expanded = false; reset() }) { Text("Обрати $queue") }
}

@Composable
internal fun AddressOutageDialog(region: String, repository: ScheduleRepository, onClose: () -> Unit) {
    var city by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    var house by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AddressOutage?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text("Аварії за адресою") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(city, { city = it.take(100); result = null }, label = { Text("Населений пункт") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !loading)
            OutlinedTextField(street, { street = it.take(100); result = null }, label = { Text("Вулиця") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !loading)
            OutlinedTextField(house, { house = it.take(100); result = null }, label = { Text("Будинок") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !loading)
            result?.let { outage ->
                val text = when (outage.status) {
                    "active" -> listOfNotNull("Аварійне відключення", outage.reason,
                        outage.startedAt?.let { "Початок: $it" },
                        outage.estimatedRestoration?.let { "Орієнтовне відновлення: $it" }).joinToString("\n")
                    "no_emergency" -> "Оператор не повідомив про аварію за цією адресою."
                    "not_found" -> "Оператор не знайшов цей будинок."
                    "ambiguous" -> "Адреса неоднозначна. Уточніть її на сайті оператора."
                    else -> "Не вдалося перевірити аварії. Спробуйте пізніше."
                }
                if (outage.status == "active") Surface(color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text(text, Modifier.padding(12.dp))
                } else Text(text)
            }
        }
    }, confirmButton = {
        TextButton(enabled = !loading && city.isNotBlank() && street.isNotBlank() && house.isNotBlank(), onClick = {
            loading = true
            result = null
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    try { repository.addressOutage(region, city, street, house) }
                    catch (_: java.io.IOException) { AddressOutage("unavailable", null, null, null) }
                    catch (_: org.json.JSONException) { AddressOutage("unavailable", null, null, null) }
                }
                loading = false
            }
        }) { Text(if (loading) "Перевіряємо…" else "Перевірити") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Закрити") } })
}
