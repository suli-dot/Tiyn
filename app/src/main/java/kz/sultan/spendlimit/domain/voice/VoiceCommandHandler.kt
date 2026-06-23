package kz.sultan.spendlimit.domain.voice

import kotlinx.coroutines.flow.first
import kz.sultan.spendlimit.data.prefs.SettingsRepository
import kz.sultan.spendlimit.data.repository.FinanceRepository
import kz.sultan.spendlimit.domain.SpendingLimitCalculator
import kz.sultan.spendlimit.domain.category.Categories
import kz.sultan.spendlimit.domain.model.Transaction
import kz.sultan.spendlimit.domain.model.TransactionType
import kz.sultan.spendlimit.util.Money
import kz.sultan.spendlimit.util.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Исполняет распознанный [Intent]: проверяет и пишет в [FinanceRepository].
 *
 * Здесь живёт всё, что касается безопасности и истины данных, — то, что СОЗНАТЕЛЬНО
 * не отдано модели в промпт:
 *  - санити-проверка суммы (модель могла ослышаться — код переспросит, а не запишет);
 *  - маппинг слова-категории в slug ([CategoryWordResolver]);
 *  - перевод тенге→тиыны уже сделан в парсере, тут только запись.
 *
 * Модель лишь распознаёт; решает и исполняет — этот класс.
 */
