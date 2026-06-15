package kz.sultan.spendlimit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Месячный лимит трат по категории («конвертный» бюджет).
 * Ключ — slug категории (см. [kz.sultan.spendlimit.domain.category.Categories]),
 * поэтому на категорию может быть только один лимит.
 */
@Entity(tableName = "category_budgets")
data class CategoryBudgetEntity(
    @PrimaryKey
    val category: String,

    /** Месячный лимит в тиынах. */
    @ColumnInfo(name = "limit_tiyn")
    val limitTiyn: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
