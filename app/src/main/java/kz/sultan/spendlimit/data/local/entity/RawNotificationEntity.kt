package kz.sultan.spendlimit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Сырое уведомление, сохранённое целиком ДО парсинга.
 *
 * Храним оригинальный текст, чтобы при изменении формата пушей Kaspi
 * можно было перепарсить историю без потери данных.
 */
@Entity(
    tableName = "raw_notifications",
    indices = [Index("posted_at"), Index("synced"), Index("dedup_key")]
)
data class RawNotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    val title: String?,

    /** Оригинальный текст пуша (NotificationCompat EXTRA_TEXT / bigText). */
    val text: String,

    /** Время появления уведомления, epoch millis (UTC). */
    @ColumnInfo(name = "posted_at")
    val postedAt: Long,

    /** false — ещё не выгружено в Supabase. WorkManager чистит очередь после успешной выгрузки. */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false,

    /**
     * Сигнатура повторной доставки одного и того же пуша: `"${sbn.key}|${postTime}"`.
     * Система может вызвать onNotificationPosted несколько раз для одного уведомления
     * (двойной callback, реконнект листенера, перепост после ребута) — все они несут
     * одинаковые key+postTime. По этому полю отсекаем дубли (см. ingestNotification).
     * null — операция не из пуша (ручная/голос) или восстановленная из бэкапа: не дедупится.
     */
    @ColumnInfo(name = "dedup_key")
    val dedupKey: String? = null
)
