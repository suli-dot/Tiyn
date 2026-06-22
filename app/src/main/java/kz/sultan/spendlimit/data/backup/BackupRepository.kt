package kz.sultan.spendlimit.data.backup

import androidx.room.withTransaction
import kz.sultan.spendlimit.data.local.AppDatabase
import kz.sultan.spendlimit.data.prefs.SettingsRepository

/**
 * Канал A — локальный файл резервной копии, независимый от облака.
 *
 * Работает со строками JSON: ввод/вывод в файл (content Uri) лежит на вызывающем слое
 * (ViewModel + ContentResolver), чтобы репозиторий не тащил Android-IO.
 *
 * Восстановление — merge по первичному ключу (REPLACE): строки из файла перетирают
 * совпадающие локальные, отсутствующие в файле локальные строки остаются нетронутыми.
 * Для чистого сценария (переустановка → пустая БД) это даёт точное воспроизведение.
 */
class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {

    private val rawDao = db.rawNotificationDao()
    private val txDao = db.transactionDao()
    private val ruleDao = db.merchantRuleDao()
    private val budgetDao = db.categoryBudgetDao()

    /** Собирает полный снимок всех данных и сериализует в JSON-строку. */
    suspend fun export(): String {
        val backup = BackupFile(
            exportedAt = System.currentTimeMillis(),
            transactions = txDao.getAll().map { it.toDto() },
            rawNotifications = rawDao.getAll().map { it.toDto() },
            merchantRules = ruleDao.all().map { it.toDto() },
            categoryBudgets = budgetDao.getAll().map { it.toDto() },
            settings = settings.exportSettings()
        )
        return BackupSerializer.encode(backup)
    }

    /**
     * Восстанавливает данные из JSON-строки.
     *
     * Порядок вставки важен: raw_notifications ПЕРЕД transactions из-за внешнего ключа
     * transactions.raw_id → raw_notifications.id. Всё в одной транзакции Room — частичного
     * восстановления при сбое не будет.
     *
     * @throws kotlinx.serialization.SerializationException если файл повреждён/не тот формат.
     * @return краткая сводка восстановленного.
     */
    suspend fun import(json: String): RestoreSummary {
        val backup = BackupSerializer.decode(json)

        db.withTransaction {
            rawDao.insertAll(backup.rawNotifications.map { it.toEntity() })
            txDao.insertAll(backup.transactions.map { it.toEntity() })
            ruleDao.insertAll(backup.merchantRules.map { it.toEntity() })
            budgetDao.insertAll(backup.categoryBudgets.map { it.toEntity() })
        }
        // Настройки в DataStore — отдельным хранилищем, вне Room-транзакции.
        settings.importSettings(backup.settings)

        return RestoreSummary(
            transactions = backup.transactions.size,
            rawNotifications = backup.rawNotifications.size,
            merchantRules = backup.merchantRules.size,
            categoryBudgets = backup.categoryBudgets.size
        )
    }
}

data class RestoreSummary(
    val transactions: Int,
    val rawNotifications: Int,
    val merchantRules: Int,
    val categoryBudgets: Int
)
