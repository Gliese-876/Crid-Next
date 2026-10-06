package cn.crid.next.platform

import android.content.Context
import android.os.UserManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneId

/** Access is serialized by ReminderScheduler; never opens credential-encrypted storage while locked. */
internal object ReminderDeviceStore {
    internal const val STORE = "course-reminders-device-v1"
    internal const val LEGACY_STORE = "course-reminder-registration-v2"
    private const val SNAPSHOT = "snapshot"
    private const val REGISTERED = "registered"
    private const val DELIVERED = "delivered"
    private val json = Json { encodeDefaults = true }
    internal fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun snapshot(context: Context): ReminderSnapshot = runCatching {
        val encoded = preferences(context).getString(SNAPSHOT, null) ?: return@runCatching ReminderSnapshot()
        val snapshot = json.decodeFromString<ReminderSnapshot>(encoded)
        val zone = ZoneId.systemDefault()
        snapshot.copy(notices = snapshot.notices.filter { notice ->
            runCatching { notice.id.isNotBlank() && notice.advanceMinutes in 0..1440 && notice.start(zone) < notice.end(zone) }
                .getOrDefault(false)
        })
    }.getOrDefault(ReminderSnapshot())

    fun save(context: Context, snapshot: ReminderSnapshot) {
        check(preferences(context).edit().putString(SNAPSHOT, json.encodeToString(snapshot)).commit())
    }

    fun invalidate(context: Context) {
        // Keep the alarm index until the next refresh can cancel its old PendingIntents.
        check(preferences(context).edit().remove(SNAPSHOT).commit())
    }

    fun registered(context: Context): Set<Long> = preferences(context).getStringSet(REGISTERED, emptySet())
        .orEmpty().mapNotNull(String::toLongOrNull).toSet()

    fun setRegistered(context: Context, dues: Set<Long>) {
        preferences(context).edit().putStringSet(REGISTERED, dues.map(Long::toString).toSet()).commit()
    }

    fun delivered(context: Context): Set<String> = preferences(context).getStringSet(DELIVERED, emptySet()).orEmpty().toSet()

    fun setDelivered(context: Context, ids: Set<String>) {
        preferences(context).edit().putStringSet(DELIVERED, ids).commit()
    }

    fun migrateUnlocked(context: Context) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val legacy = context.getSharedPreferences(LEGACY_STORE, Context.MODE_PRIVATE)
        val oldIds = legacy.getStringSet(DELIVERED, emptySet()).orEmpty()
        val oldDues = legacy.getStringSet(REGISTERED, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()
        if (oldIds.isEmpty() && oldDues.isEmpty()) return
        val saved = preferences(context).edit()
            .putStringSet(DELIVERED, delivered(context) + oldIds)
            .putStringSet(REGISTERED, (registered(context) + oldDues).map(Long::toString).toSet()).commit()
        if (saved) legacy.edit().remove(DELIVERED).remove(REGISTERED).commit()
    }
}
