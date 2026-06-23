package kz.sultan.spendlimit

import android.content.Context
import kz.sultan.spendlimit.data.backup.BackupRepository
import kz.sultan.spendlimit.data.local.AppDatabase
import kz.sultan.spendlimit.data.prefs.SettingsRepository
import kz.sultan.spendlimit.data.remote.AuthRepository
import kz.sultan.spendlimit.data.remote.nlu.AnthropicIntentResolver
import kz.sultan.spendlimit.data.repository.FinanceRepository
import kz.sultan.spendlimit.data.repository.FinanceRepositoryImpl
import kz.sultan.spendlimit.domain.category.Categorizer
import kz.sultan.spendlimit.domain.voice.IntentResolver
import kz.sultan.spendlimit.domain.voice.VoiceCommandHandler

/**
 * Ручной контейнер зависимостей (service locator).
 * Для скелета этого достаточно; при росте проекта легко заменить на Hilt,
 * т.к. зависимости уже выражены через интерфейсы.
 */
class AppContainer(context: Context) {

    private val database: AppDatabase = AppDatabase.get(context)

    private val categorizer = Categorizer(database.merchantRuleDao())

    val settingsRepository: SettingsRepository = SettingsRepository(context)

    val authRepository: AuthRepository = AuthRepository()

    val backupRepository: BackupRepository = BackupRepository(database, settingsRepository)

    val financeRepository: FinanceRepository = FinanceRepositoryImpl(
        db = database,
        rawDao = database.rawNotificationDao(),
        txDao = database.transactionDao(),
        budgetDao = database.categoryBudgetDao(),
        settings = settingsRepository,
        categorizer = categorizer
    )

    // Голосовой NLU: распознавание (Claude) за интерфейсом, исполнение — в handler.
    val intentResolver: IntentResolver = AnthropicIntentResolver(
        apiKey = BuildConfig.ANTHROPIC_API_KEY,
        model = BuildConfig.ANTHROPIC_MODEL
    )

    val voiceCommandHandler: VoiceCommandHandler =
        VoiceCommandHandler(financeRepository, settingsRepository)
}