class VoiceCommandHandler(
    private val repo: FinanceRepository,
    private val settings: SettingsRepository,
    private val categories: CategoryWordResolver = CategoryWordResolver,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun handle(intent: Intent): VoiceOutcome = when (intent) {
        is Intent.AddExpense -> addExpense(intent)
        is Intent.AddIncome -> addIncome(intent)
        is Intent.SetLimit -> setLimit(intent)
        is Intent.QuerySpent -> querySpent(intent)
        is Intent.QueryBalance -> queryBalance(intent)
        is Intent.CorrectLast -> correctLast(intent)
        is Intent.Clarify -> VoiceOutcome.NeedClarify(intent.question)
    }

    private suspend fun addExpense(i: Intent.AddExpense): VoiceOutcome {
        validateAmount(i.amountTiyn)?.let { return it }
        val slug = categories.resolve(i.categoryWord)
        repo.addManualTransaction(
            amountTiyn = i.amountTiyn,
            type = TransactionType.PURCHASE,
            merchant = i.note,
            category = slug,
            createdAt = atDate(i.date)
        )
        val cat = Categories.bySlug(slug).title
        val day = if (i.date != null && i.date != today()) " за ${i.date}" else ""
        return VoiceOutcome.Recorded("Записал расход ${Money.formatTiyn(i.amountTiyn)} — $cat$day")
    }

    private suspend fun addIncome(i: Intent.AddIncome): VoiceOutcome {
        validateAmount(i.amountTiyn)?.let { return it }
        repo.addManualTransaction(
            amountTiyn = i.amountTiyn,
            type = TransactionType.INCOME,
            merchant = i.note,
            category = null,
            createdAt = atDate(i.date)
        )
        val src = i.note?.let { " ($it)" } ?: ""
        return VoiceOutcome.Recorded("Записал доход ${Money.formatTiyn(i.amountTiyn)}$src")
    }

    private suspend fun setLimit(i: Intent.SetLimit): VoiceOutcome {
        validateAmount(i.amountTiyn)?.let { return it }
        // Общий лимит (без категории) в текущей модели данных не выражается — переспрашиваем.
        val slug = categories.resolve(i.categoryWord)
            ?: return VoiceOutcome.NeedClarify("На какую категорию поставить лимит?")
        repo.setCategoryBudget(slug, i.period, i.amountTiyn)
        val cat = Categories.bySlug(slug).title
        return VoiceOutcome.Recorded("${i.period.adjective.replaceFirstChar { it.uppercase() }} лимит на $cat: ${Money.formatTiyn(i.amountTiyn)}")
    }

    private suspend fun querySpent(i: Intent.QuerySpent): VoiceOutcome {
        val slug = i.categoryWord?.let { categories.resolve(it) }
        val (from, to) = rangeOf(i.period)
        val sum = repo.spentBetween(from, to, slug)
        val cat = slug?.let { " на ${Categories.bySlug(it).title.lowercase()}" } ?: ""
        return VoiceOutcome.Answer("Потрачено$cat за ${periodLabel(i.period)}: ${Money.formatTiyn(sum)}")
    }

    private suspend fun queryBalance(i: Intent.QueryBalance): VoiceOutcome {
        val s = settings.settings.first()
        val income = s.nextIncomeDate
            ?: return VoiceOutcome.Answer("На счету ${Money.formatTiyn(s.balanceTiyn)}. Дневной лимит ещё не настроен.")
        val (from, to) = rangeOf(QueryPeriod.TODAY)
        val spentToday = repo.spentBetween(from, to, null)
        val r = SpendingLimitCalculator.compute(s.balanceTiyn, s.obligatoryTiyn, income, spentToday)
        return if (r.isExceeded)
            VoiceOutcome.Answer("Дневной лимит превышен на ${Money.formatTiyn(-r.remainingTodayTiyn)}")
        else
            VoiceOutcome.Answer("На сегодня осталось ${Money.formatTiyn(r.remainingTodayTiyn)} из ${Money.formatTiyn(r.dailyLimitTiyn)}")
    }

    private suspend fun correctLast(i: Intent.CorrectLast): VoiceOutcome {
        val last = repo.lastTransaction()
            ?: return VoiceOutcome.NeedClarify("Нет последней операции для правки.")
        if (i.delete) {
            repo.deleteTransaction(last.id)
            return VoiceOutcome.Recorded("Отменил последнюю запись: ${describe(last)}")
        }
        i.newAmountTiyn?.let { validateAmount(it)?.let { bad -> return bad } }
        val newAmount = i.newAmountTiyn ?: last.amountTiyn
        val newSlug = i.newCategoryWord?.let { categories.resolve(it) } ?: last.category
        repo.updateTransaction(last.id, newAmount, last.type, last.merchant, newSlug)
        return VoiceOutcome.Recorded("Поправил последнюю: ${Money.formatTiyn(newAmount)} — ${Categories.bySlug(newSlug).title}")
    }

    private fun describe(tx: Transaction): String =
        "${Money.formatTiyn(tx.amountTiyn)} — ${Categories.bySlug(tx.category).title}"

    /** Полуинтервал [from, to) для запроса трат. ALL — с эпохи до конца сегодняшнего дня. */
    private fun rangeOf(period: QueryPeriod): Pair<Long, Long> {
        val now = clock()
        return when (period) {
            QueryPeriod.TODAY -> Time.startOfTodayMillis(now) to Time.startOfTomorrowMillis(now)
            QueryPeriod.YESTERDAY -> {
                val zone = ZoneId.systemDefault()
                val y = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
                y.atStartOfDay(zone).toInstant().toEpochMilli() to
                    y.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            QueryPeriod.WEEK -> Time.startOfWeekMillis(now) to Time.startOfNextWeekMillis(now)
            QueryPeriod.MONTH -> Time.startOfMonthMillis(now) to Time.startOfNextMonthMillis(now)
            QueryPeriod.ALL -> 0L to Time.startOfTomorrowMillis(now)
        }
    }

    private fun periodLabel(period: QueryPeriod): String = when (period) {
        QueryPeriod.TODAY -> "сегодня"
        QueryPeriod.YESTERDAY -> "вчера"
        QueryPeriod.WEEK -> "эту неделю"
        QueryPeriod.MONTH -> "этот месяц"
        QueryPeriod.ALL -> "всё время"
    }

    /** @return [VoiceOutcome.NeedClarify], если сумма не прошла проверку; null — если ок. */
    private fun validateAmount(tiyn: Long): VoiceOutcome? = when {
        tiyn <= 0L -> VoiceOutcome.NeedClarify("Не расслышал сумму — повтори?")
        tiyn > SANITY_MAX_TIYN ->
            VoiceOutcome.NeedClarify("Сумма ${Money.formatTiyn(tiyn)} великовата — повтори, чтобы подтвердить.")
        else -> null
    }

    /** Дата траты → epoch millis. null/сегодня → текущий момент; иначе полдень указанного дня. */
    private fun atDate(date: LocalDate?): Long {
        val now = clock()
        if (date == null || date == today(now)) return now
        return date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun today(now: Long = clock()): LocalDate =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

    companion object {
        /** Порог «подозрительно крупной» суммы: 1 000 000 ₸ в тиынах. Выше — переспрос, не запись. */
        const val SANITY_MAX_TIYN: Long = 100_000_000L
    }
}
