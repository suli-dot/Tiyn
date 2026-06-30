package kz.sultan.spendlimit.data.remote

/**
 * Восстановление локальной БД из облачного архива Supabase (канал Б, в дополнение к
 * файловому бэкапу [kz.sultan.spendlimit.data.backup.BackupRepository]).
 *
 * Стратегия — REPLACE по client_id (= локальному id): облачные строки перетирают
 * совпадающие локальные, отсутствующие в облаке локальные строки не трогаются. Для
 * чистого сценария (переустановка → вход → восстановление) это точное воспроизведение.
 *
 * Сам аккуратный (атомарный, raw перед tx) способ записи скрыт за [RestoreWriter],
 * поэтому здесь чистая оркестрация без зависимости от Room.
 */
class CloudRestore(
    private val remote: RemoteSyncSource,
    private val writer: RestoreWriter
) {

    data class Result(val rawNotifications: Int, val transactions: Int)

    /** @throws IllegalStateException если нет активной сессии (RLS не отдаст чужие/анонимные данные). */
    suspend fun restore(): Result {
        check(remote.isAuthenticated()) {
            "Нужно войти в аккаунт — восстановление из облака недоступно"
        }
        val raws = remote.pullRawNotifications()
        val txs = remote.pullTransactions()
        writer.replaceAll(raws, txs)
        return Result(rawNotifications = raws.size, transactions = txs.size)
    }
}
