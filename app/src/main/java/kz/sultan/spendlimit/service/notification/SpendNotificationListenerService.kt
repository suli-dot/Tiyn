package kz.sultan.spendlimit.service.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kz.sultan.spendlimit.SpendLimitApp
import kz.sultan.spendlimit.data.prefs.SettingsRepository
import kz.sultan.spendlimit.data.repository.FinanceRepository
import kz.sultan.spendlimit.domain.BudgetPeriod
import kz.sultan.spendlimit.domain.SpendingLimitCalculator
import kz.sultan.spendlimit.util.LimitAlertNotifier
import kz.sultan.spendlimit.work.SyncScheduler
import java.time.LocalDate

/**
 * Перехватывает уведомления, фильтрует пуши Kaspi, парсит и сохраняет.
 *
 * Система биндит сервис только после того, как пользователь вручную включит
 * «Доступ к уведомлениям» в настройках. Метод [onNotificationPosted]
 * вызывается в главном потоке — тяжёлую работу уводим в корутину на IO.
 */
class SpendNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repository: FinanceRepository
        get() = (application as SpendLimitApp).container.financeRepository
    private val settings: SettingsRepository
        get() = (application as SpendLimitApp).container.settingsRepository

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!BankParsers.supports(sbn.packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        // bigText полнее, чем text — берём его в приоритете.
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()

        if (text.isNullOrBlank() && title.isNullOrBlank()) return

        val postedAt = sbn.postTime
        val safeText = text ?: title.orEmpty()

        scope.launch {
            try {
                val parsed = BankParsers.parse(
                    packageName = sbn.packageName,
                    title = title,
                    text = safeText
                )
                val tx = repository.ingestNotification(
                    packageName = sbn.packageName,
                    title = title,
                    text = safeText,
                    postedAt = postedAt,
                    parsed = parsed
                )

                if (tx != null && tx.type.isOutgoing) {
                    checkLimit()
                    checkCategoryLimit(tx.category)
                }
                // Триггерим выгрузку новой записи в облако.
                SyncScheduler.requestOneOff(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось обработать уведомление", e)
            }
        }
    }

    /**
     * Считает дневной лимит и шлёт уведомление при достижении порога.
     * Два порога: 80% (предупреждение) и 100% (превышение). Чтобы не спамить на
     * каждую трату, помним показанный сегодня уровень и эскалируем только вверх.
     */
    private suspend fun checkLimit() {
        val s = settings.settings.first()
        val incomeDate = s.nextIncomeDate ?: return // не настроено — нечего считать
        val spentToday = repository.observeTodayOutgoingSum().first()

        val result = SpendingLimitCalculator.compute(
            balanceTiyn = s.balanceTiyn,
            obligatoryTiyn = s.obligatoryTiyn,
            nextIncomeDate = incomeDate,
            spentTodayTiyn = spentToday
        )

        // Уровень достигнутого порога. Непол. дневной лимит = бюджета на день нет → сразу 100%.
        val level = if (result.dailyLimitTiyn <= 0L) {
            100
        } else {
            val ratio = result.spentTodayTiyn.toDouble() / result.dailyLimitTiyn
            when {
                ratio >= 1.0 -> 100
                ratio >= 0.8 -> 80
                else -> 0
            }
        }
        if (level == 0) return

        val today = LocalDate.now().toEpochDay()
        val state = settings.alertState.first()
        val shownToday = if (state.dayEpoch == today) state.level else 0
        if (level <= shownToday) return // этот (или более высокий) порог уже показан сегодня

        when (level) {
            100 -> LimitAlertNotifier.notifyExceeded(applicationContext, -result.remainingTodayTiyn)
            80 -> LimitAlertNotifier.notifyApproaching(applicationContext, result.remainingTodayTiyn)
        }
        settings.setAlertState(today, level)
    }

    /**
     * Проверяет лимиты категории по всем периодам (день/неделя/месяц) для только что
     * добавленной траты. Уведомление — один раз на пару категория+период в её отрезке
     * (дедуп по ключу "slug|PERIOD|bucket").
     */
    private suspend fun checkCategoryLimit(categorySlug: String?) {
        if (categorySlug == null) return
        val status = repository.categoryBudgetStatus(categorySlug) ?: return // лимитов нет

        val exceeded = status.exceededPeriods
        if (exceeded.isEmpty()) return

        val alreadyAlerted = settings.categoryAlerts.first()
        val currentBuckets = BudgetPeriod.currentBuckets()

        for (ps in exceeded) {
            val limit = ps.limitTiyn ?: continue
            val key = "$categorySlug|${ps.period.name}|${ps.period.bucket()}"
            if (key in alreadyAlerted) continue // уже оповещали в этом отрезке

            LimitAlertNotifier.notifyCategoryExceeded(
                applicationContext,
                categorySlug,
                ps.period,
                ps.spentTiyn,
                limit
            )
            settings.addCategoryAlert(key, currentBuckets)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "KaspiListener"
    }
}
