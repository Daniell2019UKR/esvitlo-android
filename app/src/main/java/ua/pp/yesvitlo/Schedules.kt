package ua.pp.yesvitlo

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val kyiv: ZoneId = runCatching { ZoneId.of("Europe/Kyiv") }.getOrElse { ZoneId.of("Europe/Kiev") }
data class Region(val code: String, val name: String, val source: String?)
enum class Power { ON, OFF, UNKNOWN }
data class Interval(val start: Int, val end: Int, val power: Power)
data class Day(val date: LocalDate, val groups: Map<String, List<Interval>>)
data class Schedule(
    val days: List<Day>, val updated: String?, val stale: Boolean,
    val retained: Set<String>, val notice: String?, val noticeStale: Boolean,
    val noticeKnown: Boolean = false
) {
    val groups: List<String> get() = days.flatMap { it.groups.keys }.distinct().sortedWith(
        compareBy({ it.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE },
            { it.substringAfter('.').toIntOrNull() ?: Int.MAX_VALUE }, { it })
    )
}
data class Download(val schedule: Schedule, val offline: Boolean)

class ScheduleRepository(private val prefs: SharedPreferences) {
    private fun request(path: String): Pair<Int, JSONObject> {
        val connection = URL("https://yesvitlo.pp.ua/api/v1/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        return try {
            val status = connection.responseCode
            if (status != 200 && status != 503) throw IOException("HTTP $status")
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            status to JSONObject((stream ?: throw IOException("Empty response")).bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun regions(): List<Region> = try {
        val (status, json) = request("regions")
        if (status != 200) throw IOException("Catalog unavailable")
        parseRegions(json).also { prefs.edit().putString("catalog", json.toString()).apply() }
    } catch (_: Exception) {
        runCatching { parseRegions(JSONObject(prefs.getString("catalog", null) ?: "{}")) }.getOrDefault(emptyList())
    }

    fun cachedSchedule(region: String): Schedule? = prefs.getString("schedule:$region", null)?.let {
        runCatching { parseSchedule(JSONObject(it)) }.getOrNull()
    }

    fun cachedRegions() = runCatching { parseRegions(JSONObject(prefs.getString("catalog", null) ?: "{}")) }.getOrDefault(emptyList())

    fun schedule(region: String): Download {
        require(region.matches(Regex("[a-z][a-z0-9-]{0,47}")))
        val key = "schedule:$region"
        return try {
            val (status, json) = request("schedules?region=$region")
            require(json.getString("region") == region)
            val schedule = parseSchedule(json)
            if (status == 200) {
                prefs.edit().putString(key, json.toString()).apply()
                Download(schedule, false)
            } else {
                val saved = cachedSchedule(region)
                Download(schedule.copy(days = saved?.days.orEmpty(), updated = saved?.updated, stale = true), false)
            }
        } catch (_: Exception) {
            Download(cachedSchedule(region) ?: Schedule(emptyList(), null, true, emptySet(), null, true), true)
        }
    }
}

private fun JSONArray.items() = (0 until length()).map { get(it) }
private fun JSONObject.string(key: String) = if (isNull(key)) null else getString(key)
private fun parseRegions(json: JSONObject) = json.getJSONArray("regions").items().map {
    it as JSONObject
    Region(it.getString("code"), it.getString("name"), it.string("source"))
}.filter { it.code.matches(Regex("[a-z][a-z0-9-]{0,47}")) }

fun parseSchedule(json: JSONObject): Schedule {
    val days = json.optJSONArray("days")?.items().orEmpty().map { raw ->
        val day = raw as JSONObject
        val groups = buildMap<String, List<Interval>> {
            day.getJSONArray("schedules").items().forEach { value ->
                val profile = value as JSONObject
                val slots = Array(48) { Power.ON }
                fun mark(key: String, power: Power) {
                    profile.optJSONArray(key)?.items()?.forEach {
                        val range = it as JSONArray
                        val start = minutes(range.getString(0))
                        val end = minutes(range.getString(1))
                        require(start in 0..1410 && end in 30..1440 && start < end && start % 30 == 0 && end % 30 == 0)
                        slots.fill(power, start / 30, end / 30)
                    }
                }
                mark("off", Power.OFF)
                mark("unknown", Power.UNKNOWN)
                val intervals = buildList {
                    var start = 0
                    for (end in 1..48) {
                        if (end == 48 || slots[end] != slots[start]) {
                            add(Interval(start * 30, end * 30, slots[start]))
                            start = end
                        }
                    }
                }
                profile.getJSONArray("groups").items().forEach { put(it as String, intervals) }
            }
        }
        Day(LocalDate.parse(day.getString("date")), groups)
    }
    val alert = json.optJSONObject("emergency")
    return Schedule(days, json.string("generatedAt"), json.optBoolean("stale"),
        json.optJSONArray("retainedDates")?.items().orEmpty().map { it.toString() }.toSet(),
        alert?.takeIf { it.optBoolean("active") }?.let {
            it.string("message") ?: "Оператор повідомив про відключення."
        }, alert?.optBoolean("stale") == true, alert?.opt("active") is Boolean)
}

private fun minutes(value: String): Int {
    if (value == "24:00") return 1440
    val time = LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"))
    return time.hour * 60 + time.minute
}
