package kz.sultan.spendlimit.data.repository

import kotlinx.coroutines.flow.Flow
import kz.sultan.spendlimit.data.local.dao.CategorySum
import kz.sultan.spendlimit.domain.model.ParsedTransaction
import kz.sultan.spendlimit.domain.model.Transaction
import kz.sultan.spendlimit.domain.model.TransactionType
import java.time.LocalDate

/** Сумма исходящих трат за один день — точка тренда для графика. */
data class DayTotal(val date: LocalDate, val totalTiyn: Long)

/**
 * Состояние месячного бюджета по категории: сколько потрачено и каков лимит.
 * [limitTiyn] = null означает, что лимит на категорию не задан.
 */
data class CategoryBudgetStatus(
    val categorySlug: String,
    val spentTiyn: Long,
    val limitTiyn: Long?
) {
    val hasLimit: Boolean get() = limitTiyn != null
    val remainingTiyn: Long get() = (limitTiyn ?: 0L) - spentTiyn
    val isExceeded: Boolean get() = limitTiyn != null && spentTiyn > limitTiyn
    /** Доля израсходованного лимита (0..N). Без лимита — 0. */
    val fraction: Float
        get() = if (limitTiyn != null && limitTiyn > 0L) spentTiyn.toFloat() / limitTiyn else 0f
}

/**
 * Абстракция слоя данных. Реализация ([FinanceRepositoryImpl]) скрывает,
 * откуда берутся данные (Room сейчас, Room+Supabase позже) — источник взаимозаменяем.
 */
interface FinanceRepository {

    /**
     * Сохраняет сырое уведомление и, если оно распозналось как финансовое,
     * создаёт транзакцию.
     *
     * @return созданная транзакция или null, если уведомление не финансовое.
     */
    suspend fun ingestNotification(
        packageName: String,
        title: String?,
        text: String,
        postedAt: Long,
        parsed: ParsedTransaction?
    ): Transaction?

    /**
     * Добавляет операцию вручную (raw_id = NULL): исходящие переводы, которые
     * Kaspi не пушит, и траты наличными. Если категория не задана — определяется
     * категоризатором по мерчанту.
     */
    suspend fun addManualTransaction(
        amountTiyn: Long,
        type: TransactionType,
        merchant: String?,
        category: String?,
        createdAt: Long
    ): Transaction

    /** Удаляет ошибочно распознанную транзакцию. Сырое уведомление сохраняется. */
    suspend fun deleteTransaction(id: Long)

    /**
     * Правит ошибочно распознанную транзакцию (сумма/тип/мерчант).
     * Помечает запись отредактированной и снимает флаг synced для повторной выгрузки.
     * Сырое уведомление не трогается — остаётся оригиналом для сверки с парсером.
     */
    suspend fun updateTransaction(
        id: Long,
        amountTiyn: Long,
        type: TransactionType,
        merchant: String?,
        category: String?
    )

    /** Сумма исходящих трат за сегодня (тиыны), реактивно. */
    fun observeTodayOutgoingSum(): Flow<Long>

    /** Суммы трат по категориям за сегодня, реактивно — для сводки «по категориям». */
    fun observeTodayCategorySums(): Flow<List<CategorySum>>

    /** Транзакции за сегодня, реактивно. */
    fun observeTodayTransactions(): Flow<List<Transaction>>

    /** Все транзакции (новые сверху), реактивно — для экрана управления записями. */
    fun observeAllTransactions(): Flow<List<Transaction>>

    /** Суммы трат по категориям за произвольный период [fromMillis, toMillis) — для статистики. */
    fun observeCategorySums(fromMillis: Long, toMillis: Long): Flow<List<CategorySum>>

    /**
     * Исходящие траты, сгруппированные по локальному дню, за период [fromMillis, toMillis).
     * Только дни с тратами (без нулевых) — заполнение пропусков делается выше, в UI/ViewModel.
     */
    fun observeOutgoingByDay(fromMillis: Long, toMillis: Long): Flow<List<DayTotal>>

    // ---- Месячные лимиты по категориям ----

    /** Потрачено за текущий месяц по категориям: slug → тиыны. */
    fun observeMonthlySpentByCategory(): Flow<Map<String, Long>>

    /** Заданные месячные лимиты: slug → лимит в тиынах. */
    fun observeCategoryLimits(): Flow<Map<String, Long>>

    /** Устанавливает/меняет месячный лимит категории. */
    suspend fun setCategoryBudget(categorySlug: String, limitTiyn: Long)

    /** Снимает лимит с категории. */
    suspend fun removeCategoryBudget(categorySlug: String)

    /**
     * Текущий статус месячного бюджета категории (потрачено за месяц + лимит).
     * Возвращает null, если лимит на категорию не задан — проверять нечего.
     */
    suspend fun categoryBudgetStatus(categorySlug: String): CategoryBudgetStatus?
}
