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
    CLEARANCE("clearance", "Зняття попередження"),
    REMINDER("reminder", "Нагадування до відключення"),
    RESTORATION("restoration", "Нагадування до відновлення"),
    APP_UPDATE("app-update", "Оновлення застосунку")
}

internal fun SharedPreferences.notificationMode(type: NotificationType): NotificationMode =
    NotificationMode.entries.find { it.name == getString("notificationMode:${type.key}", null) }
        ?: NotificationMode.SOUND

internal fun SharedPreferences.notificationMode(key: String): NotificationMode =
    if (key == "mixed") minOf(notificationMode(NotificationType.UNPLANNED), notificationMode(NotificationType.EMERGENCY))
    else NotificationType.entries.find { it.key == key }?.let { notificationMode(it) } ?: NotificationMode.OFF

internal fun NotificationManager.channelFor(mode: NotificationMode, appUpdate: Boolean = false): String? {
    val prefix = if (appUpdate) "app_updates_" else ""
    val channel = when (mode) {
        NotificationMode.SOUND -> NotificationChannel(prefix + "schedule_updates",
            if (appUpdate) "Оновлення застосунку" else "Графіки та попередження", NotificationManager.IMPORTANCE_DEFAULT)
        NotificationMode.SILENT -> NotificationChannel(prefix + "silent_updates",
            if (appUpdate) "Оновлення застосунку без звуку" else "Сповіщення без звуку", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }
        NotificationMode.OFF -> return null
    }
    createNotificationChannel(channel)
    return channel.id
}
