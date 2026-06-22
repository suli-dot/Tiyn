package kz.sultan.spendlimit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kz.sultan.spendlimit.data.local.dao.MerchantRuleDao
import kz.sultan.spendlimit.data.local.entity.MerchantRuleEntity
import kz.sultan.spendlimit.domain.category.Categories
import kz.sultan.spendlimit.domain.category.Categorizer
import kz.sultan.spendlimit.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorizerTest {

    /** In-memory подмена DAO — без Room, чтобы тест оставался юнит-тестом. */
    private class FakeRuleDao : MerchantRuleDao {
        private val rules = mutableListOf<MerchantRuleEntity>()
        override suspend fun all(): List<MerchantRuleEntity> = rules.toList()
        override fun observeAll(): Flow<List<MerchantRuleEntity>> = flowOf(rules.toList())
        override suspend fun findByMerchant(merchantNorm: String): MerchantRuleEntity? =
            rules.firstOrNull { it.merchantNorm == merchantNorm }
        override suspend fun upsert(rule: MerchantRuleEntity) {
            rules.removeAll { it.merchantNorm == rule.merchantNorm }
            rules.add(rule)
        }
        override suspend fun insertAll(items: List<MerchantRuleEntity>) = items.forEach { upsert(it) }
    }

    @Test
    fun builtin_matches_groceries() = runBlocking {
        val c = Categorizer(FakeRuleDao())
        assertEquals(
            Categories.GROCERIES.slug,
            c.categorize("Magnum Express", TransactionType.PURCHASE)
        )
    }

    @Test
    fun builtin_matches_transport() = runBlocking {
        val c = Categorizer(FakeRuleDao())
        assertEquals(
            Categories.TRANSPORT.slug,
            c.categorize("Yandex Go", TransactionType.PURCHASE)
        )
    }

    @Test
    fun transfer_without_known_merchant_goes_to_transfers() = runBlocking {
        val c = Categorizer(FakeRuleDao())
        assertEquals(
            Categories.TRANSFER.slug,
            c.categorize("Айгуль К.", TransactionType.TRANSFER)
        )
    }

    @Test
    fun unknown_purchase_is_null() = runBlocking {
        val c = Categorizer(FakeRuleDao())
        assertNull(c.categorize("Неведомый ИП", TransactionType.PURCHASE))
    }

    @Test
    fun null_merchant_purchase_is_null() = runBlocking {
        val c = Categorizer(FakeRuleDao())
        assertNull(c.categorize(null, TransactionType.PURCHASE))
    }

    @Test
    fun user_rule_overrides_builtin() = runBlocking {
        val dao = FakeRuleDao()
        val c = Categorizer(dao)
        // Magnum по словарю — продукты, но ручная поправка важнее.
        c.rememberUserChoice("Magnum Express", Categories.CAFE.slug)
        assertEquals(
            Categories.CAFE.slug,
            c.categorize("magnum express", TransactionType.PURCHASE)
        )
    }
}
