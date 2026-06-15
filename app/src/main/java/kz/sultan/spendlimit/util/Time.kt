package kz.sultan.spendlimit.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Границы локального дня и расчёт дней до поступления.
 * Всё в epoch millis; зона — системная.
 */
object Time {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Начало сегодняшнего дня (00:00 локального времени) в epoch millis. */
    fun startOfTodayMillis(now: Long = System.currentTimeMillis()): Long {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Начало завтрашнего дня — верхняя граница «сегодня» (полуинтервал). */
    fun startOfTomorrowMillis(now: Long = System.currentTimeMillis()): Long {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Начало текущей недели (понедельник 00:00) в epoch millis. */
    fun startOfWeekMillis(now: Long = System.currentTimeMillis()): Long {
        val monday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)
        return monday.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Начало следующей недели — верхняя граница «этой недели» (полуинтервал). */
    fun startOfNextWeekMillis(now: Long = System.currentTimeMillis()): Long {
        val nextMonday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .with(DayOfWeek.MONDAY).plusWeeks(1)
        return nextMonday.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Начало текущего месяца (1-е число 00:00) в epoch millis. */
    fun startOfMonthMillis(now: Long = System.currentTimeMillis()): Long {
        val first = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        return first.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Начало следующего месяца — верхняя граница «этого месяца» (полуинтервал). */
    fun startOfNextMonthMillis(now: Long = System.currentTimeMillis()): Long {
        val firstNext = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .withDayOfMonth(1).plusMonths(1)
        return firstNext.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Сколько дней нужно «прожить» с сегодняшнего дня (включительно) до даты поступления.
     * Если поступление сегодня или в прошлом — возвращаем 1 (защита от деления на ноль).
     */
    fun daysToCover(nextIncomeDate: LocalDate, today: LocalDate = LocalDate.now(zone)): Int {
        val diff = ChronoUnit.DAYS.between(today, nextIncomeDate)
        return if (diff < 1) 1 else diff.toInt()
    }
}
