package com.example.expensetracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val Ink = Color(0xFF202522)
private val Muted = Color(0xFF777F78)
private val Green = Color(0xFF245D45)
private val Canvas = Color(0xFFF6F7F2)
private val Mint = Color(0xFFE5F0E8)
private const val SECTION_SUMMARY = "Summary"
private const val SECTION_PATTERNS = "Key Patterns"
private const val SECTION_RISKS = "Risks"
private const val SECTION_NEXT_STEPS = "Next Steps"

private fun buildFallbackInsights(expenses: List<Expense>): String {
    val expenseOnly = expenses.filter { LedgerTotals.spending(listOf(it)) > 0L }
    val totalSpent = expenseOnly.sumOf { it.amountPaise }
    val topCategory = expenseOnly.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key ?: "No spending yet"
    val avgExpense = if (expenseOnly.isNotEmpty()) totalSpent / expenseOnly.size else 0L
    return """
        Spending snapshot
        • Total spending: ${money(totalSpent)}
        • Top category: $topCategory
        • Transactions counted: ${expenseOnly.size}

        Practical next step
        • Keep your highest category under control.
        • Your average expense is ${money(avgExpense)}.
    """.trimIndent()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(246, 247, 242)
        window.navigationBarColor = android.graphics.Color.rgb(246, 247, 242)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val repository = ExpenseRepository(ExpenseDatabase.open(this).expenseDao(), ExpenseCipher())
        setContent { PocketLedgerApp(repository) }
    }
}

data class Expense(
    val id: String,
    val title: String,
    val category: String,
    val amountPaise: Long,
    val date: LocalDate,
    val type: String = "EXPENSE", // "EXPENSE" (Debit) or "INCOME" (Credit)
    val paymentMethod: String = PaymentMethods.UNKNOWN,
    val bank: String = Banks.UNKNOWN,
    val selfTransfer: Boolean = false,
    val timestampMillis: Long = 0L,
    val sourceSnippet: String = "",
    val classification: String = IncomeClassifications.UNREVIEWED
)

class ExpenseViewModel(private val repository: ExpenseRepository) : ViewModel() {
    var expenses by mutableStateOf<List<Expense>>(emptyList())
        private set

    var reviews by mutableStateOf<List<Pair<String, SmsTransactionCandidate>>>(emptyList())
        private set

    init {
        viewModelScope.launch { repository.observeExpenses().collect { expenses = it } }
        viewModelScope.launch { repository.observeReviews().collect { reviews = it } }
    }

    suspend fun knownSms(): Set<String> = repository.knownFingerprints()
    suspend fun saveReviews(items: List<Pair<String, SmsTransactionCandidate>>) = repository.addReviews(items)
    fun decideReview(fp: String, candidate: SmsTransactionCandidate, accept: Boolean) {
        viewModelScope.launch { repository.decideReview(fp, candidate, accept) }
    }

    fun approveEligibleReviews() { viewModelScope.launch { repository.approveEligibleReviews() } }

    fun updateExpense(id: String, bank: String, category: String, classification: String) {
        viewModelScope.launch { repository.updateExpense(id, bank, category, classification) }
    }

    fun addExpense(title: String, category: String, amountPaise: Long, type: String, bank: String) {
        viewModelScope.launch { repository.addExpense(Expense(UUID.randomUUID().toString(), title, category, amountPaise, LocalDate.now(), type, bank = bank)) }
    }

    suspend fun importSmsTransactions(transactions: List<SmsTransactionCandidate>): Pair<Int, Int> {
        val imported = repository.addSmsExpenses(transactions)
        return imported to (transactions.size - imported)
    }

    fun setSelfTransfer(id: String, confirmed: Boolean) {
        viewModelScope.launch { repository.setSelfTransfer(id, confirmed) }
    }

    fun deleteExpense(id: String) {
        viewModelScope.launch { repository.deleteExpense(id) }
    }
}

