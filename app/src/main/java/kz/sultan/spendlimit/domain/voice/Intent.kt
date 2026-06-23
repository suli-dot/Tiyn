package kz.sultan.spendlimit.domain.voice

import kz.sultan.spendlimit.domain.BudgetPeriod
import java.time.LocalDate

/**
 * Распознанное намерение голосовой команды — ровно одна из «форм» инструментов,
 * которые модель обязана вернуть (tool_choice = any). Это чистое описание смысла:
 * никакой бизнес-логики, валидации и обращения к данным здесь нет — их выполняет
 * [VoiceCommandHandler]. Суммы уже переведены в ТИЫНЫ (модель оперирует тенге).
 *
 * Категория приходит как короткое слово-кандидат ([categoryWord]); в slug его
 * превращает [CategoryWordResolver], а не модель.
 */
sealed interface Intent {

    /** «закинул штуку за обед» → трата. */
    data class AddExpense(
        val amountTiyn: Long,
        val categoryWord: String?,
        val note: String?,
        val date: LocalDate?
    ) : Intent

    /** «зарплата пришла, 400 тысяч» → поступление. */
    data class AddIncome(
        val amountTiyn: Long,
        val note: String?,
        val date: LocalDate?
    ) : Intent

    /** «лимит на еду 60 тысяч в месяц» → лимит категории на период. */
    data class SetLimit(
        val amountTiyn: Long,
        val period: BudgetPeriod,
        val categoryWord: String?
    ) : Intent

    /** «сколько ушло на такси за неделю» → запрос суммы трат. */
    data class QuerySpent(
        val period: QueryPeriod,
        val categoryWord: String?
    ) : Intent

    /** «сколько осталось сегодня» → остаток относительно лимита. */
    data class QueryBalance(
        val period: BudgetPeriod?
    ) : Intent

    /** «нет, это была не такси а кафе» / «отмени последнее» → правка последней операции. */
    data class CorrectLast(
        val newAmountTiyn: Long?,
        val newCategoryWord: String?,
        val delete: Boolean
    ) : Intent

    /** Не хватает обязательного слота или фраза двусмысленна — модель просит уточнить. */
    data class Clarify(
        val missing: String,
        val question: String
    ) : Intent
}

/** Период для запросов трат. Шире [BudgetPeriod]: включает «вчера» и «за всё время». */
enum class QueryPeriod { TODAY, YESTERDAY, WEEK, MONTH, ALL }

/**
 * Результат разрешения команды моделью. [Resolved] несёт распознанный [Intent]
 * (в т.ч. [Intent.Clarify] — это валидный исход, а не ошибка); [Failure] — техническая
 * проблема (сеть, не-200 от API, пустой/битый ответ), о которой надо сообщить пользователю.
 */
sealed interface IntentResult {
    data class Resolved(val intent: Intent) : IntentResult
    data class Failure(val reason: String) : IntentResult
}
