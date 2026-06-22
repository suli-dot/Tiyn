package kz.sultan.spendlimit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Лимиты трат по категории («конвертный» бюджет). Ключ — slug категории
 * (см. [kz.sultan.spendlimit.domain.category.Categories]), одна строка на категорию.
 *
 * Три независимых лимита — день/неделя/месяц. null = лимит на этот период не задан.
 * Если все три null, строка не хранится (репозиторий удаляет её).
 */
@Entity(tableName = "category_budgets")
data class CategoryBudgetEntity(
    @PrimaryKey
    val category: String,

    /** Дневной лимит в тиынах (null = не задан). */
    @ColumnInfo(name = "limit_day_tiyn")
    val limitDayTiyn: Long? = null,

    /** Недельный лимит в тиынах (null = не задан). */
    @ColumnInfo(name = "limit_week_tiyn")
    val limitWeekTiyn: Long? = null,

    /** Месячный лимит в тиынах (null = не задан). */
    @ColumnInfo(name = "limit_month_tiyn")
    val limitMonthTiyn: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
