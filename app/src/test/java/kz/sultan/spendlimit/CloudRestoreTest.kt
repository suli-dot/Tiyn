package kz.sultan.spendlimit

import kotlinx.coroutines.runBlocking
import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity
import kz.sultan.spendlimit.data.local.entity.TransactionEntity
import kz.sultan.spendlimit.data.remote.CloudRestore
import kz.sultan.spendlimit.data.remote.RemoteSyncSource
import kz.sultan.spendlimit.data.remote.RestoreWriter
import kz.sultan.spendlimit.data.remote.dto.toDto
import kz.sultan.spendlimit.data.remote.dto.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CloudRestore] (оркестрация восстановления из облака) и обратные мапперы DTO→сущность.
 * Запись в Room абстрагирована [RestoreWriter] — проверяем без Android: что выгружено из
 * облака, то и отдано писателю; без сессии не пишем ничего.
 */
class CloudRestoreTest {

    private class FakeRemote(
        private val authed: Boolean,
        private val raws: List<RawNotificationEntity> = emptyList(),
        private val txs: List<TransactionEntity> = emptyList()
    ) : RemoteSyncSource {
        var pulled = false
        override suspend fun isAuthenticated(): Boolean = authed
        override suspend fun pullRawNotifications(): List<RawNotificationEntity> { pulled = true; return raws }
        override suspend fun pullTransactions(): List<TransactionEntity> { pulled = true; return txs }
        // push в восстановлении не участвует
        override suspend fun pushRawNotifications(items: List<RawNotificationEntity>): List<Long> = emptyList()
        override suspend fun pushTransactions(items: List<TransactionEntity>): List<Long> = emptyList()
    }

    private class FakeWriter : RestoreWriter {
        var calls = 0
        var lastRaws: List<RawNotificationEntity>? = null
        var lastTxs: List<TransactionEntity>? = null
        override suspend fun replaceAll(raws: List<RawNotificationEntity>, txs: List<TransactionEntity>) {
            calls++; lastRaws = raws; lastTxs = txs
        }
    }

    private fun raw(id: Long) = RawNotificationEntity(
        id = id, packageName = "kz.kaspi.mobile", title = "Kaspi", text = "Покупка 1000 ₸", postedAt = 1_700_000_000_000L
    )

    private fun tx(id: Long) = TransactionEntity(
        id = id, rawId = null, amount = 100_000L, type = "PURCHASE", merchant = "Кафе",
        category = "cafe", createdAt = 1_700_000_000_000L
    )

    // ---- Оркестрация ----

    @Test
    fun notAuthenticated_throwsAndWritesNothing() {
        val remote = FakeRemote(authed = false)
        val writer = FakeWriter()
        val restore = CloudRestore(remote, writer)

        assertThrows(IllegalStateException::class.java) { runBlocking { restore.restore() } }

        assertEquals("без сессии писать нечего", 0, writer.calls)
        assertFalse("без сессии не должны дёргать pull", remote.pulled)
    }

    @Test
    fun restore_passesPulledDataToWriterAndReturnsCounts() = runBlocking {
        val raws = listOf(raw(1), raw(2))
        val txs = listOf(tx(1), tx(2), tx(3))
        val writer = FakeWriter()
        val result = CloudRestore(FakeRemote(authed = true, raws = raws, txs = txs), writer).restore()

        assertEquals(2, result.rawNotifications)
        assertEquals(3, result.transactions)
        assertEquals(1, writer.calls)
        assertSame("во writer уходит ровно выгруженное из облака", raws, writer.lastRaws)
        assertSame(txs, writer.lastTxs)
    }

    @Test
    fun restore_emptyCloud_returnsZerosAndStillWrites() = runBlocking {
        val writer = FakeWriter()
        val result = CloudRestore(FakeRemote(authed = true), writer).restore()

        assertEquals(0, result.rawNotifications)
        assertEquals(0, result.transactions)
        assertEquals("вызов writer корректен даже при пустом облаке", 1, writer.calls)
    }

    // ---- Обратные мапперы (DTO облака → локальная сущность) ----

    @Test
    fun transactionDto_toEntity_roundTrip() {
        val origin = tx(42).copy(editedAt = 1_700_000_500_000L, currency = "KZT")
        val back = origin.toDto(userId = "u1").toEntity()

        assertEquals(origin.id, back.id) // client_id облака → локальный id
        assertEquals(origin.amount, back.amount)
        assertEquals(origin.type, back.type)
        assertEquals(origin.merchant, back.merchant)
        assertEquals(origin.category, back.category)
        assertEquals(origin.currency, back.currency)
        assertEquals("ISO-8601 round-trip без потери миллисекунд", origin.createdAt, back.createdAt)
        assertEquals(origin.editedAt, back.editedAt)
        assertTrue("пришло из облака — повторно выгружать нечего", back.synced)
    }

    @Test
    fun transactionDto_toEntity_preservesSoftDelete() {
        val deleted = tx(7).copy(deletedAt = 1_700_000_900_000L)
        val back = deleted.toDto("u1").toEntity()
        assertEquals("soft-delete сохраняется — запись не воскресает", deleted.deletedAt, back.deletedAt)
    }

    @Test
    fun rawDto_toEntity_setsSyncedAndDropsDedupKey() {
        val origin = raw(5).copy(dedupKey = "key|123")
        val back = origin.toDto("u1").toEntity()

        assertEquals(origin.id, back.id)
        assertEquals(origin.packageName, back.packageName)
        assertEquals(origin.title, back.title)
        assertEquals(origin.text, back.text)
        assertEquals(origin.postedAt, back.postedAt)
        assertTrue(back.synced)
        assertNull("восстановленная запись не из живого пуша — не дедупится", back.dedupKey)
    }
}
