package ua.pp.yesvitlo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.SharedPreferences

internal enum class NotificationMode(val label: String) {
    SOUND("Зі звуком"), SILENT("Без звуку"), OFF("Вимкнено")
}

internal enum class NotificationType(val key: String, val label: String) {
    SCHEDULE("schedule", "Зміни графіка"),
    PUBLICATION("publication", "Новий графік на завтра"),
    UNPLANNED("unplanned", "Аварійні відключення"),
    EMERGENCY("emergency", "Екстрені відключення"),
    CLEARANCE("clearance", "Зняття попередження")
}

internal fun SharedPreferences.notificationMode(type: NotificationType): NotificationMode =
    NotificationMode.entries.find { it.name == getString("notificationMode:${type.key}", null) }
        ?: NotificationMode.SOUND

internal fun SharedPreferences.notificationMode(key: String): NotificationMode =
    if (key == "mixed") minOf(notificationMode(NotificationType.UNPLANNED), notificationMode(NotificationType.EMERGENCY))
    else NotificationType.entries.find { it.key == key }?.let { notificationMode(it) } ?: NotificationMode.OFF

internal fun NotificationManager.channelFor(mode: NotificationMode): String? {
    val channel = when (mode) {
        NotificationMode.SOUND -> NotificationChannel("schedule_updates", "Графіки та попередження", NotificationManager.IMPORTANCE_DEFAULT)
        NotificationMode.SILENT -> NotificationChannel("silent_updates", "Сповіщення без звуку", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }
        NotificationMode.OFF -> return null
    }
    createNotificationChannel(channel)
    return channel.id
}
