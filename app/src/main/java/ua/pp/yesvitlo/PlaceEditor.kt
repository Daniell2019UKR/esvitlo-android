package ua.pp.yesvitlo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PlaceEditor(place: Place, regions: List<Region>, repository: ScheduleRepository,
                         onClose: () -> Unit, onSave: (Place) -> Unit, onDelete: (() -> Unit)?) {
    var draft by remember(place) { mutableStateOf(place) }
    var menu by remember { mutableStateOf("") }
    var queues by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сповіщення", Modifier.weight(1f))
                Switch(draft.alerts, { draft = draft.copy(alerts = it) })
            }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Видалити локацію") }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(draft.copy(name = draft.name.trim())) },
            enabled = draft.name.isNotBlank() && (draft.queue != null || !loading && queues.isEmpty())) { Text("Зберегти") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Скасувати") } })
}
