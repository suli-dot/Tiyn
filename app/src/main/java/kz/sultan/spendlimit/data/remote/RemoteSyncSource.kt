package kz.sultan.spendlimit.data.remote

import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity
import kz.sultan.spendlimit.data.local.entity.TransactionEntity

/**
 * Контракт выгрузки локальной очереди в облако. Реализация — Supabase.
 * Вынесено в интерфейс, чтобы [kz.sultan.spendlimit.work.SyncWorker]
 * не зависел от конкретного бэкенда.
 */
interface RemoteSyncSource {

    /** Есть ли активная сессия (юзер вошёл). Без неё RLS отклонит запись — синк ждёт входа. */
    suspend fun isAuthenticated(): Boolean

    /** @return id успешно выгруженных сырых уведомлений (их пометим synced локально). */
    suspend fun pushRawNotifications(items: List<RawNotificationEntity>): List<Long>

    /** @return id успешно выгруженных транзакций. */
    suspend fun pushTransactions(items: List<TransactionEntity>): List<Long>

    /**
     * Полная выгрузка облачного архива в локальные сущности — для восстановления
     * на новом устройстве. RLS отдаёт только строки текущего пользователя.
     * @return сырые уведомления (вставлять ПЕРЕД транзакциями из-за FK raw_id).
     */
    suspend fun pullRawNotifications(): List<RawNotificationEntity>

    /** Полная выгрузка транзакций пользователя из облака (включая soft-deleted). */
    suspend fun pullTransactions(): List<TransactionEntity>
}
