package app.projectsteps.data

import app.projectsteps.core.schedule.QuietHours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

object SettingKeys {
    const val SETUP_COMPLETED = "setup_completed"
    const val NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val DEFAULT_REMINDER_TIME = "default_reminder_time"
    const val SNOOZE_MINUTES = "snooze_minutes"
    const val NAG_MINUTES = "nag_minutes"
    const val QUIET_ENABLED = "quiet_enabled"
    const val QUIET_START = "quiet_start"
    const val QUIET_END = "quiet_end"
    const val LOCK_SCREEN_DETAILS = "lock_screen_details"
    const val THEME = "theme"
    const val AMOLED = "amoled"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val SHOW_COMPLETED = "show_completed"
    const val NEXT_INCLUDE_BLOCKED = "next_include_blocked"
    const val LAST_BACKUP_AT = "last_backup_at"
    const val BACKUP_REMINDER_SEEN = "backup_explained"

    /** Settings that travel with a backup (device-specific ones like theme stay on the device). */
    val PROJECT_RELEVANT = setOf(
        DEFAULT_REMINDER_TIME, SNOOZE_MINUTES, NAG_MINUTES, QUIET_ENABLED, QUIET_START, QUIET_END,
        LOCK_SCREEN_DETAILS, SHOW_COMPLETED, NEXT_INCLUDE_BLOCKED,
    )
}

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

data class AppSettings(
    val loaded: Boolean = false,
    val setupCompleted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val defaultReminderTime: LocalTime = LocalTime.of(9, 0),
    val snoozeMinutes: Int = 15,
    val nagMinutes: Int = 60,
    val quietHours: QuietHours = QuietHours(false, LocalTime.of(22, 0), LocalTime.of(7, 0)),
    val lockScreenDetails: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = true,
    val dynamicColor: Boolean = false,
    val showCompleted: Boolean = true,
    val nextIncludeBlocked: Boolean = false,
    val lastBackupAt: Long? = null,
) {
    companion object {
        fun from(map: Map<String, String>): AppSettings {
            fun bool(k: String, d: Boolean) = map[k]?.toBooleanStrictOrNull() ?: d
            fun int(k: String, d: Int) = map[k]?.toIntOrNull() ?: d
            fun time(k: String, d: LocalTime) = map[k]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d
            return AppSettings(
                loaded = true,
                setupCompleted = bool(SettingKeys.SETUP_COMPLETED, false),
                notificationsEnabled = bool(SettingKeys.NOTIFICATIONS_ENABLED, false),
                defaultReminderTime = time(SettingKeys.DEFAULT_REMINDER_TIME, LocalTime.of(9, 0)),
                snoozeMinutes = int(SettingKeys.SNOOZE_MINUTES, 15).coerceIn(1, 24 * 60),
                nagMinutes = int(SettingKeys.NAG_MINUTES, 60).coerceIn(5, 24 * 60),
                quietHours = QuietHours(
                    bool(SettingKeys.QUIET_ENABLED, false),
                    time(SettingKeys.QUIET_START, LocalTime.of(22, 0)),
                    time(SettingKeys.QUIET_END, LocalTime.of(7, 0)),
                ),
                lockScreenDetails = bool(SettingKeys.LOCK_SCREEN_DETAILS, false),
                theme = ThemeMode.entries.firstOrNull { it.name == map[SettingKeys.THEME] } ?: ThemeMode.SYSTEM,
                amoled = bool(SettingKeys.AMOLED, true),
                dynamicColor = bool(SettingKeys.DYNAMIC_COLOR, false),
                showCompleted = bool(SettingKeys.SHOW_COMPLETED, true),
                nextIncludeBlocked = bool(SettingKeys.NEXT_INCLUDE_BLOCKED, false),
                lastBackupAt = map[SettingKeys.LAST_BACKUP_AT]?.toLongOrNull(),
            )
        }
    }
}

class SettingsRepository(private val dao: SettingDao) {
    val settings: Flow<AppSettings> = dao.observeAll().map { list -> AppSettings.from(list.associate { it.key to it.value }) }

    suspend fun current(): AppSettings = AppSettings.from(dao.all().associate { it.key to it.value })

    suspend fun rawAll(): Map<String, String> = dao.all().associate { it.key to it.value }

    suspend fun set(key: String, value: String) = dao.upsert(SettingEntity(key, value))
    suspend fun set(key: String, value: Boolean) = set(key, value.toString())
    suspend fun set(key: String, value: Int) = set(key, value.toString())
    suspend fun set(key: String, value: LocalTime) = set(key, value.toString())
}
