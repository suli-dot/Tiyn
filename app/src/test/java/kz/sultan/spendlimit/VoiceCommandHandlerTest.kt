package kz.sultan.spendlimit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kz.sultan.spendlimit.data.local.dao.CategorySum
import kz.sultan.spendlimit.data.prefs.SettingsReader
import kz.sultan.spendlimit.data.prefs.UserSettings
import kz.sultan.spendlimit.data.repository.CategoryBudgetStatus
import kz.sultan.spendlimit.data.repository.CategoryLimits
import kz.sultan.spendlimit.data.repository.DayTotal
import kz.sultan.spendlimit.data.repository.FinanceRepository
import kz.sultan.spendlimit.domain.BudgetPeriod
import kz.sultan.spendlimit.domain.model.ParsedTransaction
import kz.sultan.spendlimit.domain.model.Transaction
import kz.sultan.spendlimit.domain.model.TransactionType
import kz.sultan.spendlimit.domain.voice.Intent
import kz.sultan.spendlimit.domain.voice.VoiceCommandHandler
import kz.sultan.spendlimit.domain.voice.VoiceOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Защитные («негативные») пути [VoiceCommandHandler] — то, что СОЗНАТЕЛЬНО не отдано модели:
 * санити-проверка суммы, нераспознанная категория, правка при пустой истории. Парсер ответа
 * модели проверяется отдельно в [VoiceIntentParserTest]; здесь — поведение исполнителя.
 *
 * Зависимости — рукописные in-memory фейки (не моки): проверяем РЕАЛЬНЫЙ эффект на данные
 * (отказ ⇒ в репозиторий ничего не записано), а не вызовы мока.
 */
class VoiceCommandHandlerTest {

    /** Минимальный in-memory [FinanceRepository]: пишущие методы копят эффект, читающие — пусты. */
    private class FakeFinanceRepository : FinanceRepository {
        val added = mutableListOf<Transaction>()
        val deletedIds = mutableListOf<Long>()
        val updates = mutableListOf<Long>()
        val budgets = mutableListOf<Triple<String, BudgetPeriod, Long>>()
        var lastTx: Transaction? = null
        var spent: Long = 0L
        private var idSeq = 0L

        override suspend fun addManualTransaction(
            amountTiyn: Long,
            type: TransactionType,
            merchant: String?,
            category: String?,
            createdAt: Long
        ): Transaction {
            val tx = Transaction(++idSeq, amountTiyn, type, merchant, category, createdAt, isManual = true)
            added += tx
            return tx
        }

        override suspend fun deleteTransaction(id: Long) { deletedIds += id }

        override suspend fun updateTransaction(
            id: Long,
            amountTiyn: Long,
            type: TransactionType,
            merchant: String?,
            category: String?
        ) { updates += id }

        override suspend fun setCategoryBudget(categorySlug: String, period: BudgetPeriod, limitTiyn: Long) {
            budgets += Triple(categorySlug, period, limitTiyn)
        }

        override suspend fun lastTransaction(): Transaction? = lastTx
        override suspend fun spentBetween(fromMillis: Long, toMillis: Long, categorySlug: String?): Long = spent

        // --- неиспользуемое в этих тестах: нейтральные заглушки ---
        override suspend fun ingestNotification(
            packageName: String, title: String?, text: String, postedAt: Long,
            parsed: ParsedTransaction?, dedupKey: String?
        ): Transaction? = null
        override fun observeTodayOutgoingSum(): Flow<Long> = flowOf(0L)
        override fun observeTodayCategorySums(): Flow<List<CategorySum>> = flowOf(emptyList())
        override fun observeTodayTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeAllTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeCategorySums(fromMillis: Long, toMillis: Long): Flow<List<CategorySum>> = flowOf(emptyList())
        override fun observeOutgoingByDay(fromMillis: Long, toMillis: Long): Flow<List<DayTotal>> = flowOf(emptyList())
        override fun observeSpentByCategory(period: BudgetPeriod): Flow<Map<String, Long>> = flowOf(emptyMap())
        override fun observeCategoryLimits(): Flow<Map<String, CategoryLimits>> = flowOf(emptyMap())
        override suspend fun removeCategoryBudget(categorySlug: String, period: BudgetPeriod) {}
        override suspend fun categoryBudgetStatus(categorySlug: String): CategoryBudgetStatus? = null
    }

    private class FakeSettings(private val value: UserSettings) : SettingsReader {
        override val settings: Flow<UserSettings> = flowOf(value)
    }

    private fun handler(
        repo: FakeFinanceRepository = FakeFinanceRepository(),
        settings: UserSettings = UserSettings(balanceTiyn = 0L, obligatoryTiyn = 0L, nextIncomeDate = null)
    ) = VoiceCommandHandler(repo, FakeSettings(settings)) to repo

