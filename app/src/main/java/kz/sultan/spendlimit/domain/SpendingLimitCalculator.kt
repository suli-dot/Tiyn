package kz.sultan.spendlimit.domain

import kz.sultan.spendlimit.util.Time
import java.time.LocalDate

/**
 * Расчёт дневного лимита трат.
 *
 * Базовая формула:
 *   дневной_лимит = (остаток − обязательные_платежи) / дней_до_поступления
 *
 * Перенос недотрат реализован естественно: расчёт ведётся от ТЕКУЩЕГО остатка.
 * Если вчера потрачено меньше лимита — деньги остались на балансе, значит сегодня
 * (остаток / оставшиеся_дни) автоматически выше. Отдельный «банк переноса» не нужен,
 * пока остаток отражает реальные траты. Условие — поддерживать остаток в актуальном
 * состоянии (вычитать исходящие транзакции; делается на следующем этапе).
 */
object SpendingLimitCalculator {

    data class Result(
        /** Базовый лимит на сегодня (тиыны). */
        val dailyLimitTiyn: Long,
        /** Сколько ещё можно потратить сегодня (тиыны). Может быть отрицательным. */
        val remainingTodayTiyn: Long,
        /** Потрачено сегодня (тиыны). */
        val spentTodayTiyn: Long,
        /** Сколько дней (включая сегодня) надо прожить до поступления. */
        val daysToCover: Int,
        /** Превышен ли дневной лимит. */
        val isExceeded: Boolean
    )

    /**
     * @param balanceTiyn текущий доступный остаток.
     * @param obligatoryTiyn обязательные платежи до конца периода.
     * @param nextIncomeDate дата следующего поступления.
     * @param spentTodayTiyn сумма исходящих трат за сегодня.
     */
    fun compute(
        balanceTiyn: Long,
        obligatoryTiyn: Long,
        nextIncomeDate: LocalDate,
        spentTodayTiyn: Long,
        today: LocalDate = LocalDate.now()
    ): Result {
        val days = Time.daysToCover(nextIncomeDate, today)
        val disposable = balanceTiyn - obligatoryTiyn
        // floorDiv — корректное округление вниз, в т.ч. для отрицательного disposable.
        val daily = Math.floorDiv(disposable, days.toLong())
        val remaining = daily - spentTodayTiyn
        return Result(
            dailyLimitTiyn = daily,
            remainingTodayTiyn = remaining,
            spentTodayTiyn = spentTodayTiyn,
            daysToCover = days,
            isExceeded = remaining < 0
        )
    }
}
