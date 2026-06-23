package kz.sultan.spendlimit.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kz.sultan.spendlimit.data.local.AppDatabase
import kz.sultan.spendlimit.data.local.dao.CategoryBudgetDao
import kz.sultan.spendlimit.data.local.dao.CategorySum
import kz.sultan.spendlimit.data.local.dao.RawNotificationDao
import kz.sultan.spendlimit.data.local.dao.TransactionDao
import kz.sultan.spendlimit.data.local.entity.CategoryBudgetEntity
import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity
import kz.sultan.spendlimit.data.local.entity.TransactionEntity
import kz.sultan.spendlimit.data.prefs.SettingsRepository
import kz.sultan.spendlimit.domain.BalanceEffect
import kz.sultan.spendlimit.domain.BudgetPeriod
import kz.sultan.spendlimit.domain.category.Categories
import kz.sultan.spendlimit.domain.category.Categorizer
import kz.sultan.spendlimit.domain.model.ParsedTransaction
import kz.sultan.spendlimit.domain.model.Transaction
import kz.sultan.spendlimit.domain.model.TransactionType
import kz.sultan.spendlimit.util.Time
import java.time.Instant
import java.time.ZoneId

/**
 * Локальная реализация поверх Room. Слой синхронизации (Supabase) подключается
 * отдельно через [kz.sultan.spendlimit.work.SyncWorker] и не меняет этот контракт.
 *
 * Остаток (в [SettingsRepository]/DataStore) двигается автоматически при каждой
 * операции через единый путь в этом классе. Room-часть (чтение+вставка/правка/
 * удаление) выполняется в одной транзакции БД; сдвиг остатка применяется один раз
 * сразу после неё атомарным delta. Остаток вне Room (DataStore), поэтому общая
 * Room-транзакция его не охватывает — единый путь и atomic-delta защищают от двойного счёта.
 */
