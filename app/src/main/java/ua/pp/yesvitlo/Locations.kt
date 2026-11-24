package ua.pp.yesvitlo

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class Place(val id: String, val name: String, val region: String, val queue: String?, val alerts: Boolean,
                          val outageMinutes: List<Int>? = null, val restorationMinutes: List<Int>? = null,
                          val address: PlaceAddress? = null)

internal data class PlaceAddress(val city: String = "", val street: String = "", val house: String = "") {
    val complete get() = city.isNotBlank() && street.isNotBlank() && house.isNotBlank()
    val empty get() = city.isBlank() && street.isBlank() && house.isBlank()
    fun trimmed() = PlaceAddress(city.trim(), street.trim(), house.trim())
    fun json() = JSONObject().put("city", city).put("street", street).put("house", house)
    override fun toString() = listOf(city, street, house).filter(String::isNotBlank).joinToString(", ")
}

internal fun JSONObject.placeAddress() = PlaceAddress(optString("city"), optString("street"), optString("house"))

internal fun SharedPreferences.places(): List<Place> {
    val raw = getString("places", null)
    if (raw == null) {
        val region = getString("region", "o") ?: "o"
        return listOf(Place("home", "Дім", region, getString("queue:$region", null), getBoolean(notificationSetting, false)))
    }
    return runCatching {
        val array = JSONArray(raw)
        List(array.length()) {
            val item = array.getJSONObject(it)
            Place(item.getString("id"), item.getString("name"), item.getString("region"),
                item.optString("queue").takeIf { value -> value.isNotBlank() }, item.getBoolean("alerts"),
                item.optString("outageMinutes", "").takeIf { it.isNotEmpty() }?.let(::parseReminderMinutes)
                    ?: item.optInt("reminderMinutes", -1).takeIf { it in 0..180 }?.let { parseReminderMinutes(it.toString()) },
                item.optString("restorationMinutes", "").takeIf { it.isNotEmpty() }?.let(::parseReminderMinutes),
                item.optJSONObject("address")?.placeAddress())
        }
    }.getOrDefault(emptyList())
}

internal fun SharedPreferences.savePlaces(places: List<Place>) {
    val array = JSONArray()
    for (place in places) array.put(JSONObject().put("id", place.id).put("name", place.name)
        .put("region", place.region).put("queue", place.queue.orEmpty()).put("alerts", place.alerts)
        .put("outageMinutes", place.outageMinutes?.reminderSettingText())
        .put("restorationMinutes", place.restorationMinutes?.reminderSettingText())
        .put("address", place.address?.json()))
    edit().putString("places", array.toString()).apply()
}

internal fun SharedPreferences.widgetPlace(id: Int): Place? {
    val places = places()
    return places.find { it.id == getString("widgetPlace:$id", null) } ?: places.firstOrNull()
}
