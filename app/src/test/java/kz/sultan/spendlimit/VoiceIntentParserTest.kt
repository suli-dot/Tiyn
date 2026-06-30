package kz.sultan.spendlimit

import kz.sultan.spendlimit.data.remote.nlu.IntentParser
import kz.sultan.spendlimit.domain.BudgetPeriod
import kz.sultan.spendlimit.domain.voice.Intent
import kz.sultan.spendlimit.domain.voice.IntentResult
import kz.sultan.spendlimit.domain.voice.QueryPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Парсинг ответа Anthropic (tool_use) в [Intent]. Без сети — кормим готовый JSON,
 * как его отдаёт Messages API. Проверяем перевод тенге→тиыны и деградацию в Clarify/Failure.
 */
class VoiceIntentParserTest {

    private fun toolUse(name: String, input: String): String =
        """{"id":"msg_1","type":"message","role":"assistant","stop_reason":"tool_use",
            "content":[{"type":"tool_use","id":"toolu_1","name":"$name","input":$input}]}"""

    private fun resolved(raw: String): Intent {
        val r = IntentParser.fromResponse(raw)
        assertTrue("ожидался Resolved, был $r", r is IntentResult.Resolved)
        return (r as IntentResult.Resolved).intent
    }

    @Test
    fun addExpense_convertsTengeToTiyn() {
        val i = resolved(toolUse("add_expense", """{"amount":1000,"category":"еда"}"""))
        assertTrue(i is Intent.AddExpense)
        i as Intent.AddExpense
        assertEquals(100_000L, i.amountTiyn) // 1000 ₸ = 100 000 тиын
        assertEquals("еда", i.categoryWord)
        assertNull(i.date)
    }

    @Test
    fun addExpense_handlesDecimalAndDate() {
        val i = resolved(toolUse("add_expense", """{"amount":1299.9,"date":"2026-06-22"}""")) as Intent.AddExpense
        assertEquals(129_990L, i.amountTiyn)
        assertEquals(LocalDate.of(2026, 6, 22), i.date)
    }

    @Test
    fun addExpense_missingAmount_becomesClarify() {
        val i = resolved(toolUse("add_expense", """{"category":"еда"}"""))
        assertTrue(i is Intent.Clarify)
    }

    @Test
    fun addIncome_parsed() {
        val i = resolved(toolUse("add_income", """{"amount":400000,"note":"зарплата"}""")) as Intent.AddIncome
        assertEquals(40_000_000L, i.amountTiyn)
        assertEquals("зарплата", i.note)
    }

    @Test
    fun setLimit_parsesPeriodEnum() {
        val i = resolved(toolUse("set_limit", """{"amount":60000,"period":"month","category":"еда"}""")) as Intent.SetLimit
        assertEquals(6_000_000L, i.amountTiyn)
        assertEquals(BudgetPeriod.MONTH, i.period)
        assertEquals("еда", i.categoryWord)
    }

    @Test
    fun querySpent_parsesQueryPeriod() {
        val i = resolved(toolUse("query_spent", """{"period":"week","category":"такси"}""")) as Intent.QuerySpent
        assertEquals(QueryPeriod.WEEK, i.period)
        assertEquals("такси", i.categoryWord)
    }

    @Test
    fun canISpend_parsed() {
        val i = resolved(toolUse("can_i_spend", """{"amount":15000}""")) as Intent.CanISpend
        assertEquals(1_500_000L, i.amountTiyn) // 15 000 ₸
    }

    @Test
    fun canISpend_missingAmount_becomesClarify() {
        val i = resolved(toolUse("can_i_spend", """{}"""))
        assertTrue(i is Intent.Clarify)
    }

    @Test
    fun correctLast_delete() {
        val i = resolved(toolUse("correct_last", """{"delete":true}""")) as Intent.CorrectLast
        assertTrue(i.delete)
        assertNull(i.newAmountTiyn)
    }

    @Test
    fun clarify_carriesQuestion() {
        val i = resolved(toolUse("clarify", """{"missing":"категория","question":"На что 3000?"}""")) as Intent.Clarify
        assertEquals("категория", i.missing)
        assertEquals("На что 3000?", i.question)
    }

    @Test
    fun unknownTool_degradesToClarify() {
        val i = resolved(toolUse("some_future_tool", """{}"""))
        assertTrue(i is Intent.Clarify)
    }

    @Test
    fun noToolUseBlock_degradesToClarify() {
        val raw = """{"type":"message","content":[{"type":"text","text":"привет"}]}"""
        val i = resolved(raw)
        assertTrue(i is Intent.Clarify)
    }

    @Test
    fun apiError_becomesFailure() {
        val raw = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val r = IntentParser.fromResponse(raw)
        assertTrue(r is IntentResult.Failure)
        assertEquals("invalid x-api-key", (r as IntentResult.Failure).reason)
    }

    @Test
    fun garbage_becomesFailure() {
        assertTrue(IntentParser.fromResponse("не json вовсе") is IntentResult.Failure)
    }
}
