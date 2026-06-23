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
     * @param balanceTiyn ТЕКУЩИЙ (живой) остаток — траты сегодня из него уже списаны
     *   (см. автосписание в репозитории). Дневной лимит считается от остатка на НАЧАЛО
     *   дня, поэтому внутри сегодняшние траты возвращаются обратно (`balance + spentToday`):
     *   иначе трата уменьшила бы и сам лимит (`daily`), и остаток на сегодня — двойной счёт.
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
        // Остаток на начало дня = живой остаток + уже списанные сегодня траты.
        // Так daily стабилен в течение дня, а трата вычитается ровно один раз (в remaining).
        val startOfDayBalance = balanceTiyn + spentTodayTiyn
        val disposable = startOfDayBalance - obligatoryTiyn
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
