package kz.sultan.spendlimit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kz.sultan.spendlimit.data.local.dao.CategorySum
import kz.sultan.spendlimit.data.prefs.ThemeMode
import kz.sultan.spendlimit.data.repository.CategoryBudgetStatus
import kz.sultan.spendlimit.data.repository.DayTotal
import kz.sultan.spendlimit.domain.category.Categories
import kz.sultan.spendlimit.domain.model.Transaction
import kz.sultan.spendlimit.domain.model.TransactionType
import kz.sultan.spendlimit.ui.theme.SpendLimitTheme
import kz.sultan.spendlimit.ui.theme.positiveColor
import kz.sultan.spendlimit.util.Money
import kz.sultan.spendlimit.util.SystemPermissions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            SpendLimitTheme(themeMode) {
                AppRoot(viewModel)
            }
        }
    }
}

private enum class Screen { MAIN, RECORDS, STATS, BUDGETS }

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    // Простое переключение экранов состоянием — без зависимости navigation-compose:
    // надёжнее и проще для нескольких экранов.
    var screen by remember { mutableStateOf(Screen.MAIN) }
    when (screen) {
        Screen.MAIN -> MainScreen(
            viewModel,
            onOpenRecords = { screen = Screen.RECORDS },
            onOpenStats = { screen = Screen.STATS }
        )
        Screen.RECORDS -> RecordsScreen(viewModel, onBack = { screen = Screen.MAIN })
        Screen.STATS -> StatsScreen(
            viewModel,
            onBack = { screen = Screen.MAIN },
            onOpenBudgets = { screen = Screen.BUDGETS }
        )
        Screen.BUDGETS -> BudgetsScreen(viewModel, onBack = { screen = Screen.STATS })
    }
}

// ---------------------------------------------------------------------------
// Главный экран — витрина: лимит + траты за сегодня (без интерактивных жестов).
// Управление записями вынесено на отдельный экран «Записи».
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onOpenRecords: () -> Unit,
    onOpenStats: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // Доступ к уведомлениям/батарее проверяем заново при каждом возврате на экран.
    var notifAccess by remember { mutableStateOf(SystemPermissions.isNotificationAccessGranted(context)) }
    var batteryOk by remember { mutableStateOf(SystemPermissions.isIgnoringBatteryOptimizations(context)) }
    var showAdd by remember { mutableStateOf(false) }
    var showAuth by remember { mutableStateOf(false) }
    var showBalanceEdit by remember { mutableStateOf(false) }
    val authEmail by viewModel.authEmail.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifAccess = SystemPermissions.isNotificationAccessGranted(context)
                batteryOk = SystemPermissions.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Лимит") },
                actions = {
                    val themeMode by viewModel.themeMode.collectAsState()
                    TextButton(onClick = { viewModel.cycleTheme() }) {
                        Text(
                            when (themeMode) {
                                ThemeMode.SYSTEM -> "Тема: системная"
                                ThemeMode.LIGHT -> "Тема: светлая"
                                ThemeMode.DARK -> "Тема: тёмная"
                            }
                        )
                    }
                    TextButton(onClick = onOpenStats) { Text("Статистика") }
                    TextButton(onClick = onOpenRecords) { Text("Записи") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Text("+", fontSize = 28.sp) }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!notifAccess) {
                OnboardingCard(
                    title = "Доступ к уведомлениям",
                    body = "Чтобы автоматически считать траты, дайте доступ к уведомлениям. Читаются только пуши Kaspi.",
                    button = "Открыть настройки",
                    onClick = { SystemPermissions.openNotificationAccessSettings(context) }
                )
            }
            if (!batteryOk) {
                OnboardingCard(
                    title = "Работа в фоне",
                    body = "Отключите оптимизацию батареи, иначе система будет «убивать» сервис и часть трат не попадёт в учёт.",
                    button = "Разрешить",
                    onClick = { SystemPermissions.requestIgnoreBatteryOptimizations(context) }
                )
            }
            if (viewModel.authConfigured) {
                AuthStatusCard(
                    email = authEmail,
                    onLogin = { showAuth = true },
                    onLogout = { viewModel.signOut() }
                )
            }

            if (!state.configured) {
                SettingsForm(onSave = viewModel::saveSettings)
            } else {
                LimitHeader(state, onTapBalance = { showBalanceEdit = true })
                Spacer(Modifier.height(4.dp))
                Text("Траты за сегодня", style = MaterialTheme.typography.titleMedium)
                if (state.todayTransactions.isNotEmpty()) {
                    Text(
                        "Исправить или удалить запись можно на экране «Записи» (кнопка вверху справа)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TodayList(state.todayTransactions, Modifier.weight(1f))
            }
        }
    }

    if (showAdd) {
        AddTransactionDialog(
            onDismiss = { showAdd = false },
            onAdd = { amountTiyn, type, merchant, category, createdAt ->
                viewModel.addManualTransaction(amountTiyn, type, merchant, category, createdAt)
                showAdd = false
            }
        )
    }

    if (showAuth) {
        AuthDialog(
            onDismiss = { showAuth = false },
            onSignIn = { e, p, cb -> viewModel.signIn(e, p, cb) },
            onSignUp = { e, p, cb -> viewModel.signUp(e, p, cb) }
        )
    }

    if (showBalanceEdit) {
        BalanceEditDialog(
            currentTiyn = state.balanceTiyn,
            onDismiss = { showBalanceEdit = false },
            onSave = { tiyn ->
                viewModel.setBalance(tiyn)
                showBalanceEdit = false
            }
        )
    }
}