    private fun expense(amountTiyn: Long) = Intent.AddExpense(amountTiyn, categoryWord = "еда", note = null, date = null)

    // ---- Санити-проверка суммы: отказ + ничего не записано ----

    @Test
    fun addExpense_zeroAmount_clarifiesAndWritesNothing() = runBlocking {
        val (h, repo) = handler()
        val out = h.handle(expense(0L))
        assertTrue("ожидался NeedClarify, был $out", out is VoiceOutcome.NeedClarify)
        assertTrue("нулевая сумма не должна записываться", repo.added.isEmpty())
    }

    @Test
    fun addExpense_negativeAmount_clarifies() = runBlocking {
        val (h, repo) = handler()
        assertTrue(h.handle(expense(-500L)) is VoiceOutcome.NeedClarify)
        assertTrue(repo.added.isEmpty())
    }

    @Test
    fun addExpense_overSanityMax_clarifies() = runBlocking {
        val (h, repo) = handler()
        val out = h.handle(expense(VoiceCommandHandler.SANITY_MAX_TIYN + 1))
        assertTrue(out is VoiceOutcome.NeedClarify)
        assertTrue("подозрительно крупная сумма не пишется без подтверждения", repo.added.isEmpty())
    }

    @Test
    fun addExpense_atSanityMax_isAccepted() = runBlocking {
        // Граница включительно: ровно лимит — валиден (проверка использует строгое «больше»).
        val (h, repo) = handler()
        val out = h.handle(expense(VoiceCommandHandler.SANITY_MAX_TIYN))
        assertTrue(out is VoiceOutcome.Recorded)
        assertEquals(1, repo.added.size)
    }

    @Test
    fun addIncome_zeroAmount_clarifies() = runBlocking {
        val (h, repo) = handler()
        val out = h.handle(Intent.AddIncome(amountTiyn = 0L, note = "зарплата", date = null))
        assertTrue(out is VoiceOutcome.NeedClarify)
        assertTrue(repo.added.isEmpty())
    }

    // ---- Лимит ----

    @Test
    fun setLimit_unresolvedCategory_clarifiesAndSetsNothing() = runBlocking {
        val (h, repo) = handler()
        val out = h.handle(Intent.SetLimit(amountTiyn = 6_000_000L, period = BudgetPeriod.MONTH, categoryWord = "абракадабра"))
        assertTrue("нераспознанная категория ⇒ переспрос", out is VoiceOutcome.NeedClarify)
        assertTrue(repo.budgets.isEmpty())
    }

    @Test
    fun setLimit_zeroAmount_clarifiesBeforeCategory() = runBlocking {
        val (h, repo) = handler()
        val out = h.handle(Intent.SetLimit(amountTiyn = 0L, period = BudgetPeriod.DAY, categoryWord = "еда"))
        assertTrue(out is VoiceOutcome.NeedClarify)
        assertTrue(repo.budgets.isEmpty())
    }

    // ---- Правка последней операции ----

    @Test
    fun correctLast_noHistory_clarifies() = runBlocking {
        val (h, repo) = handler() // lastTx = null
        val out = h.handle(Intent.CorrectLast(newAmountTiyn = 1000L, newCategoryWord = null, delete = false))
        assertTrue(out is VoiceOutcome.NeedClarify)
        assertTrue(repo.updates.isEmpty())
    }

    @Test
    fun correctLast_deleteNoHistory_clarifiesAndDeletesNothing() = runBlocking {
        val (h, repo) = handler()
        val out = h.handle(Intent.CorrectLast(newAmountTiyn = null, newCategoryWord = null, delete = true))
        assertTrue(out is VoiceOutcome.NeedClarify)
        assertTrue(repo.deletedIds.isEmpty())
    }

    @Test
    fun correctLast_invalidNewAmount_doesNotModify() = runBlocking {
        val repo = FakeFinanceRepository().apply {
            lastTx = Transaction(7L, 50_000L, TransactionType.PURCHASE, "Кафе", "cafe", createdAt = 1L)
        }
        val (h, _) = handler(repo)
        val out = h.handle(Intent.CorrectLast(newAmountTiyn = 0L, newCategoryWord = null, delete = false))
        assertTrue("нулевая правка отклоняется", out is VoiceOutcome.NeedClarify)
        assertTrue("при отказе запись не меняется", repo.updates.isEmpty())
    }

    // ---- Clarify проходит насквозь ----

    @Test
    fun clarify_passesThroughQuestion() = runBlocking {
        val (h, _) = handler()
        val out = h.handle(Intent.Clarify(missing = "категория", question = "На что 3000?"))
        assertTrue(out is VoiceOutcome.NeedClarify)
        assertEquals("На что 3000?", (out as VoiceOutcome.NeedClarify).question)
    }
}
