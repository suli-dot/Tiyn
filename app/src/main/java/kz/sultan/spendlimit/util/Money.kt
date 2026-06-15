package kz.sultan.spendlimit.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Работа с деньгами в ТИЫНАХ (Long). Никаких float.
 */
object Money {

    private val groupingSymbols = DecimalFormatSymbols(Locale("ru")).apply {
        groupingSeparator = ' '
    }
    private val wholeTengeFormat = DecimalFormat("#,##0", groupingSymbols)

    /**
     * Парсит денежную строку Kaspi в тиыны.
     * Понимает разделители разрядов (любые пробелы, в т.ч. NBSP) и копейки через запятую/точку.
     *
     * "5 000"      -> 500000
     * "5 000,50"   -> 500050
     * "1 299,9"    -> 129990
     *
     * @return тиыны или null, если строка не похожа на сумму.
     */
    fun parseToTiyn(raw: String): Long? {
        // Оставляем только цифры; первый встреченный разделитель (',' или '.') считаем десятичным.
        val sb = StringBuilder()
        var decimalSeen = false
        for (ch in raw) {
            when {
                ch.isDigit() -> sb.append(ch)
                (ch == ',' || ch == '.') && !decimalSeen -> {
                    decimalSeen = true
                    sb.append(',')
                }
                // пробелы (любые), валютные символы и прочее — игнорируем
            }
        }
        val cleaned = sb.toString()
        if (cleaned.isEmpty() || cleaned == ",") return null

        val parts = cleaned.split(',')
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val tiyn: Long = if (parts.size == 1) {
            0
        } else {
            // Дополняем/обрезаем дробную часть до 2 знаков.
            val frac = (parts[1] + "00").take(2)
            frac.toLongOrNull() ?: return null
        }
        if (whole < 0) return null
        return whole * 100 + tiyn
    }

    /** Символ/код валюты для отображения. Незнакомую валюту показываем ISO-кодом. */
    fun symbolOf(currency: String): String = when (currency.uppercase(Locale.ROOT)) {
        "KZT" -> "₸"
        "KGS" -> "сом"
        "USD" -> "$"
        "EUR" -> "€"
        "RUB" -> "₽"
        else -> currency.uppercase(Locale.ROOT)
    }

    /**
     * Форматирует минорные единицы как "12 500 ₸" (без копеек, если их нет) или "12 500,50 ₸".
     * @param currency валюта операции; по умолчанию KZT для обратной совместимости.
     */
    fun formatTiyn(tiyn: Long, currency: String = "KZT"): String {
        val sign = if (tiyn < 0) "−" else ""
        val abs = kotlin.math.abs(tiyn)
        val whole = abs / 100
        val frac = abs % 100
        val wholeStr = wholeTengeFormat.format(whole)
        val symbol = symbolOf(currency)
        return if (frac == 0L) "$sign$wholeStr $symbol"
        else "$sign$wholeStr,${frac.toString().padStart(2, '0')} $symbol"
    }
}