@Composable
private fun PocketLedgerApp(repository: ExpenseRepository) {
    val vm: ExpenseViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ExpenseViewModel(repository) as T
    })
    val context = LocalContext.current
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    val syncPrefs = remember(context) { context.getSharedPreferences("sms_sync_settings", android.content.Context.MODE_PRIVATE) }
    var syncStartMonth by remember { mutableStateOf(runCatching { YearMonth.parse(syncPrefs.getString("start_month", "2024-12")) }.getOrDefault(YearMonth.of(2024, 12))) }
    var showSyncMonthPicker by remember { mutableStateOf(false) }
    val monthlyExpenses = vm.expenses.filter { YearMonth.from(it.date) == selectedMonth }
    var showAdd by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    val suggestedTransferIds = remember(vm.expenses) { findTransferSuggestions(vm.expenses) }
    var smsStatus by remember { mutableStateOf("Tap Sync SMS to scan your inbox on this device.") }
    var smsSyncing by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    var showApproveConfirmation by remember { mutableStateOf(false) }
    var bankFilter by remember { mutableStateOf("All banks") }
    var categoryFilter by remember { mutableStateOf("All categories") }
    var classFilter by remember { mutableStateOf("All classifications") }
    var showAiConsent by remember { mutableStateOf(false) }
    var aiKey by remember { mutableStateOf("") }
    var aiOnNextSync by remember { mutableStateOf(false) }
    fun startSmsSync() {
        if (smsSyncing) return
        smsSyncing = true
        smsStatus = "Sync SMS in progress… Reading inbox and importing transactions."
        vm.viewModelScope.launch {
            var scanned = 0
            var recognized = 0
            var imported = 0
            var errors = 0
            var permissionDenied = false
            try {
                withContext(Dispatchers.IO) {
                    val cutoff = syncStartMonth.atDay(1)
                    val messages = SmsReader.readInbox(context.applicationContext).filter { !it.date.isBefore(cutoff) && !it.date.isAfter(LocalDate.now()) }
                    scanned = messages.size
                    val candidates = mutableListOf<SmsTransactionCandidate>()
                    val reviews = mutableListOf<Pair<String, SmsTransactionCandidate>>()
                    val known = vm.knownSms().toMutableSet()
                    val aiClient = SmsOpenAiClient()
                    messages.forEach { message ->
                        try {
                            val fp = message.fingerprint()
                            if (!known.add(fp)) return@forEach
                            val decision = SmsAgent.classify(message)
                            when (decision.kind) {
                                SmsAgent.Kind.TRANSACTION -> decision.candidate?.let { candidate ->
                                    if (ReviewEligibility.eligible(candidate)) candidates.add(candidate.copy(smsFingerprint = fp))
                                    // Excluded or ambiguous SMS must never enter review.
                                }
                                SmsAgent.Kind.REVIEW -> {
                                    val aiSuggestion = if (aiOnNextSync && aiKey.isNotBlank()) aiClient.suggest(message, aiKey) else null
                                    val candidate = aiSuggestion ?: decision.candidate
                                    if (candidate != null && ReviewEligibility.eligible(candidate)) reviews.add(fp to candidate)
                                }
                                SmsAgent.Kind.IGNORE -> Unit
                            }
                        } catch (_: Exception) { errors++ }
                    }
                    recognized = candidates.size + reviews.size
                    imported = vm.importSmsTransactions(candidates).first
                    vm.saveReviews(reviews)
                }
            } catch (_: SecurityException) {
                permissionDenied = true
                errors++
            } catch (_: Exception) {
                errors++
            } finally {
                val skipped = (recognized - imported).coerceAtLeast(0)
                smsStatus = "SMS sync ${if (permissionDenied) "permission denied" else if (errors > 0) "completed with errors" else "complete"}. " +
                    "Inbox scanned: $scanned · Bank transactions recognized: $recognized · " +
                    "Newly imported: $imported · Duplicates skipped: $skipped · Errors: $errors" +
                    if (permissionDenied) ". Grant SMS permission in Android Settings to retry." else ""
                smsSyncing = false
                aiOnNextSync = false
                aiKey = ""
            }
        }
    }
    val smsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSmsSync() else {
            smsStatus = "SMS sync permission denied. Inbox scanned: 0 · Bank transactions recognized: 0 · Newly imported: 0 · Duplicates skipped: 0 · Errors: 0"
        }
    }
    fun requestSmsImport() {
        if (smsSyncing) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            startSmsSync()
        } else {
            smsStatus = "SMS permission required. Waiting for permission…"
            smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Canvas, surface = Color.White)) {
        Scaffold(
            containerColor = Canvas,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true }, containerColor = Green, contentColor = Color.White,
                    icon = { Text("＋", style = MaterialTheme.typography.titleLarge) }, text = { Text("Add transaction", fontWeight = FontWeight.SemiBold) }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("POCKET LEDGER", style = MaterialTheme.typography.labelMedium, color = Green, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                            Text("Your money, in focus.", style = MaterialTheme.typography.headlineSmall, color = Ink, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(Mint, CircleShape)
                                    .clickable { showAi = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✨", color = Green, style = MaterialTheme.typography.titleMedium)
                            }
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(Mint, CircleShape)
                                    .clickable { exportCsv(context, vm.expenses) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📥", color = Green, style = MaterialTheme.typography.titleMedium)
                            }
                            Box(Modifier.size(44.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                                Text("₹", color = Green, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { showSyncMonthPicker = true }, enabled = !smsSyncing, modifier = Modifier.fillMaxWidth()) {
                        Text("Sync SMS from ${syncStartMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))} (inclusive) · Change")
                    }
                }
                item {
                    Button(
                        onClick = { requestSmsImport() },
                        enabled = !smsSyncing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) {
                        Text(if (smsSyncing) "Syncing SMS…" else "Sync SMS", fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    OutlinedButton(onClick = { showReview = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Review SMS suggestions (${vm.reviews.size})")
                    }
                    TextButton(onClick = { showAiConsent = true }, enabled = !smsSyncing) {
                        Text("Optional OpenAI-assisted SMS review (off by default)")
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("SMS sync status", color = Ink, fontWeight = FontWeight.Bold)
                            if (smsSyncing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(smsStatus, color = Ink, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedMonth = selectedMonth.minusMonths(1) }) {
                            Text("‹", color = Green, style = MaterialTheme.typography.headlineMedium)
                        }
                        Text(selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")), color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { selectedMonth = selectedMonth.plusMonths(1) }) {
                            Text("›", color = Green, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                item { OverviewCard(monthlyExpenses, selectedMonth) }
                item { BankReportCard(monthlyExpenses, selectedMonth) }
                item { PaymentMethodChart(monthlyExpenses) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Filter ledger", fontWeight = FontWeight.Bold)
                        ChoiceSelector("Bank", bankFilter, listOf("All banks") + monthlyExpenses.map { it.bank }.distinct().sorted()) { bankFilter = it }
                        ChoiceSelector("Category", categoryFilter, listOf("All categories") + monthlyExpenses.map { it.category }.distinct().sorted()) { categoryFilter = it }
                        ChoiceSelector("Classification", classFilter, listOf("All classifications") + listOf(IncomeClassifications.UNREVIEWED) + IncomeClassifications.options) { classFilter = it }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Recent activity", style = MaterialTheme.typography.titleLarge, color = Ink, fontWeight = FontWeight.Bold)
                        Text(selectedMonth.format(DateTimeFormatter.ofPattern("MMM yyyy")), color = Muted, style = MaterialTheme.typography.labelLarge)
                    }
                }
                val visibleExpenses = monthlyExpenses.filter {
                    (bankFilter == "All banks" || it.bank == bankFilter) &&
                        (categoryFilter == "All categories" || it.category == categoryFilter) &&
                        (classFilter == "All classifications" || it.classification == classFilter)
                }
                if (visibleExpenses.isEmpty()) {
                    item { Text("No transactions match the selected filters.", color = Muted, modifier = Modifier.padding(vertical = 12.dp)) }
                } else {
                    items(visibleExpenses, key = { it.id }) { expense -> ExpenseRow(expense, suggestedTransferIds.contains(expense.id), onClick = { expenseToDelete = expense }) }
                }
                item {
                    Text("Private by design · stored on this device", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        if (showReview) {
            val eligible = vm.reviews.count { ReviewEligibility.eligible(it.second) }
            AlertDialog(onDismissRequest = { showReview = false }, title = { Text("Review suggested transactions") },
                text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (vm.reviews.isEmpty()) Text("No pending suggestions.")
                    vm.reviews.forEach { (fp, candidate) ->
                        var selectedBank by remember(fp, candidate.bank) { mutableStateOf(candidate.bank) }
                        Text("${candidate.date} · ${candidate.type} · ${money(candidate.amountPaise)} · ${candidate.paymentMethod}")
                        Text(candidate.title, style = MaterialTheme.typography.bodySmall)
                        ChoiceSelector("Bank", selectedBank, Banks.options) { selectedBank = it }
                        Row {
                            TextButton(enabled = ReviewEligibility.eligible(candidate), onClick = { vm.decideReview(fp, candidate.copy(bank = selectedBank), true) }) { Text("Accept") }
                            TextButton(onClick = { vm.decideReview(fp, candidate, false) }) { Text("Reject") }
                        }
                        if (!ReviewEligibility.eligible(candidate)) Text("Uncertain: cannot approve automatically. Reject or review the original SMS.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                } }, confirmButton = {
                    Row {
                        TextButton(enabled = eligible > 0, onClick = { showApproveConfirmation = true }) { Text("Approve all ($eligible)") }
                        TextButton(onClick = { showReview = false }) { Text("Done") }
                    }
                })
        }
        if (showApproveConfirmation) {
            val count = vm.reviews.count { ReviewEligibility.eligible(it.second) }
            AlertDialog(onDismissRequest = { showApproveConfirmation = false },
                title = { Text("Confirm batch approval") },
                text = { Text("Approve $count clearly identified transactions? Uncertain suggestions will remain pending. Review individual banks before accepting if needed.") },
                confirmButton = { TextButton(enabled = count > 0, onClick = { vm.approveEligibleReviews(); showApproveConfirmation = false }) { Text("Approve $count") } },
                dismissButton = { TextButton(onClick = { showApproveConfirmation = false }) { Text("Cancel") } })
        }
        if (showAiConsent) {
            AlertDialog(onDismissRequest = { showAiConsent = false; aiKey = "" },
                title = { Text("External OpenAI processing") },
                text = { Column {
                    Text("Only unresolved SMS are sent to OpenAI after masking amounts, numbers, identifiers and links. Remaining wording may still reveal sensitive details. OpenAI processes this content externally. No message is sent unless you explicitly opt in for this sync. Suggestions require your review. Your key stays in memory for this sync only.")
                    OutlinedTextField(value = aiKey, onValueChange = { aiKey = it.trim() }, label = { Text("OpenAI API key (not saved)") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                } },
                confirmButton = { TextButton(enabled = aiKey.isNotBlank(), onClick = {
                    aiOnNextSync = true; showAiConsent = false; requestSmsImport()
                }) { Text("Consent & sync") } },
                dismissButton = { TextButton(onClick = { showAiConsent = false; aiKey = "" }) { Text("Cancel") } })
        }
        if (showSyncMonthPicker) {
            SyncStartMonthDialog(syncStartMonth, onDismiss = { showSyncMonthPicker = false }, onSave = { month ->
                syncStartMonth = month
                syncPrefs.edit().putString("start_month", month.toString()).apply()
                showSyncMonthPicker = false
            })
        }
        if (showAdd) AddExpenseDialog(onDismiss = { showAdd = false }, onSave = { title, category, amount, type, bank -> vm.addExpense(title, category, amount, type, bank); showAdd = false })
        if (showAi) AiAnalysisDialog(monthlyExpenses, onDismiss = { showAi = false })
        if (expenseToDelete != null) {
            AlertDialog(
                onDismissRequest = { expenseToDelete = null },
                title = { Text("Transaction options", fontWeight = FontWeight.Bold) },
                text = { expenseToDelete?.let { expense ->
                    var bank by remember(expense.id) { mutableStateOf(expense.bank) }
                    var category by remember(expense.id) { mutableStateOf(expense.category) }
                    var classification by remember(expense.id) { mutableStateOf(expense.classification) }
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${expense.title} · ${money(expense.amountPaise)} · ${expense.type}")
                        ChoiceSelector("Bank", bank, Banks.options) { bank = it }
                        ChoiceSelector("Category", category, if (expense.type == "INCOME") listOf("Income", "Salary", "Freelance", "Investment", "Gift", "Other") else listOf("Food", "Travel", "Shopping", "Bills", "Other")) { category = it }
                        ChoiceSelector("Classification", classification, TransactionRules.options(expense.type)) { classification = it }
                        Button(onClick = { vm.updateExpense(expense.id, bank, category, classification); expenseToDelete = null }) { Text("Save changes") }
                        Text("Self transfers and card bill payments are excluded from totals. Card refunds reduce spending without becoming income.", style = MaterialTheme.typography.bodySmall)
                    }
                } },
                confirmButton = {
                    TextButton(onClick = {
                        expenseToDelete?.let { vm.deleteExpense(it.id) }
                        expenseToDelete = null
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = {
                    Column {
                        TextButton(onClick = {
                            expenseToDelete?.let { vm.setSelfTransfer(it.id, !it.selfTransfer) }
                            expenseToDelete = null
                        }) { Text(if (expenseToDelete?.selfTransfer == true) "Undo Self Transfer" else "Mark as Self Transfer") }
                        TextButton(onClick = { expenseToDelete = null }) { Text("Cancel") }
                    }
                },
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

@Composable
private fun OverviewCard(expenses: List<Expense>, selectedMonth: YearMonth) {
    val currentMonthExpenses = LedgerTotals.spending(expenses)
    val currentMonthIncome = LedgerTotals.income(expenses)
    val netBalance = currentMonthIncome - currentMonthExpenses
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Green), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SELECTED MONTH", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(selectedMonth.format(DateTimeFormatter.ofPattern("MMM yyyy")), color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            Text(money(currentMonthExpenses), color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Total recorded spending (Debit)", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(22.dp))
            HorizontalDivider(color = Color.White.copy(alpha = .2f))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("INCOME (CREDIT)", color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelSmall); Text(money(currentMonthIncome), color = Color.White, fontWeight = FontWeight.SemiBold) }
                Column(horizontalAlignment = Alignment.End) { Text("NET", color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelSmall); Text(money(netBalance), color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense, suggested: Boolean, onClick: () -> Unit) {
    val isIncome = expense.type == "INCOME"
    val icon = when (expense.category) { "Food" -> "◒"; "Travel" -> "↗"; "Shopping" -> "▧"; "Bills" -> "⌂"; "Salary" -> "✦"; "Income" -> "✦"; else -> "•" }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).background(Mint, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text(icon, color = Green, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(expense.title, color = Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${expense.category}  ·  ${expense.bank}  ·  ${expense.date.format(DateTimeFormatter.ofPattern("d MMM"))}", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (expense.classification != IncomeClassifications.UNREVIEWED) Text(expense.classification.replace('_', ' '), color = Green, style = MaterialTheme.typography.labelSmall)
            if (expense.selfTransfer) Text("Self Transfer · excluded from totals", color = Green, style = MaterialTheme.typography.labelSmall)
            else if (suggested) Text("Possible self transfer · tap to review", color = Green, style = MaterialTheme.typography.labelSmall)
        }
        val amountPrefix = if (isIncome) "+" else "−"
        val amountColor = if (isIncome) Green else Ink
        Text("$amountPrefix${money(expense.amountPaise)}", color = amountColor, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AddExpenseDialog(onDismiss: () -> Unit, onSave: (String, String, Long, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("EXPENSE") }
    var category by remember { mutableStateOf("Food") }
    var categoryMenu by remember { mutableStateOf(false) }
    var bank by remember { mutableStateOf("") }
    val amountValue = amount.toBigDecimalOrNull()?.setScale(2, java.math.RoundingMode.HALF_UP)
    val canSave = title.isNotBlank() && amountValue != null && amountValue > java.math.BigDecimal.ZERO
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { type = "EXPENSE"; if (category == "Salary") category = "Food" },
                        colors = ButtonDefaults.buttonColors(containerColor = if (type == "EXPENSE") Green else Canvas),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Expense (Debit)", color = if (type == "EXPENSE") Color.White else Ink)
                    }
                    Button(
                        onClick = { type = "INCOME"; category = "Salary" },
                        colors = ButtonDefaults.buttonColors(containerColor = if (type == "INCOME") Green else Canvas),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Income (Credit)", color = if (type == "INCOME") Color.White else Ink)
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(if (type == "INCOME") "What was this income for?" else "What was it for?") }, singleLine = true)
                OutlinedTextField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount (₹)") }, singleLine = true)
                OutlinedTextField(value = bank, onValueChange = { bank = it }, label = { Text("Bank (optional, e.g. HDFC Bank)") }, singleLine = true)
                Text("Unrecognized banks are saved as Unknown bank.", style = MaterialTheme.typography.labelSmall, color = Muted)
                Box {
                    OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("$category  ·  Choose category") }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        val options = if (type == "INCOME") listOf("Salary", "Freelance", "Investment", "Gift", "Other") else listOf("Food", "Travel", "Shopping", "Bills", "Other")
                        options.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { category = option; categoryMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (canSave) onSave(title.trim(), category, amountValue!!.movePointRight(2).longValueExact(), type, Banks.normalize(bank)) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(24.dp)
    )
}

private fun money(paise: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(java.math.BigDecimal.valueOf(paise, 2))

private fun exportCsv(context: android.content.Context, expenses: List<Expense>) {
    val csv = StringBuilder().apply {
        append("Title,Category,Bank,Payment Method,Type,Amount (INR),Date,Self Transfer\n")
        expenses.forEach { e ->
            val amountInr = java.math.BigDecimal.valueOf(e.amountPaise, 2).toPlainString()
            append("\"${e.title.replace("\"", "\"\"")}\",\"${e.category}\",\"${e.bank}\",\"${e.paymentMethod}\",\"${e.type}\",\"$amountInr\",\"${e.date}\",\"${e.selfTransfer}\"\n")
        }
    }.toString()

    val file = java.io.File(context.cacheDir, "pocket_ledger_export.csv")
    file.writeText(csv)

    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Export Transactions to Excel/CSV"))
}

@Composable
private fun PaymentMethodChart(expenses: List<Expense>) {
    val expensesOnly = expenses.filter { LedgerTotals.spending(listOf(it)) != 0L }
    val totalExpense = LedgerTotals.spending(expenses).coerceAtLeast(1L)
    val byMethod = LedgerTotals.spendingByMethod(expenses)

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Payment Method Breakdown", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (expensesOnly.isEmpty()) {
                Text("No spending recorded for the selected month.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                PaymentMethods.all.forEach { method ->
                    val amount = byMethod[method] ?: 0L
                    val fraction = (amount.toFloat() / totalExpense).coerceIn(0f, 1f)
                    val percentage = (fraction * 100).toInt()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(method, color = Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("${money(amount)} ($percentage%)", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = Green,
                            trackColor = Mint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiAnalysisDialog(expenses: List<Expense>, onDismiss: () -> Unit) {
    var analysisResult by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val expenseOnly = expenses.filter { LedgerTotals.spending(listOf(it)) > 0L }
    val categoryTotals = remember(expenseOnly) {
        expenseOnly.groupBy { it.category }.mapValues { it.value.sumOf { expense -> expense.amountPaise } }
    }

    val loadAnalysis: () -> Unit = {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                analysisResult = buildFallbackInsights(expenses)
            } catch (t: Throwable) {
                errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "OpenAI analysis failed"
                analysisResult = buildFallbackInsights(expenses)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadAnalysis() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(40.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                        Text("✨", style = MaterialTheme.typography.titleMedium)
                    }
                    Column {
                        Text("Insights & Analysis", fontWeight = FontWeight.Bold, color = Ink)
                        Text("Selected month spending summary", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        text = {
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                when {
                    isLoading -> {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Green, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Generating insights...", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SummaryStrip(expenses)

                            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Spending by Category", color = Ink, fontWeight = FontWeight.Bold)
                                    if (categoryTotals.isEmpty()) {
                                        Text("No expense data for charts yet.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                                    } else {
                                        val totalSpent = categoryTotals.values.sum().coerceAtLeast(1L)
                                        categoryTotals.entries.sortedByDescending { it.value }.take(6).forEach { (category, amount) ->
                                            val fraction = amount.toFloat() / totalSpent.toFloat()
                                            val percent = (fraction * 100).toInt()
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(category, color = Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                                    Text("${money(amount)}  •  $percent%", color = Muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                Box(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .height(14.dp)
                                                        .clip(RoundedCornerShape(999.dp))
                                                        .background(Mint)
                                                ) {
                                                    Box(
                                                        Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                                            .background(Green)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            errorMessage?.let { message ->
                                Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text(message, color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                                }
                            }

                            analysisResult?.let { result ->
                                val sections = parseInsightSections(result)
                                sections.take(4).forEach { section ->
                                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(section.title, color = Green, fontWeight = FontWeight.Bold)
                                            Text(section.body, color = Ink, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = loadAnalysis) { Text("Refresh", color = Green) }
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Done") }
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

private data class InsightSection(val title: String, val body: String)

private fun parseInsightSections(result: String): List<InsightSection> {
    return result.split("\n\n")
        .mapNotNull { chunk ->
            val lines = chunk.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) null else InsightSection(lines.first().removePrefix("#").trim().ifBlank { "Insight" }, lines.drop(1).joinToString("\n").ifBlank { lines.first() })
        }
}

@Composable
private fun SummaryStrip(expenses: List<Expense>) {
    val expenseOnly = expenses.filter { LedgerTotals.spending(listOf(it)) > 0L }
    val totalSpent = LedgerTotals.spending(expenses)
    val totalIncome = LedgerTotals.income(expenses)
    val topCategory = expenseOnly.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key ?: "—"
    val net = totalIncome - totalSpent

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryChip("Spent", money(totalSpent), Modifier.weight(1f))
            SummaryChip("Income", money(totalIncome), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryChip("Net", money(net), Modifier.weight(1f))
            SummaryChip("Top", topCategory, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = Canvas), shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
