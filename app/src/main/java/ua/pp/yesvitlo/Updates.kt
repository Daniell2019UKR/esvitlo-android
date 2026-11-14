package ua.pp.yesvitlo

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val github = "https://github.com/Daniell2019UKR/esvitlo-android"
internal data class AppUpdate(val version: String, val url: String)

internal fun latestUpdate(): AppUpdate? {
    val connection = URL("https://api.github.com/repos/Daniell2019UKR/esvitlo-android/releases/latest")
        .openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", "Esvitlo-Android")
    val release = try {
        check(connection.responseCode == HTTP_OK)
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }

    if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null
    val tag = release.getString("tag_name")
    val parts = Regex("v(\\d{1,5})\\.(\\d{1,5})\\.(\\d{1,5})").matchEntire(tag)?.groupValues?.drop(1)
        ?.map(String::toInt) ?: return null
    val installed = BuildConfig.VERSION_NAME.split('.').map(String::toInt)
    val difference = parts.zip(installed).firstOrNull { (latest, current) -> latest != current } ?: return null
    if (difference.first < difference.second) return null
    return AppUpdate(tag.removePrefix("v"), "$github/releases/tag/$tag")
}

private const val HTTP_OK = HttpURLConnection.HTTP_OK
