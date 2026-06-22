package kz.sultan.spendlimit.domain

import kz.sultan.spendlimit.util.Time
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Период категорийного лимита. Категория может иметь независимые лимиты на день,
 * неделю и месяц одновременно — «исчерпал» считается отдельно по каждому.
 *
 * Неделя — с понедельника (как в [Time] и в экране статистики).
 */
enum class BudgetPeriod(val title: String, val adjective: String) {
    DAY("День", "дневной"),
    WEEK("Неделя", "недельный"),
    MONTH("Месяц", "месячный");

    /** Полуинтервал [from, to) текущего периода в epoch millis. */
    fun range(now: Long = System.currentTimeMillis()): Pair<Long, Long> = when (this) {
        DAY -> Time.startOfTodayMillis(now) to Time.startOfTomorrowMillis(now)
        WEEK -> Time.startOfWeekMillis(now) to Time.startOfNextWeekMillis(now)
        MONTH -> Time.startOfMonthMillis(now) to Time.startOfNextMonthMillis(now)
    }

    /**
     * Идентификатор текущего отрезка периода — ключ дедупликации уведомлений
     * («один раз на исчерпание в этом дне/неделе/месяце»).
     * Для недели берём дату понедельника, чтобы не возиться с ISO-номером недели.
     */
    fun bucket(now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (this) {
            DAY -> date.format(DAY_FMT)
            WEEK -> Instant.ofEpochMilli(Time.startOfWeekMillis(now)).atZone(zone)
                .toLocalDate().format(DAY_FMT)
            MONTH -> date.format(MONTH_FMT)
        }
    }

    companion object {
        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

        /** Текущие bucket-суффиксы всех периодов — для очистки протухших ключей дедупа. */
        fun currentBuckets(now: Long = System.currentTimeMillis()): Set<String> =
            entries.map { it.bucket(now) }.toSet()
    }
}
