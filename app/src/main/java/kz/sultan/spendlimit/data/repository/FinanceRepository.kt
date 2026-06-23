package kz.sultan.spendlimit.data.repository

import kotlinx.coroutines.flow.Flow
import kz.sultan.spendlimit.data.local.dao.CategorySum
import kz.sultan.spendlimit.domain.BudgetPeriod
import kz.sultan.spendlimit.domain.model.ParsedTransaction
import kz.sultan.spendlimit.domain.model.Transaction
import kz.sultan.spendlimit.domain.model.TransactionType
import java.time.LocalDate

/** Сумма исходящих трат за один день — точка тренда для графика. */
data class DayTotal(val date: LocalDate, val totalTiyn: Long)

/** Лимиты категории по периодам; null = лимит на период не задан. */
data class CategoryLimits(
    val day: Long? = null,
    val week: Long? = null,
    val month: Long? = null
) {
    fun forPeriod(period: BudgetPeriod): Long? = when (period) {
        BudgetPeriod.DAY -> day
        BudgetPeriod.WEEK -> week
        BudgetPeriod.MONTH -> month
    }

    /** Копия с переустановленным лимитом периода (null = снять). */
    fun withPeriod(period: BudgetPeriod, tiyn: Long?): CategoryLimits = when (period) {
        BudgetPeriod.DAY -> copy(day = tiyn)
        BudgetPeriod.WEEK -> copy(week = tiyn)
        BudgetPeriod.MONTH -> copy(month = tiyn)
    }

    val isEmpty: Boolean get() = day == null && week == null && month == null
}

/** Состояние лимита категории за один период: потрачено + лимит. */
data class CategoryPeriodStatus(
    val period: BudgetPeriod,
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
 * Состояние бюджета категории по всем периодам (день/неделя/месяц).
 * [periods] всегда содержит три элемента в порядке [BudgetPeriod.entries].
 */
data class CategoryBudgetStatus(
    val categorySlug: String,
    val periods: List<CategoryPeriodStatus>
) {
    val hasAnyLimit: Boolean get() = periods.any { it.hasLimit }
    /** Периоды, по которым лимит исчерпан (для пометки «исчерпано»). */
    val exceededPeriods: List<CategoryPeriodStatus> get() = periods.filter { it.isExceeded }
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
     * @param dedupKey сигнатура повторной доставки (`"${sbn.key}|${postTime}"`); если
     *   уведомление с такой сигнатурой уже сохранено — это дубль (двойной callback/
     *   реконнект/ребут), ничего не пишем и возвращаем null. null — дедуп выключен.
     * @return созданная транзакция или null, если уведомление не финансовое или дубль.
     */
    suspend fun ingestNotification(
        packageName: String,
        title: String?,
        text: String,
        postedAt: Long,
        parsed: ParsedTransaction?,
        dedupKey: String? = null
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
     * Одноразовая сумма исходящих трат за период [fromMillis, toMillis).
     * [categorySlug] = null → по всем категориям. Для голосовых запросов «сколько потрачено».
     */
    suspend fun spentBetween(fromMillis: Long, toMillis: Long, categorySlug: String?): Long

    /** Последняя операция (самая свежая) — для голосовой правки «отмени/поправь последнее». null, если записей нет. */
    suspend fun lastTransaction(): Transaction?

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

    // ---- Лимиты по категориям (день/неделя/месяц) ----

    /** Потрачено по категориям за текущий отрезок [period]: slug → тиыны, реактивно. */
    fun observeSpentByCategory(period: BudgetPeriod): Flow<Map<String, Long>>

    /** Заданные лимиты по категориям: slug → [CategoryLimits], реактивно. */
    fun observeCategoryLimits(): Flow<Map<String, CategoryLimits>>

    /** Устанавливает/меняет лимит категории на конкретный период. */
    suspend fun setCategoryBudget(categorySlug: String, period: BudgetPeriod, limitTiyn: Long)

    /** Снимает лимит категории на конкретный период (строка удаляется, если лимитов не осталось). */
    suspend fun removeCategoryBudget(categorySlug: String, period: BudgetPeriod)

    /**
     * Текущий статус бюджета категории по всем периодам (потрачено + лимит на день/неделю/месяц).
     * Возвращает null, если на категорию не задан ни один лимит — проверять нечего.
     */
    suspend fun categoryBudgetStatus(categorySlug: String): CategoryBudgetStatus?
}
