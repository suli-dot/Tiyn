package kz.sultan.spendlimit.domain

import kz.sultan.spendlimit.util.Time
import java.time.LocalDate

/**
 * Прогнозное ядро финансового ассистента: «доживу ли до поступления» и «что будет, если
 * потратить N». Надстройка над [SpendingLimitCalculator] — безопасный дневной лимит считается
 * той же формулой `(остаток − обязательные) / дней`, но движок добавляет ВЗГЛЯД ВПЕРЁД по
 * фактическому темпу трат: на сколько дней хватит денег, в какой день возможен минус, насколько
 * резать траты, чтобы дотянуть.
 *
 * Чистая логика без Android: темп трат [avgDailySpendTiyn] и текущий остаток вычисляет вызывающий
 * слой (история через `spentBetween`, остаток из настроек) — сюда приходят готовые числа.
 *
 * Используется в трёх местах: прогноз на главном экране, сценарий «что-если» ([whatIf]),
 * голосовая команда «можно ли потратить N».
 */
object ForecastEngine {

    /** Уровень риска уйти в минус до поступления. */
    enum class Risk { LOW, MEDIUM, HIGH }

    data class Forecast(
        /** Безопасный дневной лимит (тиыны): свободные деньги / дни. С учётом гипотетической траты, если задана. */
        val safeDailyTiyn: Long,
        /** Дней (включая сегодня) до поступления. */
        val daysToCover: Int,
        /** Свободные деньги на период = остаток на начало дня − обязательные − гипотетическая трата. */
        val disposableTiyn: Long,
        /** Фактический темп трат (тиыны/день) — основа взгляда вперёд. */
        val avgDailySpendTiyn: Long,
        /** Хватит ли денег до поступления при текущем темпе. */
        val willSurvive: Boolean,
        /** На сколько дней (с сегодня) хватит свободных денег при текущем темпе. */
        val daysMoneyLasts: Int,
        /** Дата возможного ухода в минус; null — если хватает до поступления. */
        val runOutDate: LocalDate?,
        /** Насколько урезать траты в день, чтобы дотянуть до поступления (0, если и так хватает). */
        val shortfallPerDayTiyn: Long,
        val risk: Risk
    )

    data class WhatIf(
        /** Прогноз до гипотетической траты. */
        val before: Forecast,
        /** Прогноз после неё. */
        val after: Forecast,
        /** Насколько упадёт безопасный дневной лимит из-за траты (тиыны). */
        val dailyDropTiyn: Long,
        /** Можно ли потратить без перехода в высокий риск минуса. */
        val affordable: Boolean
    )

    /**
     * @param balanceTiyn ТЕКУЩИЙ (живой) остаток — сегодняшние траты из него уже списаны.
     *   Как и в [SpendingLimitCalculator], внутри восстанавливается остаток на начало дня
     *   (`balance + spentToday`), чтобы трата не уменьшала и лимит, и доступное разом.
     * @param obligatoryTiyn обязательные платежи до поступления (резерв, в трату не идут).
     * @param nextIncomeDate дата следующего поступления.
     * @param spentTodayTiyn потрачено сегодня (исходящие).
     * @param avgDailySpendTiyn фактический темп трат (тиыны/день) из истории.
     * @param hypotheticalSpendTiyn разовая трата «прямо сейчас» для сценария «что-если»; 0 — обычный прогноз.
     */
    fun project(
        balanceTiyn: Long,
        obligatoryTiyn: Long,
        nextIncomeDate: LocalDate,
        spentTodayTiyn: Long,
        avgDailySpendTiyn: Long,
        hypotheticalSpendTiyn: Long = 0L,
        today: LocalDate = LocalDate.now()
    ): Forecast {
        val days = Time.daysToCover(nextIncomeDate, today)
        val startOfDayBalance = balanceTiyn + spentTodayTiyn
        // Свободные деньги на период: после обязательных и гипотетической траты.
        val disposable = startOfDayBalance - obligatoryTiyn - hypotheticalSpendTiyn
        val safeDaily = Math.floorDiv(disposable, days.toLong())

        val daysMoneyLasts = when {
            disposable <= 0L -> 0
            avgDailySpendTiyn <= 0L -> days          // не тратит — доживает весь период
            else -> (disposable / avgDailySpendTiyn).coerceAtMost(days.toLong()).toInt()
        }
        val willSurvive = daysMoneyLasts >= days
        val runOutDate = if (willSurvive) null else today.plusDays(daysMoneyLasts.toLong())
        val shortfall = if (willSurvive) 0L else (avgDailySpendTiyn - safeDaily).coerceAtLeast(0L)
        val risk = when {
            willSurvive -> Risk.LOW
            disposable <= 0L || daysMoneyLasts * 2 < days -> Risk.HIGH   // минус сейчас или в первой половине
            else -> Risk.MEDIUM
        }

        return Forecast(
            safeDailyTiyn = safeDaily,
            daysToCover = days,
            disposableTiyn = disposable,
            avgDailySpendTiyn = avgDailySpendTiyn,
            willSurvive = willSurvive,
            daysMoneyLasts = daysMoneyLasts,
            runOutDate = runOutDate,
            shortfallPerDayTiyn = shortfall,
            risk = risk
        )
    }

    /**
     * Сценарий «что будет, если потратить [spendTiyn] прямо сейчас»: сравнивает прогноз до и после.
     * Для §23 (экран «что-если») и голосового «можно ли потратить N».
     */
    fun whatIf(
        balanceTiyn: Long,
        obligatoryTiyn: Long,
        nextIncomeDate: LocalDate,
        spentTodayTiyn: Long,
        avgDailySpendTiyn: Long,
        spendTiyn: Long,
        today: LocalDate = LocalDate.now()
    ): WhatIf {
        val before = project(balanceTiyn, obligatoryTiyn, nextIncomeDate, spentTodayTiyn, avgDailySpendTiyn, 0L, today)
        val after = project(balanceTiyn, obligatoryTiyn, nextIncomeDate, spentTodayTiyn, avgDailySpendTiyn, spendTiyn, today)
        return WhatIf(
            before = before,
            after = after,
            dailyDropTiyn = before.safeDailyTiyn - after.safeDailyTiyn,
            affordable = after.risk != Risk.HIGH
        )
    }
}
