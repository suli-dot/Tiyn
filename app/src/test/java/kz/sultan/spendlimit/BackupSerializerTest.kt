package kz.sultan.spendlimit

import kz.sultan.spendlimit.data.backup.BackupFile
import kz.sultan.spendlimit.data.backup.BackupSerializer
import kz.sultan.spendlimit.data.backup.CategoryBudgetDto
import kz.sultan.spendlimit.data.backup.MerchantRuleDto
import kz.sultan.spendlimit.data.backup.RawNotificationDto
import kz.sultan.spendlimit.data.backup.SettingsDto
import kz.sultan.spendlimit.data.backup.TransactionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip сериализации бэкапа: encode → decode даёт тот же объект.
 * Чистый JVM-тест, без Room/DataStore.
 */
class BackupSerializerTest {

    private fun sample() = BackupFile(
        exportedAt = 1_700_000_000_000L,
        transactions = listOf(
            TransactionDto(
                id = 1, rawId = 10, amount = 5000, type = "PURCHASE", merchant = "Magnum",
                category = "groceries", currency = "KZT", createdAt = 1_700_000_000_000L,
                editedAt = null, deletedAt = null, synced = true
            ),
            // Ручная запись (rawId=null) + soft-deleted + правка — крайние случаи nullable.
            TransactionDto(
                id = 2, rawId = null, amount = 120000, type = "TRANSFER", merchant = null,
                category = null, currency = "USD", createdAt = 1_700_000_500_000L,
                editedAt = 1_700_000_600_000L, deletedAt = 1_700_000_700_000L, synced = false
            )
        ),
        rawNotifications = listOf(
            RawNotificationDto(
                id = 10, packageName = "kz.kaspi.mobile", title = "Kaspi",
                text = "Покупка 50 ₸", postedAt = 1_700_000_000_000L, synced = true
            )
        ),
        merchantRules = listOf(MerchantRuleDto(1, "magnum", "groceries", 1_700_000_000_000L)),
        categoryBudgets = listOf(
            CategoryBudgetDto("groceries", 300000, 1500000, 30000000, 1_700_000_000_000L)
        ),
        settings = SettingsDto(
            balanceTiyn = 50000000, obligatoryTiyn = 10000000,
            nextIncomeEpochDay = 20000, themeMode = "DARK"
        )
    )

    @Test
    fun roundTrip_preservesEverything() {
        val original = sample()
        val restored = BackupSerializer.decode(BackupSerializer.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun roundTrip_handlesNullsAndEmptyLists() {
        val original = BackupFile(
            exportedAt = 1L,
            settings = SettingsDto(0, 0, null, "SYSTEM")
        )
        val restored = BackupSerializer.decode(BackupSerializer.encode(original))
        assertEquals(original, restored)
        assertTrue(restored.transactions.isEmpty())
        assertEquals(null, restored.settings.nextIncomeEpochDay)
    }

    @Test
    fun decode_corruptFile_throws() {
        assertThrows(Exception::class.java) {
            BackupSerializer.decode("{ это не наш json")
        }
    }

    @Test
    fun decode_ignoresUnknownKeys() {
        // Файл от более новой версии приложения с лишним полем — должен читаться.
        val json = """
            {"schemaVersion":5,"exportedAt":1,"settings":{"balanceTiyn":0,"obligatoryTiyn":0,
            "nextIncomeEpochDay":null,"themeMode":"SYSTEM"},"futureField":"ignored"}
        """.trimIndent()
        val restored = BackupSerializer.decode(json)
        assertEquals(1L, restored.exportedAt)
    }
}
