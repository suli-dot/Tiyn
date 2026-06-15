package kz.sultan.spendlimit.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kz.sultan.spendlimit.data.local.dao.CategoryBudgetDao
import kz.sultan.spendlimit.data.local.dao.MerchantRuleDao
import kz.sultan.spendlimit.data.local.dao.RawNotificationDao
import kz.sultan.spendlimit.data.local.dao.TransactionDao
import kz.sultan.spendlimit.data.local.entity.CategoryBudgetEntity
import kz.sultan.spendlimit.data.local.entity.MerchantRuleEntity
import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity
import kz.sultan.spendlimit.data.local.entity.TransactionEntity

@Database(
    entities = [
        RawNotificationEntity::class,
        TransactionEntity::class,
        MerchantRuleEntity::class,
        CategoryBudgetEntity::class
    ],
    // v2: добавлено transactions.edited_at
    // v3: + таблица merchant_rules (автокатегоризация), + transactions.currency (мультивалютность)
    // v4: + transactions.deleted_at (soft delete)
    // v5: + таблица category_budgets (месячные лимиты по категориям)
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun transactionDao(): TransactionDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun categoryBudgetDao(): CategoryBudgetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Миграция 2→3. Добавляет таблицу правил автокатегоризации и колонку валюты.
         * Данные пользователя (raw_notifications, transactions) сохраняются.
         *
         * DDL обязан совпадать с тем, что Room генерит из @Entity (см. app/schemas/3.json),
         * иначе при старте — IllegalStateException про несоответствие схемы.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merchant_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`merchant_norm` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_merchant_rules_merchant_norm` ON `merchant_rules` (`merchant_norm`)"
                )
                db.execSQL(
                    "ALTER TABLE `transactions` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'KZT'"
                )
            }
        }

        /**
         * Миграция 3→4. Добавляет колонку soft delete. Данные сохраняются.
         * deleted_at nullable, без NOT NULL и без default — совпадает с тем,
         * что Room генерит из @Entity (см. app/schemas/4.json).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `deleted_at` INTEGER")
            }
        }

        /**
         * Миграция 4→5. Добавляет таблицу месячных лимитов по категориям.
         * Существующие данные не затрагиваются.
         *
         * DDL обязан совпадать с тем, что Room генерит из @Entity (см. app/schemas/5.json).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `category_budgets` (" +
                        "`category` TEXT NOT NULL, " +
                        "`limit_tiyn` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`category`))"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spendlimit.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // Страховка вниз на этапе разработки: версия без описанной миграции
                    // пересоздаётся, а не роняет приложение.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
