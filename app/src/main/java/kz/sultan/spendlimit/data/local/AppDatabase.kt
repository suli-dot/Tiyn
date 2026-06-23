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
    // v6: category_budgets — лимиты по периодам (day/week/month) вместо одного limit_tiyn
    // v7: + raw_notifications.dedup_key (+индекс) — дедуп повторной доставки одного пуша
    version = 7,
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

        /**
         * Миграция 5→6. Переводит category_budgets с одного месячного лимита на три
         * по периодам (день/неделя/месяц). SQLite не умеет менять колонку — пересоздаём
         * таблицу, перенося старый limit_tiyn в limit_month_tiyn (прежние лимиты не теряются).
         *
         * DDL обязан совпадать с тем, что Room генерит из @Entity (см. app/schemas/6.json).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `category_budgets_new` (" +
                        "`category` TEXT NOT NULL, " +
                        "`limit_day_tiyn` INTEGER, " +
                        "`limit_week_tiyn` INTEGER, " +
                        "`limit_month_tiyn` INTEGER, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`category`))"
                )
                db.execSQL(
                    "INSERT INTO `category_budgets_new` " +
                        "(`category`, `limit_month_tiyn`, `updated_at`) " +
                        "SELECT `category`, `limit_tiyn`, `updated_at` FROM `category_budgets`"
                )
                db.execSQL("DROP TABLE `category_budgets`")
                db.execSQL("ALTER TABLE `category_budgets_new` RENAME TO `category_budgets`")
            }
        }

        /**
         * Миграция 6→7. Добавляет колонку дедупа повторной доставки пушей и индекс по ней.
         * Существующие данные сохраняются (dedup_key у старых записей = NULL).
         *
         * DDL обязан совпадать с тем, что Room генерит из @Entity (см. app/schemas/7.json):
         * имя индекса по умолчанию — `index_raw_notifications_dedup_key`.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `raw_notifications` ADD COLUMN `dedup_key` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_raw_notifications_dedup_key` " +
                        "ON `raw_notifications` (`dedup_key`)"
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
                    .addMigrations(
                        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
                    )
                    // fallbackToDestructiveMigration НЕ используем: при отсутствии миграции
                    // лучше явный IllegalStateException на разработке, чем тихое стирание
                    // данных пользователя в релизе. Любая новая версия схемы обязана
                    // получить свою Migration в addMigrations выше.
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
