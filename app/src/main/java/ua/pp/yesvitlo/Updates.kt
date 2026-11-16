package ua.pp.yesvitlo

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

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
    return newerRelease(release.getString("tag_name"))
}

internal fun newerRelease(tag: String, current: String = BuildConfig.VERSION_NAME): AppUpdate? {
    val parts = Regex("v(\\d{1,5})\\.(\\d{1,5})\\.(\\d{1,5})").matchEntire(tag)?.groupValues?.drop(1)
        ?.map(String::toInt) ?: return null
    val installed = current.split('.').map(String::toInt)
    val difference = parts.zip(installed).firstOrNull { (latest, current) -> latest != current } ?: return null
    if (difference.first < difference.second) return null
    return AppUpdate(tag.removePrefix("v"), "$github/releases/tag/$tag")
}

private const val HTTP_OK = HttpURLConnection.HTTP_OK

internal const val appUpdateTopic = "app-updates"

internal fun syncUpdateChecks(context: Context) {
    val work = PeriodicWorkRequestBuilder<AppUpdateWorker>(6, TimeUnit.HOURS)
        .setInitialDelay(6, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("app-update-check", ExistingPeriodicWorkPolicy.KEEP, work)
}

class AppUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        latestUpdate()?.let { postAppUpdate(applicationContext, it) }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

@Synchronized
internal fun postAppUpdate(context: Context, update: AppUpdate) {
    val prefs = context.getSharedPreferences("esvitlo", Context.MODE_PRIVATE)
    if (!prefs.getBoolean(notificationSetting, false)) return
    val announced = prefs.getString("announcedAppUpdate", null)
    if (announced != null && newerRelease("v${update.version}", announced) == null) return
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    val channel = manager.channelFor(prefs.notificationMode(NotificationType.APP_UPDATE), appUpdate = true) ?: return
    if (manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) return
    val open = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, Uri.parse(update.url)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    manager.notify("app-update", 0, Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_svitlo_mark)
        .setContentTitle("Доступне оновлення єСвітло ${update.version}")
        .setContentText("Відкрити сторінку релізу на GitHub")
        .setContentIntent(open).setAutoCancel(true).build())
    prefs.edit().putString("announcedAppUpdate", update.version).apply()
}
