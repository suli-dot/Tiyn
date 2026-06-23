package kz.sultan.spendlimit.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kz.sultan.spendlimit.data.local.entity.TransactionEntity

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    /** Полный снимок таблицы (включая удалённые) — для экспорта бэкапа. */
    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TransactionEntity>

    /** Массовая вставка при восстановлении бэкапа; REPLACE — id из файла перетирают совпадающие. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TransactionEntity>)

    /** Запись по id (включая удалённые) — для расчёта корректировки остатка. */
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: Long): TransactionEntity?

    /** Транзакции за период [fromMillis, toMillis). Flow — для реактивного главного экрана. */
    @Query(
        "SELECT * FROM transactions WHERE deleted_at IS NULL AND " +
            "created_at >= :fromMillis AND created_at < :toMillis " +
            "ORDER BY created_at DESC"
    )
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<TransactionEntity>>

    /** Все транзакции, новые сверху — для экрана «Записи». */
    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /** Сумма исходящих трат (тиыны) за период. NULL → 0. */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE deleted_at IS NULL AND type IN ('PURCHASE', 'TRANSFER') " +
            "AND created_at >= :fromMillis AND created_at < :toMillis"
    )
    fun observeOutgoingSum(fromMillis: Long, toMillis: Long): Flow<Long>

    /** Суммы исходящих трат по категориям за период (для сводки «по категориям»). */
    @Query(
        "SELECT category AS category, COALESCE(SUM(amount), 0) AS total FROM transactions " +
            "WHERE deleted_at IS NULL AND type IN ('PURCHASE', 'TRANSFER') " +
            "AND created_at >= :fromMillis AND created_at < :toMillis " +
            "GROUP BY category ORDER BY total DESC"
    )
    fun observeCategorySums(fromMillis: Long, toMillis: Long): Flow<List<CategorySum>>

    /** Сумма исходящих трат (тиыны) по одной категории за период — для проверки лимита категории. */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE deleted_at IS NULL AND type IN ('PURCHASE', 'TRANSFER') " +
            "AND category = :category AND created_at >= :fromMillis AND created_at < :toMillis"
    )
    suspend fun sumForCategory(category: String, fromMillis: Long, toMillis: Long): Long

    /** Одноразовая сумма исходящих трат за период (все категории) — для голосовых запросов «сколько потрачено». */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE deleted_at IS NULL AND type IN ('PURCHASE', 'TRANSFER') " +
            "AND created_at >= :fromMillis AND created_at < :toMillis"
    )
    suspend fun sumOutgoing(fromMillis: Long, toMillis: Long): Long

    /** Последняя не удалённая запись (самая свежая по created_at) — для голосовой правки «отмени/поправь последнее». */
    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL ORDER BY created_at DESC LIMIT 1")
    suspend fun findLast(): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE synced = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun unsynced(limit: Int = 200): List<TransactionEntity>

    @Query("UPDATE transactions SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /**
     * Soft delete: помечает запись удалённой и сбрасывает synced, чтобы факт
     * удаления уехал в облако при синхронизации. Физически строка остаётся.
     */
    @Query("UPDATE transactions SET deleted_at = :now, synced = 0, edited_at = :now WHERE id = :id")
    suspend fun deleteById(id: Long, now: Long)

    /**
     * Ручная правка ошибочно распознанной записи.
     * Ставит edited_at и сбрасывает synced=0, чтобы изменённая версия повторно ушла в Supabase.
     */
    @Query(
        "UPDATE transactions SET amount = :amount, type = :type, merchant = :merchant, " +
            "category = :category, edited_at = :editedAt, synced = 0 WHERE id = :id"
    )
    suspend fun updateEdited(
        id: Long,
        amount: Long,
        type: String,
        merchant: String?,
        category: String?,
        editedAt: Long
    )
}
