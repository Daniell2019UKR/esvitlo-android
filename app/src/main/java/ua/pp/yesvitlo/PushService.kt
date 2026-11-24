package ua.pp.yesvitlo

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.tasks.await
import androidx.work.*

fun pushConfigured(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

fun syncPush(context: Context) {
    if (!pushConfigured(context)) return
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val work = OneTimeWorkRequestBuilder<PushSubscriptionWorker>().setConstraints(constraints).build()
    val manager = WorkManager.getInstance(context)
    manager.enqueueUniqueWork("push-subscription", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    manager.cancelUniqueWork("address-watch-renewal")
}

class PushSubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!pushConfigured(applicationContext)) return Result.success()
        val prefs = applicationContext.getSharedPreferences("esvitlo", Context.MODE_PRIVATE)
        val desired = if (prefs.getBoolean(notificationSetting, false)) buildSet {
            prefs.places().filter { it.alerts }.forEach { place ->
                add("notice-${place.region}")
                place.queue?.let { add("schedule-${place.region}-$it") }
            }
            if (BuildConfig.GITHUB_UPDATES && prefs.notificationMode(NotificationType.APP_UPDATE) != NotificationMode.OFF) add(appUpdateTopic)
        } else emptySet()
        val subscribed = prefs.getStringSet("pushTopics", emptySet()).orEmpty().toMutableSet()
        val messaging = FirebaseMessaging.getInstance()
        return try {
            for (topic in subscribed - desired) {
                messaging.unsubscribeFromTopic(topic).await()
                subscribed.remove(topic)
                prefs.edit().putStringSet("pushTopics", subscribed.toSet()).apply()
            }
            for (topic in desired) {
                messaging.subscribeToTopic(topic).await()
                subscribed.add(topic)
                prefs.edit().putStringSet("pushTopics", subscribed.toSet()).apply()
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { syncPush(this) }

    override fun onMessageReceived(message: RemoteMessage) {
        val prefs = getSharedPreferences("esvitlo", MODE_PRIVATE)
        if (!prefs.getBoolean(notificationSetting, false)) return
        if (message.data["type"] == NotificationType.APP_UPDATE.key) {
            if (BuildConfig.GITHUB_UPDATES) message.data["tag"]?.let(::newerRelease)?.let { postAppUpdate(this, it) }
            return
        }
        val region = message.data["region"] ?: return
        val group = message.data["group"]
        val matching = prefs.places().filter { it.alerts && it.region == region && (group == null || group == it.queue) }
        if (matching.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val body = message.data["body"] ?: return
        val type = message.data["type"] ?: when {
            group != null && body.startsWith("Додано") -> "publication"
            group != null -> "schedule"
            body.startsWith("Оператор зняв") -> "clearance"
            else -> "mixed"
        }
        if ((type == "schedule" || type == "publication" || group == null) &&
            matching.any { prefs.hasReminders(it) }) syncBackground(this, refresh = true)
        val channel = manager.channelFor(prefs.notificationMode(type)) ?: return
        val open = PendingIntent.getActivity(this, (region + group).hashCode(), Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("place", matching.first().id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify("$region:$group:$type", 0, Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_svitlo_mark).setContentTitle(message.data["title"] ?: "єСвітло")
            .setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(open).build())
    }
}
