package kz.sultan.spendlimit.domain.voice

import kz.sultan.spendlimit.domain.category.Categories
import java.util.Locale

/**
 * Маппит слово-кандидат категории от модели («еда», «такси», «обед») на внутренний
 * slug из [Categories]. Это «истина данных» на стороне кода: модель не обязана знать
 * наш фиксированный каталог, а её свободное слово приводится к нему здесь.
 *
 * Три слоя: точное совпадение по словарю синонимов → совпадение по подстроке →
 * совпадение по названию категории. Ничего не подошло → null (вызывающий решает,
 * писать «без категории» или переспросить).
 */
object CategoryWordResolver {

    // Синонимы → slug. Ключи в нижнем регистре, по одному слову/короткой фразе.
    private val SYNONYMS: Map<String, String> = buildMap {
        fun add(slug: String, vararg words: String) = words.forEach { put(it, slug) }

        add(Categories.GROCERIES.slug, "еда", "еду", "продукты", "продукт", "бакалея", "магазин еды")
        add(Categories.CAFE.slug, "кафе", "ресторан", "обед", "ужин", "завтрак", "доставка", "кофе", "перекус", "столовая")
        add(Categories.TRANSPORT.slug, "такси", "транспорт", "проезд", "автобус", "метро", "дорога", "поездка")
        add(Categories.FUEL.slug, "бензин", "топливо", "заправка", "азс", "солярка", "дизель")
        add(Categories.SHOPPING.slug, "покупки", "покупка", "шопинг", "одежда", "обувь", "техника", "вещи")
        add(Categories.ENTERTAINMENT.slug, "развлечения", "кино", "игры", "игра", "подписка", "досуг")
        add(Categories.HEALTH.slug, "здоровье", "аптека", "лекарства", "лекарство", "врач", "медицина", "таблетки")
        add(Categories.TELECOM.slug, "связь", "телефон", "интернет", "мобильный", "пополнение баланса")
        add(Categories.TRANSFER.slug, "перевод", "переводы")
    }

    /** @return slug категории или null, если слово не распозналось. */
    fun resolve(word: String?): String? {
        val w = word?.lowercase(Locale.ROOT)?.trim()?.ifBlank { null } ?: return null

        SYNONYMS[w]?.let { return it }
        // Частичное совпадение: «на еду», «обеденный» и т.п.
        SYNONYMS.entries.firstOrNull { (key, _) -> w.contains(key) }?.let { return it.value }
        // По русскому названию категории («продукты», «топливо»).
        Categories.ALL.firstOrNull { it.title.lowercase(Locale.ROOT).contains(w) || w.contains(it.title.lowercase(Locale.ROOT)) }
            ?.let { if (it.slug != Categories.UNCATEGORIZED.slug) return it.slug }

        return null
    }
}
