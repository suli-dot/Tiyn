package kz.sultan.spendlimit

import kz.sultan.spendlimit.domain.model.TransactionType
import kz.sultan.spendlimit.service.notification.KaspiNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Эталонные форматы для парсера. Когда сверишь реальные пуши на устройстве —
 * добавляй сюда новые кейсы, и регрессии будут видны сразу.
 */
class KaspiNotificationParserTest {

    private val pkg = KaspiNotificationParser.KASPI_PACKAGE

    @Test
    fun purchase_withMerchant() {
        val r = KaspiNotificationParser.parse(pkg, "Kaspi Gold", "Покупка 4 990 ₸, Magnum")
        requireNotNull(r)
        assertEquals(499000L, r.amountTiyn)
        assertEquals(TransactionType.PURCHASE, r.type)
        assertEquals("Magnum", r.merchant)
    }

    @Test
    fun qr_payment() {
        val r = KaspiNotificationParser.parse(pkg, null, "Оплата по QR 1 200 ₸, Wolt")
        requireNotNull(r)
        assertEquals(120000L, r.amountTiyn)
        assertEquals(TransactionType.PURCHASE, r.type)
        assertEquals("Wolt", r.merchant)
    }

    @Test
    fun outgoing_transfer() {
        val r = KaspiNotificationParser.parse(pkg, null, "Перевод 10 000 ₸ Айгуль К.")
        requireNotNull(r)
        assertEquals(1_000_000L, r.amountTiyn)
        assertEquals(TransactionType.TRANSFER, r.type)
    }

    @Test
    fun income_topUp() {
        val r = KaspiNotificationParser.parse(pkg, null, "Пополнение 50 000 ₸")
        requireNotNull(r)
        assertEquals(5_000_000L, r.amountTiyn)
        assertEquals(TransactionType.INCOME, r.type)
    }

    @Test
    fun income_transferFrom_isIncomeNotTransfer() {
        val r = KaspiNotificationParser.parse(pkg, null, "Вы получили перевод 5 000 ₸ от Аскар А.")
        requireNotNull(r)
        assertEquals(TransactionType.INCOME, r.type)
        assertEquals("Аскар А", r.merchant)
    }

    @Test
    fun amount_withTiyn() {
        val r = KaspiNotificationParser.parse(pkg, null, "Покупка 1 299,90 ₸, Small")
        requireNotNull(r)
        assertEquals(129990L, r.amountTiyn)
    }

    @Test
    fun merchant_cutsOffBalanceTail() {
        val r = KaspiNotificationParser.parse(pkg, null, "Покупка 2 500 ₸, Magnum. Доступно 45 000 ₸")
        requireNotNull(r)
        assertEquals(250000L, r.amountTiyn)
        assertEquals("Magnum", r.merchant)
    }

    @Test
    fun nonKaspi_isIgnored() {
        assertNull(KaspiNotificationParser.parse("com.whatsapp", "Чат", "Покупка 1 000 ₸"))
    }

    @Test
    fun nonFinancial_isIgnored() {
        assertNull(KaspiNotificationParser.parse(pkg, "Kaspi", "Ваш заказ готов к выдаче"))
    }

    @Test
    fun income_isNotOutgoing() {
        assertTrue(TransactionType.PURCHASE.isOutgoing)
        assertTrue(TransactionType.TRANSFER.isOutgoing)
        assertTrue(!TransactionType.INCOME.isOutgoing)
    }
}
