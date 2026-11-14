package ua.pp.yesvitlo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val github = "https://github.com/Daniell2019UKR/esvitlo-android"
private const val maxApkSize = 100L * 1024 * 1024
internal data class AppUpdate(val version: String, val url: String, val sha256: String, val size: Long)

internal fun latestUpdate(): AppUpdate? {
    val release = request("https://api.github.com/repos/Daniell2019UKR/esvitlo-android/releases/latest") { connection ->
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }
    val tag = release.getString("tag_name")
    val version = tag.removePrefix("v")
    if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null
    val parts = Regex("v(\\d{1,5})\\.(\\d{1,5})\\.(\\d{1,5})").matchEntire(tag)?.groupValues?.drop(1)
        ?.map(String::toInt) ?: return null
    val installed = BuildConfig.VERSION_NAME.split('.').map(String::toInt)
    val difference = parts.zip(installed).firstOrNull { (latest, current) -> latest != current } ?: return null
    if (difference.first < difference.second) return null
    val url = "$github/releases/download/$tag/esvitlo-android-$version-debug.apk"
    val assets = release.getJSONArray("assets")
    val asset = (0 until assets.length()).map(assets::getJSONObject)
        .firstOrNull { it.optString("browser_download_url") == url } ?: error("APK unavailable")
    val digest = asset.optString("digest")
    require(digest.matches(Regex("sha256:[0-9a-f]{64}")))
    return AppUpdate(version, url, digest.removePrefix("sha256:"), asset.getLong("size").also { require(it in 1..maxApkSize) })
}

internal fun downloadUpdate(context: Context, update: AppUpdate): File {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    val file = File(dir, "esvitlo.apk")
    val pending = File.createTempFile("esvitlo-", ".part", dir)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        request(update.url) { connection ->
            connection.inputStream.use { input ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        require(received <= update.size)
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        require(received == update.size && digest.digest().joinToString("") { "%02x".format(it) } == update.sha256)
        verifyApk(context, pending, update)
        check(pending.renameTo(file))
        return file
    } finally {
        pending.delete()
    }
}

@Suppress("DEPRECATION")
private fun verifyApk(context: Context, file: File, update: AppUpdate) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = requireNotNull(pm.getPackageArchiveInfo(file.path, flags))
    val installed = pm.getPackageInfo(context.packageName, flags)
    require(archive.packageName == context.packageName && archive.versionName == update.version)
    if (Build.VERSION.SDK_INT >= 28) {
        require(archive.longVersionCode > installed.longVersionCode)
        require(archive.signingInfo?.apkContentsSigners.orEmpty().toSet().let {
            it.isNotEmpty() && it == installed.signingInfo?.apkContentsSigners.orEmpty().toSet()
        })
    } else {
        require(archive.versionCode > installed.versionCode)
        require(archive.signatures.orEmpty().toSet().let { it.isNotEmpty() && it == installed.signatures.orEmpty().toSet() })
    }
}

private fun <T> request(address: String, read: (HttpURLConnection) -> T): T {
    var url = URL(address)
    repeat(5) {
        require(url.protocol == "https" && url.port in listOf(-1, 443) && url.userInfo == null &&
            (url.host in setOf("api.github.com", "github.com") || url.host.endsWith(".githubusercontent.com")))
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Esvitlo-Android")
        try {
            val status = connection.responseCode
            if (status == 200) return read(connection)
            require(status in setOf(301, 302, 303, 307, 308))
            url = URL(url, requireNotNull(connection.getHeaderField("Location")))
        } finally {
            connection.disconnect()
        }
    }
    error("Too many redirects")
}

internal fun installUpdate(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
