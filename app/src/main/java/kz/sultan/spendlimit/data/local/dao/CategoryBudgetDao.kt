package kz.sultan.spendlimit.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kz.sultan.spendlimit.data.local.entity.CategoryBudgetEntity

@Dao
interface CategoryBudgetDao {

    /** Все заданные лимиты, реактивно — для экрана бюджетов и пересчёта прогресса. */
    @Query("SELECT * FROM category_budgets")
    fun observeAll(): Flow<List<CategoryBudgetEntity>>

    /** Лимит конкретной категории (null = не задан). */
    @Query("SELECT * FROM category_budgets WHERE category = :category LIMIT 1")
    suspend fun find(category: String): CategoryBudgetEntity?

    /** Установка/изменение лимита (конфликт по PK category → REPLACE). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: CategoryBudgetEntity)

    /** Снятие лимита с категории. */
    @Query("DELETE FROM category_budgets WHERE category = :category")
    suspend fun delete(category: String)
}
