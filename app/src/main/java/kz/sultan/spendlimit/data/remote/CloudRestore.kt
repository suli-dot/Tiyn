package kz.sultan.spendlimit.data.remote

import androidx.room.withTransaction
import kz.sultan.spendlimit.data.local.AppDatabase
import kz.sultan.spendlimit.data.local.dao.RawNotificationDao
import kz.sultan.spendlimit.data.local.dao.TransactionDao

/**
 * Восстановление локальной БД из облачного архива Supabase (канал Б, в дополнение к
 * файловому бэкапу [kz.sultan.spendlimit.data.backup.BackupRepository]).
 *
 * Стратегия — REPLACE по client_id (= локальному id): облачные строки перетирают
 * совпадающие локальные, отсутствующие в облаке локальные строки не трогаются. Для
 * чистого сценария (переустановка → вход → восстановление) это точное воспроизведение.
 *
 * Порядок вставки важен: raw_notifications ПЕРЕД transactions из-за внешнего ключа
 * transactions.raw_id → raw_notifications.id. Обе вставки в одной Room-транзакции —
 * частичного восстановления при сбое не будет.
 */
class CloudRestore(
    private val db: AppDatabase,
    private val remote: RemoteSyncSource,
    private val rawDao: RawNotificationDao,
    private val txDao: TransactionDao
) {

    data class Result(val rawNotifications: Int, val transactions: Int)

    /** @throws IllegalStateException если нет активной сессии (RLS не отдаст чужие/анонимные данные). */
    suspend fun restore(): Result {
        check(remote.isAuthenticated()) {
            "Нужно войти в аккаунт — восстановление из облака недоступно"
        }
        val raws = remote.pullRawNotifications()
        val txs = remote.pullTransactions()
        db.withTransaction {
            rawDao.insertAll(raws)
            txDao.insertAll(txs)
        }
        return Result(rawNotifications = raws.size, transactions = txs.size)
    }
}