@Composable
private fun TodayList(items: List<Transaction>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Text("Пока трат нет", style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { tx ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = (tx.merchant ?: typeLabel(tx.type)) + editedMark(tx),
                        modifier = Modifier.weight(1f)
                    )
                    AmountText(tx)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Экран «Записи» — все транзакции, сгруппированы по датам, явные кнопки.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val items by viewModel.allTransactions.collectAsState()
    val categorySums by viewModel.todayCategorySums.collectAsState()
    var pendingEdit by remember { mutableStateOf<Transaction?>(null) }
    var pendingDelete by remember { mutableStateOf<Transaction?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Записи") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Text("+", fontSize = 28.sp) }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Поле поиска показываем, только если вообще есть записи.
            if (items.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Поиск: мерчант, категория, сумма") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Фильтрация в памяти: список уже целиком во Flow, БД не трогаем.
            val filtered = remember(items, query) {
                if (query.isBlank()) items
                else items.filter { tx ->
                    tx.merchant?.contains(query, ignoreCase = true) == true ||
                        Categories.bySlug(tx.category).title.contains(query, ignoreCase = true) ||
                        Money.formatTiyn(tx.amountTiyn).contains(query)
                }
            }

            when {
                items.isEmpty() -> CenteredHint("Записей пока нет")
                filtered.isEmpty() -> CenteredHint("Ничего не найдено")
                else -> {
                    // Уже отсортировано по убыванию created_at → группы от новых к старым.
                    val grouped = remember(filtered) { filtered.groupBy { localDateOf(it.createdAt) } }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Сводку по категориям показываем только без активного поиска.
                        if (query.isBlank() && categorySums.isNotEmpty()) {
                            item(key = "cat_summary") { CategorySummaryCard(categorySums) }
                        }
                        grouped.forEach { (day, dayItems) ->
                            item(key = "header_$day") {
                                Text(
                                    text = dateHeader(day),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                            items(dayItems, key = { it.id }) { tx ->
                                RecordRow(
                                    tx = tx,
                                    onEdit = { pendingEdit = tx },
                                    onDelete = { pendingDelete = tx }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingEdit?.let { tx ->
        EditTransactionDialog(
            tx = tx,
            onDismiss = { pendingEdit = null },
            onSave = { amountTiyn, type, merchant, category ->
                viewModel.updateTransaction(tx.id, amountTiyn, type, merchant, category)
                pendingEdit = null
            }
        )
    }

    pendingDelete?.let { tx ->
        DeleteConfirmDialog(
            tx = tx,
            onConfirm = {
                viewModel.deleteTransaction(tx.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    if (showAdd) {
        AddTransactionDialog(
            onDismiss = { showAdd = false },
            onAdd = { amountTiyn, type, merchant, category, createdAt ->
                viewModel.addManualTransaction(amountTiyn, type, merchant, category, createdAt)
                showAdd = false
            }
        )
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------
// Экран «Статистика» — круговая диаграмма по категориям + тренд по дням.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenBudgets: () -> Unit
) {
    val state by viewModel.statsState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PeriodSelector(state.period, viewModel::setStatsPeriod)

            Text(
                "Всего потрачено: ${Money.formatTiyn(state.totalTiyn)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (state.categorySums.isEmpty()) {
                Text(
                    "За этот период трат нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                CategoryPie(state.categorySums)
                PieLegend(state.categorySums, state.totalTiyn)
            }

            if (state.period != StatsPeriod.DAY && state.dayTotals.isNotEmpty()) {
                Text(
                    "По дням",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TrendBars(state.dayTotals, state.period)
            }

            OutlinedButton(
                onClick = onOpenBudgets,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Лимиты по категориям →")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(selected: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsPeriod.values().forEach { p ->
            FilterChip(
                selected = p == selected,
                onClick = { onSelect(p) },
                label = { Text(periodLabel(p)) }
            )
        }
    }
}

@Composable
private fun CategoryPie(sums: List<CategorySum>) {
    val total = sums.sumOf { it.total }
    if (total <= 0L) return
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(180.dp)) {
            var start = -90f
            sums.forEach { s ->
                val sweep = (s.total.toFloat() / total) * 360f
                drawArc(
                    color = categoryColor(s.category),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = true
                )
                start += sweep
            }
        }
    }
}

@Composable
private fun PieLegend(sums: List<CategorySum>, total: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sums.forEach { s ->
            val c = Categories.bySlug(s.category)
            val pct = if (total > 0L) s.total * 100 / total else 0L
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(categoryColor(s.category), RoundedCornerShape(2.dp))
                    )
                    Text("${c.emoji} ${c.title}")
                }
                Text("${Money.formatTiyn(s.total)} · $pct%", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TrendBars(days: List<DayTotal>, period: StatsPeriod) {
    val max = days.maxOfOrNull { it.totalTiyn } ?: 0L
    val gap = if (period == StatsPeriod.MONTH) 1.dp else 4.dp
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            days.forEach { d ->
                val frac = if (max > 0L) (d.totalTiyn.toFloat() / max) else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(frac.coerceAtLeast(0f))
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                        )
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            days.forEach { d ->
                Text(
                    text = barLabel(d, period),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Палитра для категорий: индекс берём из фиксированного порядка Categories.ALL,
// поэтому цвет каждой категории стабилен между запусками.
private val categoryPalette = listOf(
    Color(0xFF4F8DFD), Color(0xFFFF8A65), Color(0xFF4CAF50), Color(0xFFFFB300),
    Color(0xFFBA68C8), Color(0xFF26C6DA), Color(0xFFEF5350), Color(0xFF8D6E63),
    Color(0xFF9CCC65), Color(0xFF90A4AE)
)

private fun categoryColor(slug: String?): Color {
    val idx = Categories.ALL.indexOfFirst { it.slug == slug }
    return if (idx >= 0) categoryPalette[idx % categoryPalette.size] else categoryPalette.last()
}

private fun periodLabel(p: StatsPeriod): String = when (p) {
    StatsPeriod.DAY -> "День"
    StatsPeriod.WEEK -> "Неделя"
    StatsPeriod.MONTH -> "Месяц"
}

private val weekdayShortFormatter = DateTimeFormatter.ofPattern("EE", Locale("ru"))

private fun barLabel(d: DayTotal, period: StatsPeriod): String = when (period) {
    StatsPeriod.WEEK -> d.date.format(weekdayShortFormatter)
    // На месяце 30 подписей не влезут — показываем 1-е и кратные 5.
    StatsPeriod.MONTH -> if (d.date.dayOfMonth == 1 || d.date.dayOfMonth % 5 == 0) d.date.dayOfMonth.toString() else ""
    StatsPeriod.DAY -> ""
}

// ---------------------------------------------------------------------------
// Экран «Лимиты по категориям» — месячный конвертный бюджет.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val items by viewModel.categoryBudgets.collectAsState()
    var editing by remember { mutableStateOf<CategoryBudgetStatus?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Лимиты по категориям") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "hint") {
                Text(
                    "Месячные лимиты. Прогресс считается по тратам с начала месяца. Нажмите на категорию, чтобы задать или изменить лимит.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(items, key = { it.categorySlug }) { st ->
                BudgetRow(st, onClick = { editing = st })
            }
        }
    }

    editing?.let { st ->
        BudgetEditDialog(
            status = st,
            onDismiss = { editing = null },
            onSave = { tiyn ->
                viewModel.setCategoryBudget(st.categorySlug, tiyn)
                editing = null
            },
            onRemove = {
                viewModel.removeCategoryBudget(st.categorySlug)
                editing = null
            }
        )
    }
}

@Composable
private fun BudgetRow(st: CategoryBudgetStatus, onClick: () -> Unit) {
    val c = Categories.bySlug(st.categorySlug)
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${c.emoji} ${c.title}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (st.hasLimit) {
                    Text(
                        "${Money.formatTiyn(st.spentTiyn)} / ${Money.formatTiyn(st.limitTiyn!!)}",
                        fontWeight = FontWeight.Medium,
                        color = if (st.isExceeded) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                } else {
                    Text(
                        "Нет лимита",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (st.hasLimit) {
                ProgressBar(
                    fraction = st.fraction,
                    exceeded = st.isExceeded
                )
                Text(
                    text = if (st.isExceeded) "Превышен на ${Money.formatTiyn(-st.remainingTiyn)}"
                    else "Осталось ${Money.formatTiyn(st.remainingTiyn)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (st.isExceeded) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (st.spentTiyn > 0L) {
                Text(
                    "Потрачено ${Money.formatTiyn(st.spentTiyn)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Простой прогресс-бар на Box: фон + заполнение, без зависимости от версии material3. */
@Composable
private fun ProgressBar(fraction: Float, exceeded: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(
                    if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(4.dp)
                )
        )
    }
}

@Composable
private fun BudgetEditDialog(
    status: CategoryBudgetStatus,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    onRemove: () -> Unit
) {
    val c = Categories.bySlug(status.categorySlug)
    var input by remember { mutableStateOf(status.limitTiyn?.let { tiynToInput(it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Лимит: ${c.emoji} ${c.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text("Месячный лимит, ₸") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (status.hasLimit) {
                    TextButton(onClick = onRemove) {
                        Text("Снять лимит", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tiyn = tengeToTiyn(input)
                if (tiyn == null || tiyn <= 0L) error = "Введите сумму больше нуля"
                else onSave(tiyn)
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun RecordRow(tx: Transaction, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tx.merchant ?: typeLabel(tx.type),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                AmountText(tx)
            }
            Text(
                text = buildString {
                    append(typeLabel(tx.type))
                    tx.category?.let { slug ->
                        val c = Categories.bySlug(slug)
                        append(" · ").append(c.emoji).append(' ').append(c.title)
                    }
                    if (tx.isManual) append(" · вручную")
                    append(" · ").append(timeOf(tx.createdAt))
                    if (tx.editedAt != null) append(" · изменено")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("Изменить") }
                TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun CategorySummaryCard(sums: List<CategorySum>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Сегодня по категориям",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            sums.forEach { s ->
                val c = Categories.bySlug(s.category)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${c.emoji} ${c.title}")
                    // Суммы пока в KZT: смешение валют появится вместе с конвертацией курса.
                    Text(Money.formatTiyn(s.total), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun AmountText(tx: Transaction) {
    val sign = if (tx.type == TransactionType.INCOME) "+" else "−"
    Text(
        "$sign${Money.formatTiyn(tx.amountTiyn, tx.currency)}",
        fontWeight = FontWeight.SemiBold,
        color = if (tx.type == TransactionType.INCOME) positiveColor else Color.Unspecified
    )
}

// ---------------------------------------------------------------------------
// Диалоги (переиспользуются обоими экранами).
// ---------------------------------------------------------------------------

@Composable
private fun EditTransactionDialog(
    tx: Transaction,
    onDismiss: () -> Unit,
    onSave: (Long, TransactionType, String?, String?) -> Unit
) {
    var amount by remember { mutableStateOf(tiynToInput(tx.amountTiyn)) }
    var type by remember { mutableStateOf(tx.type) }
    var merchant by remember { mutableStateOf(tx.merchant ?: "") }
    // null = «Без категории»; выбор пользователя запоминается как правило в репозитории.
    var category by remember { mutableStateOf(tx.category) }
    var categoryMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать запись") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; error = null },
                    label = { Text("Сумма, ₸") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Тип операции", style = MaterialTheme.typography.labelLarge)
                TransactionType.entries.forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = type == t, onClick = { type = t })
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(typeLabel(t))
                    }
                }
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Мерчант / описание (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Категория", style = MaterialTheme.typography.labelLarge)
                Box {
                    val current = Categories.bySlug(category)
                    OutlinedButton(
                        onClick = { categoryMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("${current.emoji} ${current.title}") }
                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = { categoryMenu = false }
                    ) {
                        Categories.ALL.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.emoji} ${c.title}") },
                                onClick = {
                                    // «Без категории» храним как null, чтобы не плодить правило.
                                    category = if (c.slug == Categories.UNCATEGORIZED.slug) null else c.slug
                                    categoryMenu = false
                                }
                            )
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tiyn = Money.parseToTiyn(amount)
                if (tiyn == null || tiyn <= 0) {
                    error = "Введите корректную сумму больше нуля"
                } else {
                    onSave(tiyn, type, merchant.ifBlank { null }, category)
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTransactionDialog(
    onDismiss: () -> Unit,
    onAdd: (Long, TransactionType, String?, String?, Long) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.PURCHASE) }
    var merchant by remember { mutableStateOf("") }
    // null = «Без категории»; если оставить, репозиторий прогонит через Categorizer по мерчанту.
    var category by remember { mutableStateOf<String?>(null) }
    var categoryMenu by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая операция") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; error = null },
                    label = { Text("Сумма, ₸") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Тип операции", style = MaterialTheme.typography.labelLarge)
                TransactionType.entries.forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = type == t, onClick = { type = t })
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(typeLabel(t))
                    }
                }
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Мерчант / описание (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Категория", style = MaterialTheme.typography.labelLarge)
                Box {
                    val current = Categories.bySlug(category)
                    OutlinedButton(
                        onClick = { categoryMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("${current.emoji} ${current.title}") }
                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = { categoryMenu = false }
                    ) {
                        Categories.ALL.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.emoji} ${c.title}") },
                                onClick = {
                                    category = if (c.slug == Categories.UNCATEGORIZED.slug) null else c.slug
                                    categoryMenu = false
                                }
                            )
                        }
                    }
                }
                Text("Дата", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(date.format(dateFormatter)) }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tiyn = Money.parseToTiyn(amount)
                if (tiyn == null || tiyn <= 0) {
                    error = "Введите корректную сумму больше нуля"
                } else {
                    // Дата от пользователя + текущее время суток.
                    val createdAt = date.atTime(LocalTime.now())
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onAdd(tiyn, type, merchant.ifBlank { null }, category, createdAt)
                }
            }) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )

    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Отмена") }
            }
        ) {
            DatePicker(state = dpState)
        }
    }
}

@Composable
private fun DeleteConfirmDialog(tx: Transaction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val label = tx.merchant ?: typeLabel(tx.type)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить запись?") },
        text = { Text("«$label» на ${Money.formatTiyn(tx.amountTiyn, tx.currency)} будет удалена. Лимит пересчитается автоматически.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

// ---------------------------------------------------------------------------
// Лимит, настройки, онбординг.
// ---------------------------------------------------------------------------

@Composable
private fun LimitHeader(state: MainUiState, onTapBalance: () -> Unit) {
    val limit = state.limit ?: return
    val color = if (limit.isExceeded) MaterialTheme.colorScheme.error else positiveColor
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Остаток счёта — вторичный контекст над лимитом; тап открывает ручную сверку.
            Text(
                text = "На счету: ${Money.formatTiyn(state.balanceTiyn)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onTapBalance)
                    .padding(4.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (limit.isExceeded) "Превышено на" else "Остаток на сегодня",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            val shown = if (limit.isExceeded) -limit.remainingTodayTiyn else limit.remainingTodayTiyn
            Text(
                text = Money.formatTiyn(shown),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Дневной лимит ${Money.formatTiyn(limit.dailyLimitTiyn)} · " +
                    "потрачено ${Money.formatTiyn(limit.spentTodayTiyn)} · " +
                    "осталось ${limit.daysToCover} дн. до поступления",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BalanceEditDialog(
    currentTiyn: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    var value by remember { mutableStateOf(tiynToInput(currentTiyn)) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сверка остатка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text("Фактический остаток, ₸") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Сверь с балансом карты в Kaspi и поправь, если разошлось.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tiyn = Money.parseToTiyn(value)
                if (tiyn == null || tiyn < 0) {
                    error = "Введите корректный остаток (0 или больше)"
                } else {
                    onSave(tiyn)
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun SettingsForm(onSave: (Long, Long, LocalDate) -> Unit) {
    var balance by remember { mutableStateOf("") }
    var obligatory by remember { mutableStateOf("") }
    var incomeDate by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Настройка лимита", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = balance, onValueChange = { balance = it },
                label = { Text("Остаток (обновляется автоматически, можно поправить вручную), ₸") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = obligatory, onValueChange = { obligatory = it },
                label = { Text("Обязательные платежи до конца периода, ₸") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = incomeDate, onValueChange = { incomeDate = it },
                label = { Text("Дата следующего поступления (ГГГГ-ММ-ДД)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    val b = tengeToTiyn(balance)
                    val o = tengeToTiyn(obligatory)
                    val d = runCatching { LocalDate.parse(incomeDate, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
                    when {
                        b == null || b <= 0 -> error = "Введите корректный остаток"
                        o == null -> error = "Введите обязательные платежи (0 если нет)"
                        d == null -> error = "Дата в формате ГГГГ-ММ-ДД"
                        else -> { error = null; onSave(b, o, d) }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
    }
}

@Composable
private fun OnboardingCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun AuthStatusCard(email: String?, onLogin: () -> Unit, onLogout: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (email == null) {
                Text("Облачный бэкап", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Войдите, чтобы записи сохранялись в облако и восстанавливались на другом устройстве.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onLogin) { Text("Войти") }
            } else {
                Text("Бэкап включён", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onLogout) { Text("Выйти") }
            }
        }
    }
}

@Composable
private fun AuthDialog(
    onDismiss: () -> Unit,
    onSignIn: (String, String, (String?) -> Unit) -> Unit,
    onSignUp: (String, String, (String?) -> Unit) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit(action: (String, String, (String?) -> Unit) -> Unit) {
        if (email.isBlank() || password.length < 6) {
            error = "Введите email и пароль (минимум 6 символов)"
            return
        }
        busy = true
        error = null
        action(email, password) { err ->
            busy = false
            if (err == null) onDismiss() else error = err
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Вход в аккаунт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "«Зарегистрироваться» создаёт новый аккаунт. Может потребоваться подтверждение email.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { submit(onSignIn) }) { Text("Войти") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { submit(onSignUp) }) { Text("Зарегистрироваться") }
        }
    )
}

// ---------------------------------------------------------------------------
// Хелперы.
// ---------------------------------------------------------------------------

private fun typeLabel(type: TransactionType): String = when (type) {
    TransactionType.PURCHASE -> "Покупка"
    TransactionType.TRANSFER -> "Перевод"
    TransactionType.INCOME -> "Пополнение"
}

private fun editedMark(tx: Transaction): String = if (tx.editedAt != null) " ·ред." else ""

/** Тенге из строки -> тиыны. Допускает запятую/точку и пробелы. */
private fun tengeToTiyn(input: String): Long? = Money.parseToTiyn(input)

/** Тиыны -> строка для поля ввода: "499050" -> "4990,50", "499000" -> "4990". */
private fun tiynToInput(tiyn: Long): String {
    val whole = tiyn / 100
    val frac = tiyn % 100
    return if (frac == 0L) whole.toString() else "$whole,${frac.toString().padStart(2, '0')}"
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun localDateOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun timeOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormatter)

private fun dateHeader(day: LocalDate): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    return when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> day.format(dateFormatter)
    }
}
