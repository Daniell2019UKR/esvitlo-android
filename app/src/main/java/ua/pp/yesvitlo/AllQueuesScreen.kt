package ua.pp.yesvitlo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllQueuesScreen(
    regionName: String, date: String, day: Day?, groups: List<String>, selected: String?,
    tomorrow: Boolean, loading: Boolean, offline: Boolean, cached: Boolean,
    onDayChange: (Boolean) -> Unit, onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Усі черги регіону") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_queue_back), "Назад")
                }
            }
        )
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(regionName, style = MaterialTheme.typography.titleLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("Сьогодні", "Завтра").forEachIndexed { index, label ->
                                SegmentedButton(
                                    selected = tomorrow == (index == 1),
                                    onClick = { onDayChange(index == 1) },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                    label = { Text(label) }
                                )
                            }
                        }
                        Text("$date · Час київський", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                        if (cached && day != null) {
                            Text("Збережений графік · актуальність не підтверджено",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                if (day == null || day.groups.isEmpty()) {
                    item {
                        Text(when {
                            loading -> "Завантажуємо графік…"
                            offline -> "Немає зв’язку. На цю дату графік не збережено."
                            else -> "На цю дату графік ще недоступний."
                        }, Modifier.padding(vertical = 12.dp))
                    }
                } else {
                    items(groups, key = { it }) { queue ->
                        QueueOutages(queue, day.groups[queue], queue == selected)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueOutages(queue: String, intervals: List<Interval>?, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val outages = intervals.orEmpty().filter { it.power == Power.OFF }
    val incomplete = intervals.isNullOrEmpty() || intervals.any { it.power == Power.UNKNOWN }
    val total = if (incomplete) "—" else formatDuration(outages.sumOf { it.end - it.start })
    val outageShape = RoundedCornerShape(10.dp)
    Surface(
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        border = if (selected) BorderStroke(1.dp, colors.primary) else null,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(queue, Modifier.widthIn(min = 38.dp).padding(end = 8.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (outages.isEmpty()) {
                    Text(if (incomplete) "Немає повних даних" else "Без відключень",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                } else {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        outages.forEach { interval ->
                            Surface(color = colors.errorContainer, contentColor = colors.onErrorContainer,
                                shape = outageShape) {
                                Text("${clock(interval.start)}–${clock(interval.end)}",
                                    Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                val highlightTotal = !incomplete && outages.isNotEmpty()
                Surface(
                    modifier = Modifier.padding(start = 8.dp),
                    color = Color.Transparent,
                    shape = outageShape,
                    border = if (highlightTotal) BorderStroke(1.dp, colors.error) else null
                ) {
                    Text(total, Modifier.padding(horizontal = 9.dp, vertical = 7.dp).semantics {
                        contentDescription = if (incomplete) "Загальний час без світла невідомий" else "Загалом без світла: $total"
                    }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            if (selected) Text("Моя черга", style = MaterialTheme.typography.labelMedium)
            if (incomplete && outages.isNotEmpty()) {
                Text("Графік неповний", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
