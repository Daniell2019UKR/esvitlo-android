package ua.pp.yesvitlo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
                            address = null)
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
                PlaceAddressFields(draft, provider, repository, queues) {
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
internal fun PlaceAddressFields(place: Place, provider: Region?, repository: ScheduleRepository, queues: List<String>,
                               onChange: (Place) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(place.address != null) }
    var found by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val address = place.address ?: PlaceAddress()
    fun edit(value: PlaceAddress) {
        found = emptyList()
        status = ""
        onChange(place.copy(address = value))
    }
    if (!expanded) {
        TextButton(onClick = { expanded = true }) { Text("Додати адресу") }
        return
    }
    Text("Адреса локації", style = MaterialTheme.typography.titleSmall)
    AddressFields(address, !searching, ::edit)
    if (provider?.addressLookup == true) {
        TextButton(enabled = !searching && address.complete, onClick = {
            searching = true
            val (city, street, house) = address.trimmed()
            scope.launch {
                val groups = withContext(Dispatchers.IO) {
                    try { repository.addressQueues(place.region, city, street, house) }
                    catch (_: java.io.IOException) { emptyList() }
                    catch (_: org.json.JSONException) { emptyList() }
                    catch (_: IllegalStateException) { emptyList() }
                }
                found = groups.filter { queues.isEmpty() || it in queues }
                status = if (found.isNotEmpty()) "Оберіть запропоновану чергу або залиште поточну."
                    else "Чергу можна вибрати вручну."
                searching = false
            }
        }) { Text(if (searching) "Перевіряємо…" else "Знайти чергу за адресою") }
    }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    for (queue in found) TextButton(onClick = { onChange(place.copy(queue = queue)); found = emptyList() }) { Text("Обрати $queue") }
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
