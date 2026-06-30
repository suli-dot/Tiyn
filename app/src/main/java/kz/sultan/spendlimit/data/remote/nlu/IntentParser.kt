package kz.sultan.spendlimit.data.remote.nlu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kz.sultan.spendlimit.domain.BudgetPeriod
import kz.sultan.spendlimit.domain.voice.Intent
import kz.sultan.spendlimit.domain.voice.IntentResult
import kz.sultan.spendlimit.domain.voice.QueryPeriod
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Превращает JSON-ответ Anthropic Messages API в [IntentResult]. Чистая функция без сети —
 * полностью покрывается юнит-тестами. Устойчив к лишним полям и неожиданной форме:
 * любая нераспознанная структура деградирует в [Intent.Clarify], а не падает.
 *
 * Перевод тенге→тиыны делается здесь (×100 с округлением): дальше по коду — только тиыны.
 */
object IntentParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun fromResponse(raw: String): IntentResult {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return IntentResult.Failure("Пустой или нечитаемый ответ модели")

        // Тело ошибки API: {"type":"error","error":{"message":...}}
        root["error"]?.let { err ->
            val msg = runCatching { err.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            return IntentResult.Failure(msg ?: "Ошибка API")
        }

        val content = runCatching { root["content"]?.jsonArray }.getOrNull()
            ?: return IntentResult.Failure("В ответе нет content")

        val toolUse = content.firstOrNull {
            runCatching { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }.getOrNull() == "tool_use"
        }?.jsonObject ?: return IntentResult.Resolved(genericClarify())

        val name = toolUse["name"]?.jsonPrimitive?.contentOrNull
            ?: return IntentResult.Resolved(genericClarify())
        val input = runCatching { toolUse["input"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())

        return IntentResult.Resolved(toIntent(name, input))
    }

    private fun toIntent(name: String, input: JsonObject): Intent = when (name) {
        "add_expense" -> {
            val tiyn = input.tiyn("amount")
            if (tiyn == null) clarify("сумма", "Не понял сумму — повтори?")
            else Intent.AddExpense(tiyn, input.str("category"), input.str("note"), input.date("date"))
        }
        "add_income" -> {
            val tiyn = input.tiyn("amount")
            if (tiyn == null) clarify("сумма", "Не понял сумму — повтори?")
            else Intent.AddIncome(tiyn, input.str("note"), input.date("date"))
        }
        "set_limit" -> {
            val tiyn = input.tiyn("amount")
            val period = input.budgetPeriod("period")
            if (tiyn == null || period == null) clarify("лимит", "Какой лимит и на какой период?")
            else Intent.SetLimit(tiyn, period, input.str("category"))
        }
        "query_spent" -> {
            val period = input.queryPeriod("period")
            if (period == null) clarify("период", "За какой период посчитать?")
            else Intent.QuerySpent(period, input.str("category"))
        }
        "query_balance" -> Intent.QueryBalance(input.budgetPeriod("period"))
        "can_i_spend" -> {
            val tiyn = input.tiyn("amount")
            if (tiyn == null) clarify("сумма", "Сколько хочешь потратить?")
            else Intent.CanISpend(tiyn)
        }
        "correct_last" -> Intent.CorrectLast(
            newAmountTiyn = input.tiyn("new_amount"),
            newCategoryWord = input.str("new_category"),
            delete = input.bool("delete")
        )
        "clarify" -> clarify(
            missing = input.str("missing") ?: "данные",
            question = input.str("question") ?: "Уточни, пожалуйста?"
        )
        else -> genericClarify()
    }

    private fun clarify(missing: String, question: String) = Intent.Clarify(missing, question)
    private fun genericClarify() = Intent.Clarify("ввод", "Не понял команду — повтори?")

    // --- безопасное чтение скалярных слотов (вход всегда плоский: number/string/bool/null) ---

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.ifBlank { null }

    /** Сумма в тенге → тиыны (×100, округление). Неположительное/отсутствует → null. */
    private fun JsonObject.tiyn(key: String): Long? {
        val tenge = runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull() ?: return null
        if (tenge <= 0.0) return null
        return (tenge * 100).roundToLong()
    }

    private fun JsonObject.bool(key: String): Boolean =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false

    private fun JsonObject.date(key: String): LocalDate? =
        str(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun JsonObject.budgetPeriod(key: String): BudgetPeriod? = when (str(key)?.lowercase()) {
        "day" -> BudgetPeriod.DAY
        "week" -> BudgetPeriod.WEEK
        "month" -> BudgetPeriod.MONTH
        else -> null
    }

    private fun JsonObject.queryPeriod(key: String): QueryPeriod? = when (str(key)?.lowercase()) {
        "today" -> QueryPeriod.TODAY
        "yesterday" -> QueryPeriod.YESTERDAY
        "week" -> QueryPeriod.WEEK
        "month" -> QueryPeriod.MONTH
        "all" -> QueryPeriod.ALL
        else -> null
    }
}
