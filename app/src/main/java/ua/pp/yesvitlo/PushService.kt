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
    val work = OneTimeWorkRequestBuilder<PushSubscriptionWorker>().setConstraints(
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    WorkManager.getInstance(context).enqueueUniqueWork("push-subscription", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
}

class PushSubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!pushConfigured(applicationContext)) return Result.success()
        val prefs = applicationContext.getSharedPreferences("esvitlo", Context.MODE_PRIVATE)
        val region = prefs.getString("region", "o") ?: "o"
        val group = prefs.getString("queue:$region", null)
        val desired = if (prefs.getBoolean(notificationSetting, false)) setOfNotNull(
            "notice-$region", group?.let { "schedule-$region-$it" }) else emptySet()
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
        val region = prefs.getString("region", "o")
        if (message.data["region"] != region) return
        val group = message.data["group"]
        if (group != null && group != prefs.getString("queue:$region", null)) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val channel = "schedule_updates"
        manager.createNotificationChannel(NotificationChannel(channel, "Графіки та попередження", NotificationManager.IMPORTANCE_DEFAULT))
        val body = message.data["body"] ?: return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify((message.data["region"] + group).hashCode(), Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_svitlo_mark).setContentTitle(message.data["title"] ?: "єСвітло")
            .setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(open).build())
    }
}
