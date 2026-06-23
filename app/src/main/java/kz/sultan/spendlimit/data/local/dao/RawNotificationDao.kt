package kz.sultan.spendlimit.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity

@Dao
interface RawNotificationDao {

    @Insert
    suspend fun insert(raw: RawNotificationEntity): Long

    /** Сколько уведомлений с такой сигнатурой уже сохранено — для дедупа повторной доставки. */
    @Query("SELECT COUNT(*) FROM raw_notifications WHERE dedup_key = :key")
    suspend fun countByDedupKey(key: String): Int

    /** Полный снимок таблицы — для экспорта бэкапа. */
    @Query("SELECT * FROM raw_notifications")
    suspend fun getAll(): List<RawNotificationEntity>

    /** Массовая вставка при восстановлении бэкапа (вставлять ДО transactions из-за FK raw_id). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RawNotificationEntity>)

    @Query("SELECT * FROM raw_notifications WHERE synced = 0 ORDER BY posted_at ASC LIMIT :limit")
    suspend fun unsynced(limit: Int = 200): List<RawNotificationEntity>

    @Query("UPDATE raw_notifications SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** Для перепарсинга при смене формата пушей. */
    @Query("SELECT * FROM raw_notifications WHERE package_name = :pkg ORDER BY posted_at ASC")
    suspend fun allForPackage(pkg: String): List<RawNotificationEntity>
}
