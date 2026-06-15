package kz.sultan.spendlimit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kz.sultan.spendlimit.SpendLimitApp
import kz.sultan.spendlimit.data.local.dao.CategorySum
import kz.sultan.spendlimit.data.prefs.ThemeMode
import kz.sultan.spendlimit.data.prefs.UserSettings
import kz.sultan.spendlimit.data.repository.CategoryBudgetStatus
import kz.sultan.spendlimit.data.repository.DayTotal
import kz.sultan.spendlimit.domain.SpendingLimitCalculator
import kz.sultan.spendlimit.domain.category.Categories
import kz.sultan.spendlimit.domain.model.Transaction
import kz.sultan.spendlimit.domain.model.TransactionType
import kz.sultan.spendlimit.util.Time
import kz.sultan.spendlimit.work.SyncScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class MainUiState(
    val configured: Boolean = false,
    val balanceTiyn: Long = 0L,
    val limit: SpendingLimitCalculator.Result? = null,
    val todayTransactions: List<Transaction> = emptyList()
)

/** Период статистики. [range] — полуинтервал [from, to) в epoch millis. */
enum class StatsPeriod {
    DAY, WEEK, MONTH;

    fun range(): Pair<Long, Long> = when (this) {
        DAY -> Time.startOfTodayMillis() to Time.startOfTomorrowMillis()
        WEEK -> Time.startOfWeekMillis() to Time.startOfNextWeekMillis()
        MONTH -> Time.startOfMonthMillis() to Time.startOfNextMonthMillis()
    }
}

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.WEEK,
    val categorySums: List<CategorySum> = emptyList(),
    val dayTotals: List<DayTotal> = emptyList(),
    val totalTiyn: Long = 0L
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as SpendLimitApp).container
    private val finance = container.financeRepository
    private val settingsRepo = container.settingsRepository
    private val auth = container.authRepository

    val uiState: StateFlow<MainUiState> = combine(
        settingsRepo.settings,
        finance.observeTodayOutgoingSum(),
        finance.observeTodayTransactions()
    ) { settings, spentToday, txs ->
        MainUiState(
            configured = settings.isConfigured,
            balanceTiyn = settings.balanceTiyn,
            limit = settings.nextIncomeDate?.let { date ->
                SpendingLimitCalculator.compute(
                    balanceTiyn = settings.balanceTiyn,
                    obligatoryTiyn = settings.obligatoryTiyn,
                    nextIncomeDate = date,
                    spentTodayTiyn = spentToday
                )
            },
            todayTransactions = txs
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    /** Все транзакции для экрана «Записи» (новые сверху). */
    val allTransactions: StateFlow<List<Transaction>> = finance.observeAllTransactions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Сводка трат по категориям за сегодня — «подглавы» для экрана «Записи». */
    val todayCategorySums: StateFlow<List<CategorySum>> = finance.observeTodayCategorySums()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ---- Статистика (графики) ----

    private val _statsPeriod = MutableStateFlow(StatsPeriod.WEEK)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod

    fun setStatsPeriod(period: StatsPeriod) { _statsPeriod.value = period }

    @OptIn(ExperimentalCoroutinesApi::class)
    val statsState: StateFlow<StatsUiState> = _statsPeriod
        .flatMapLatest { period ->
            val (from, to) = period.range()
            combine(
                finance.observeCategorySums(from, to),
                finance.observeOutgoingByDay(from, to)
            ) { sums, dayTotals ->
                StatsUiState(
                    period = period,
                    categorySums = sums,
                    dayTotals = fillDays(from, to, dayTotals),
                    totalTiyn = sums.sumOf { it.total }
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatsUiState()
        )

    /**
     * Достраивает ось дней нулевыми значениями для дней без трат, чтобы столбики шли
     * подряд. Хвост обрезаем по «завтра» — будущие дни недели/месяца не рисуем.
     */
    private fun fillDays(fromMillis: Long, toMillis: Long, totals: List<DayTotal>): List<DayTotal> {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val endExclusive = minOf(
            Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate(),
            LocalDate.now(zone).plusDays(1)
        )
        val byDate = totals.associateBy { it.date }
        return generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { it < endExclusive }
            .map { DayTotal(it, byDate[it]?.totalTiyn ?: 0L) }
            .toList()
    }

    // ---- Месячные лимиты по категориям ----

    /** Статус бюджета по всем «расходным» категориям (кроме «Без категории»). */
    val categoryBudgets: StateFlow<List<CategoryBudgetStatus>> = combine(
        finance.observeMonthlySpentByCategory(),
        finance.observeCategoryLimits()
    ) { spent, limits ->
        Categories.ALL
            .filter { it.slug != Categories.UNCATEGORIZED.slug }
            .map { c ->
                CategoryBudgetStatus(
                    categorySlug = c.slug,
                    spentTiyn = spent[c.slug] ?: 0L,
                    limitTiyn = limits[c.slug]
                )
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun setCategoryBudget(categorySlug: String, limitTiyn: Long) {
        viewModelScope.launch { finance.setCategoryBudget(categorySlug, limitTiyn) }
    }

    fun removeCategoryBudget(categorySlug: String) {
        viewModelScope.launch { finance.removeCategoryBudget(categorySlug) }
    }

    // ---- Тема оформления ----

    val themeMode: StateFlow<ThemeMode> = settingsRepo.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM
    )

    /** Переключает тему по кругу: Система → Светлая → Тёмная → Система. */
    fun cycleTheme() {
        viewModelScope.launch {
            val next = when (themeMode.value) {
                ThemeMode.SYSTEM -> ThemeMode.LIGHT
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.SYSTEM
            }
            settingsRepo.setThemeMode(next)
        }
    }

    fun saveSettings(balanceTiyn: Long, obligatoryTiyn: Long, nextIncomeDate: LocalDate) {
        viewModelScope.launch {
            settingsRepo.update(balanceTiyn, obligatoryTiyn, nextIncomeDate)
        }
    }

    /** Ручная сверка: перезаписывает остаток фактическим значением (не транзакция). */
    fun setBalance(balanceTiyn: Long) {
        viewModelScope.launch {
            settingsRepo.setBalance(balanceTiyn)
        }
    }

    // ---- Аутентификация для облачного бэкапа ----

    /** Настроен ли Supabase (есть ключи). Без него вход недоступен. */
    val authConfigured: Boolean get() = auth.isConfigured

    /** Email вошедшего пользователя (null = не вошёл). */
    val authEmail: StateFlow<String?> = auth.userEmail.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    /** Вход; onResult(null) — успех, иначе текст ошибки. После входа запускаем выгрузку. */
    fun signIn(email: String, password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                auth.signIn(email.trim(), password)
                SyncScheduler.requestOneOff(getApplication())
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message ?: "Не удалось войти")
            }
        }
    }

    /** Регистрация; onResult(null) — успех, иначе текст ошибки. */
    fun signUp(email: String, password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                auth.signUp(email.trim(), password)
                SyncScheduler.requestOneOff(getApplication())
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message ?: "Не удалось зарегистрироваться")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    /** Добавляет операцию вручную; лимит и сводки пересчитаются через существующие Flow. */
    fun addManualTransaction(
        amountTiyn: Long,
        type: TransactionType,
        merchant: String?,
        category: String?,
        createdAt: Long
    ) {
        viewModelScope.launch {
            finance.addManualTransaction(amountTiyn, type, merchant, category, createdAt)
        }
    }

    /** Удаляет ошибочно распознанную транзакцию; лимит пересчитается автоматически. */
    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            finance.deleteTransaction(id)
        }
    }

    /** Правит транзакцию; лимит и список обновятся через существующие Flow. */
    fun updateTransaction(
        id: Long,
        amountTiyn: Long,
        type: TransactionType,
        merchant: String?,
        category: String?
    ) {
        viewModelScope.launch {
            finance.updateTransaction(id, amountTiyn, type, merchant, category)
        }
    }
}
