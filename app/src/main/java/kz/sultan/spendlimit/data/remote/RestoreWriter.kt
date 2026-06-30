package kz.sultan.spendlimit.data.remote

import androidx.room.withTransaction
import kz.sultan.spendlimit.data.local.AppDatabase
import kz.sultan.spendlimit.data.local.dao.RawNotificationDao
import kz.sultan.spendlimit.data.local.dao.TransactionDao
import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity
import kz.sultan.spendlimit.data.local.entity.TransactionEntity

/**
 * Атомарная запись восстановленного из облака архива в локальное хранилище.
 * Вынесено за интерфейс, чтобы [CloudRestore] не зависел от Room и проверялся
 * юнит-тестами без Android-окружения.
 */
interface RestoreWriter {
    /**
     * Заменяет строки (REPLACE по первичному ключу = client_id) в ОДНОЙ транзакции.
     * [raws] вставляются ПЕРЕД [txs] из-за внешнего ключа transactions.raw_id →
     * raw_notifications.id — иначе вставка транзакции упрётся в констрейнт.
     */
    suspend fun replaceAll(raws: List<RawNotificationEntity>, txs: List<TransactionEntity>)
}

/** Room-реализация: обе вставки в `db.withTransaction` — частичного восстановления при сбое не будет. */
class RoomRestoreWriter(
    private val db: AppDatabase,
    private val rawDao: RawNotificationDao,
    private val txDao: TransactionDao
) : RestoreWriter {
    override suspend fun replaceAll(raws: List<RawNotificationEntity>, txs: List<TransactionEntity>) {
        db.withTransaction {
            rawDao.insertAll(raws)
            txDao.insertAll(txs)
        }
    }
}
