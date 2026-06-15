package kz.sultan.spendlimit.domain.category

/**
 * Категория траты. Каталог фиксирован в коде (не таблица БД): в transactions.category
 * хранится только [slug], а имя/эмодзи резолвятся отсюда. Пользовательские категории —
 * задел на будущее; пока набор фиксированный.
 */
data class Category(
    val slug: String,
    val title: String,
    val emoji: String
)

object Categories {
    val GROCERIES = Category("groceries", "Продукты", "🛒")
    val CAFE = Category("cafe", "Кафе и доставка", "🍔")
    val TRANSPORT = Category("transport", "Транспорт", "🚕")
    val FUEL = Category("fuel", "Топливо", "⛽")
    val SHOPPING = Category("shopping", "Покупки", "🛍️")
    val ENTERTAINMENT = Category("entertainment", "Развлечения", "🎮")
    val HEALTH = Category("health", "Здоровье", "💊")
    val TELECOM = Category("telecom", "Связь", "📱")
    val TRANSFER = Category("transfer", "Переводы", "🔁")
    val UNCATEGORIZED = Category("uncategorized", "Без категории", "❔")

    /** Все категории для выпадающего списка в UI. */
    val ALL: List<Category> = listOf(
        GROCERIES, CAFE, TRANSPORT, FUEL, SHOPPING,
        ENTERTAINMENT, HEALTH, TELECOM, TRANSFER, UNCATEGORIZED
    )

    private val BY_SLUG: Map<String, Category> = ALL.associateBy { it.slug }

    /** Резолв категории по slug; неизвестный/недетектированный → «Без категории». */
    fun bySlug(slug: String?): Category = BY_SLUG[slug] ?: UNCATEGORIZED
}