class FinanceRepositoryImpl(
    private val db: AppDatabase,
    private val rawDao: RawNotificationDao,
    private val txDao: TransactionDao,
    private val budgetDao: CategoryBudgetDao,
    private val settings: SettingsRepository,
    private val categorizer: Categorizer
) : FinanceRepository {

    override suspend fun ingestNotification(
        packageName: String,
        title: String?,
        text: String,
        postedAt: Long,
        parsed: ParsedTransaction?
    ): Transaction? {
        // Сырое уведомление сохраняем всегда — даже если распарсить не вышло.
        val rawId = rawDao.insert(
            RawNotificationEntity(
                packageName = packageName,
                title = title,
                text = text,
                postedAt = postedAt
            )
        )

        if (parsed == null) return null

        val category = categorizer.categorize(parsed.merchant, parsed.type)
        val entity = TransactionEntity(
            rawId = rawId,
            amount = parsed.amountTiyn,
            type = parsed.type.name,
            merchant = parsed.merchant,
            category = category,
            currency = parsed.currency,
            createdAt = postedAt
        )
        val txId = db.withTransaction { txDao.insert(entity) }
        settings.applyBalanceDelta(BalanceEffect.ofNew(parsed.type, parsed.amountTiyn))
        return entity.copy(id = txId).toDomain()
    }

    override suspend fun addManualTransaction(
        amountTiyn: Long,
        type: TransactionType,
        merchant: String?,
        category: String?,
        createdAt: Long
    ): Transaction {
        val cleanMerchant = merchant?.ifBlank { null }
        // Категория не задана явно — пробуем определить категоризатором по мерчанту.
        val finalCategory = category ?: categorizer.categorize(cleanMerchant, type)
        val entity = TransactionEntity(
            rawId = null,
            amount = amountTiyn,
            type = type.name,
            merchant = cleanMerchant,
            category = finalCategory,
            createdAt = createdAt
        )
        val id = db.withTransaction { txDao.insert(entity) }
        // Ручная операция влияет на остаток так же, как операция из пуша.
        settings.applyBalanceDelta(BalanceEffect.ofNew(type, amountTiyn))
        return entity.copy(id = id).toDomain()
    }

    override suspend fun deleteTransaction(id: Long) {
        // Возврат суммы в остаток считаем по записи ДО удаления, в той же транзакции БД.
        // Повторное удаление уже удалённой записи остаток не трогает (защита от двойного возврата).
        val refund = db.withTransaction {
            val tx = txDao.findById(id) ?: return@withTransaction 0L
            if (tx.deletedAt != null) return@withTransaction 0L
            txDao.deleteById(id, System.currentTimeMillis())
            BalanceEffect.ofDelete(TransactionType.fromName(tx.type), tx.amount)
        }
        settings.applyBalanceDelta(refund)
    }

    override suspend fun updateTransaction(
        id: Long,
        amountTiyn: Long,
        type: TransactionType,
        merchant: String?,
        category: String?
    ) {
        val cleanMerchant = merchant?.ifBlank { null }
        // Разницу для остатка считаем по старым значениям, прочитанным в той же транзакции БД.
        val delta = db.withTransaction {
            val old = txDao.findById(id) ?: return@withTransaction 0L
            txDao.updateEdited(
                id = id,
                amount = amountTiyn,
                type = type.name,
                merchant = cleanMerchant,
                category = category,
                editedAt = System.currentTimeMillis()
            )
            BalanceEffect.ofEdit(
                oldType = TransactionType.fromName(old.type),
                oldAmountTiyn = old.amount,
                newType = type,
                newAmountTiyn = amountTiyn
            )
        }
        settings.applyBalanceDelta(delta)
        // Ручной выбор категории запоминаем как правило — впредь этот мерчант
        // категоризуется автоматически.
        if (category != null) {
            categorizer.rememberUserChoice(cleanMerchant, category)
        }
    }

    override suspend fun spentBetween(fromMillis: Long, toMillis: Long, categorySlug: String?): Long =
        if (categorySlug == null) txDao.sumOutgoing(fromMillis, toMillis)
        else txDao.sumForCategory(categorySlug, fromMillis, toMillis)

    override suspend fun lastTransaction(): Transaction? = txDao.findLast()?.toDomain()

    override fun observeTodayOutgoingSum(): Flow<Long> =
        txDao.observeOutgoingSum(Time.startOfTodayMillis(), Time.startOfTomorrowMillis())

    override fun observeTodayCategorySums(): Flow<List<CategorySum>> =
        txDao.observeCategorySums(Time.startOfTodayMillis(), Time.startOfTomorrowMillis())

    override fun observeTodayTransactions(): Flow<List<Transaction>> =
        txDao.observeBetween(Time.startOfTodayMillis(), Time.startOfTomorrowMillis())
            .map { list -> list.map { it.toDomain() } }

    override fun observeAllTransactions(): Flow<List<Transaction>> =
        txDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeCategorySums(fromMillis: Long, toMillis: Long): Flow<List<CategorySum>> =
        txDao.observeCategorySums(fromMillis, toMillis)

    override fun observeOutgoingByDay(fromMillis: Long, toMillis: Long): Flow<List<DayTotal>> =
        txDao.observeBetween(fromMillis, toMillis).map { list ->
            // Группируем в Kotlin по локальной дате: корректнее, чем арифметика по millis в SQL (DST).
            list.asSequence()
                .filter { TransactionType.fromName(it.type).isOutgoing }
                .groupBy {
                    Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                .map { (date, items) -> DayTotal(date, items.sumOf { it.amount }) }
                .sortedBy { it.date }
        }

    override fun observeSpentByCategory(period: BudgetPeriod): Flow<Map<String, Long>> {
        val (from, to) = period.range()
        return txDao.observeCategorySums(from, to).map { list ->
            list.associate { (it.category ?: Categories.UNCATEGORIZED.slug) to it.total }
        }
    }

    override fun observeCategoryLimits(): Flow<Map<String, CategoryLimits>> =
        budgetDao.observeAll().map { list -> list.associate { it.category to it.toLimits() } }

    override suspend fun setCategoryBudget(categorySlug: String, period: BudgetPeriod, limitTiyn: Long) {
        val current = budgetDao.find(categorySlug)?.toLimits() ?: CategoryLimits()
        budgetDao.upsert(current.withPeriod(period, limitTiyn).toEntity(categorySlug))
    }

    override suspend fun removeCategoryBudget(categorySlug: String, period: BudgetPeriod) {
        val current = budgetDao.find(categorySlug)?.toLimits() ?: return
        val updated = current.withPeriod(period, null)
        // Лимитов не осталось — убираем строку целиком.
        if (updated.isEmpty) budgetDao.delete(categorySlug)
        else budgetDao.upsert(updated.toEntity(categorySlug))
    }

    override suspend fun categoryBudgetStatus(categorySlug: String): CategoryBudgetStatus? {
        val limits = budgetDao.find(categorySlug)?.toLimits() ?: return null
        if (limits.isEmpty) return null
        val periods = BudgetPeriod.entries.map { p ->
            val limit = limits.forPeriod(p)
            val spent = if (limit == null) 0L else {
                val (from, to) = p.range()
                txDao.sumForCategory(categorySlug, from, to)
            }
            CategoryPeriodStatus(p, spent, limit)
        }
        return CategoryBudgetStatus(categorySlug, periods)
    }
}

private fun CategoryBudgetEntity.toLimits() = CategoryLimits(
    day = limitDayTiyn,
    week = limitWeekTiyn,
    month = limitMonthTiyn
)

private fun CategoryLimits.toEntity(categorySlug: String) = CategoryBudgetEntity(
    category = categorySlug,
    limitDayTiyn = day,
    limitWeekTiyn = week,
    limitMonthTiyn = month,
    updatedAt = System.currentTimeMillis()
)

private fun TransactionEntity.toDomain() = Transaction(
    id = id,
    amountTiyn = amount,
    type = TransactionType.fromName(type),
    merchant = merchant,
    category = category,
    createdAt = createdAt,
    currency = currency,
    editedAt = editedAt,
    isManual = rawId == null
)
