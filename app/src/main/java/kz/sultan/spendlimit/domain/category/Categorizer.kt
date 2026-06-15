package kz.sultan.spendlimit.domain.category

import kz.sultan.spendlimit.data.local.dao.MerchantRuleDao
import kz.sultan.spendlimit.data.local.entity.MerchantRuleEntity
import kz.sultan.spendlimit.domain.model.TransactionType
import java.util.Locale

/**
 * Определяет категорию траты по мерчанту. Два слоя приоритета:
 *  1. пользовательские правила ([MerchantRuleDao]) — точное совпадение по
 *     нормализованному мерчанту; это ручные поправки, они главнее;
 *  2. встроенный словарь [BUILTIN] — совпадение по подстроке-маркеру.
 *
 * Если ничего не совпало, но операция — перевод, относим в «Переводы»
 * (там мерчант это имя человека, словарём не покрыть). Иначе — null.
 */
class Categorizer(private val ruleDao: MerchantRuleDao) {

    /** @return slug категории или null, если определить не удалось. */
    suspend fun categorize(merchant: String?, type: TransactionType): String? {
        val norm = normalize(merchant)
        if (norm != null) {
            ruleDao.findByMerchant(norm)?.let { return it.category }
            BUILTIN.firstOrNull { (markers, _) -> markers.any { norm.contains(it) } }
                ?.let { return it.second }
        }
        if (type == TransactionType.TRANSFER) return Categories.TRANSFER.slug
        return null
    }

    /** Запоминает ручную поправку: впредь этот мерчант категоризуется автоматически. */
    suspend fun rememberUserChoice(merchant: String?, categorySlug: String) {
        val norm = normalize(merchant) ?: return
        ruleDao.upsert(
            MerchantRuleEntity(
                merchantNorm = norm,
                category = categorySlug,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        fun normalize(merchant: String?): String? =
            merchant?.lowercase(Locale.ROOT)?.trim()?.ifBlank { null }

        /**
         * Встроенный словарь: список (маркеры → slug). Порядок важен —
         * первое совпадение по подстроке выигрывает. Маркеры в нижнем регистре.
         */
        private val BUILTIN: List<Pair<List<String>, String>> = listOf(
            listOf("magnum", "small", "galmart", "arbuz", "clever", "anvar", "food city")
                to Categories.GROCERIES.slug,
            listOf("glovo", "wolt", "chocofood", "yandex eats", "kfc", "starbucks", "bahandi", "burger", "coffee", "кофе")
                to Categories.CAFE.slug,
            listOf("yandex go", "indrive", "in drive", "bolt", "onay", "такси", "taxi")
                to Categories.TRANSPORT.slug,
            listOf("helios", "sinooil", "qazaq oil", "казмунайгаз", "kazmunaygaz", "азс")
                to Categories.FUEL.slug,
            listOf("kaspi магазин", "kaspi shop", "wildberries", "ozon", "mechta", "sulpak", "technodom", "marwin")
                to Categories.SHOPPING.slug,
            listOf("steam", "netflix", "spotify", "youtube", "playstation", "yandex plus", "apple.com", "google")
                to Categories.ENTERTAINMENT.slug,
            listOf("europharma", "аптека", "pharm", "biosfera", "биосфера", "sadykhan")
                to Categories.HEALTH.slug,
            listOf("beeline", "activ", "tele2", "kcell", "izet", "altel")
                to Categories.TELECOM.slug
        )
    }
}
