package com.example.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.IOException
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val Ink = Color(0xFF202522)
private val Muted = Color(0xFF777F78)
private val Green = Color(0xFF245D45)
private val Canvas = Color(0xFFF6F7F2)
private val Mint = Color(0xFFE5F0E8)
private const val OPENAI_API_KEY = "PUT_YOUR_OPENAI_API_KEY_HERE"
private const val OPENAI_MODEL = "gpt-4o-mini"
private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
private const val OPENAI_KEY_WARNING = "This app currently uses a hardcoded OpenAI key. For production, move it to a secure backend."
private const val SECTION_SUMMARY = "Summary"
private const val SECTION_PATTERNS = "Key Patterns"
private const val SECTION_RISKS = "Risks"
private const val SECTION_NEXT_STEPS = "Next Steps"

private fun buildFallbackInsights(expenses: List<Expense>): String {
    val expenseOnly = expenses.filter { it.type == "EXPENSE" }
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
    val type: String = "EXPENSE" // "EXPENSE" (Debit) or "INCOME" (Credit)
)

class ExpenseViewModel(private val repository: ExpenseRepository) : ViewModel() {
    var expenses by mutableStateOf<List<Expense>>(emptyList())
        private set

    init { viewModelScope.launch { repository.observeExpenses().collect { expenses = it } } }

    fun addExpense(title: String, category: String, amountPaise: Long, type: String) {
        viewModelScope.launch { repository.addExpense(Expense(UUID.randomUUID().toString(), title, category, amountPaise, LocalDate.now(), type)) }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
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
                item { OverviewCard(vm.expenses) }
                item { CategoryChart(vm.expenses) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Recent activity", style = MaterialTheme.typography.titleLarge, color = Ink, fontWeight = FontWeight.Bold)
                        Text("${YearMonth.now().format(DateTimeFormatter.ofPattern("MMMM yyyy"))}", color = Muted, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (vm.expenses.isEmpty()) {
                    item { Text("No transactions this month yet. Add your first one.", color = Muted, modifier = Modifier.padding(vertical = 12.dp)) }
                } else {
                    items(vm.expenses, key = { it.id }) { expense -> ExpenseRow(expense, onClick = { expenseToDelete = expense }) }
                }
                item {
                    Text("Private by design · stored on this device", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        if (showAdd) AddExpenseDialog(onDismiss = { showAdd = false }, onSave = { title, category, amount, type -> vm.addExpense(title, category, amount, type); showAdd = false })
        if (showAi) AiAnalysisDialog(vm.expenses, onDismiss = { showAi = false })
        if (expenseToDelete != null) {
            AlertDialog(
                onDismissRequest = { expenseToDelete = null },
                title = { Text("Delete transaction", fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to delete \"${expenseToDelete?.title}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        expenseToDelete?.let { vm.deleteExpense(it.id) }
                        expenseToDelete = null
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { expenseToDelete = null }) { Text("Cancel") }
                },
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

@Composable
private fun OverviewCard(expenses: List<Expense>) {
    val currentMonthExpenses = expenses.filter { YearMonth.from(it.date) == YearMonth.now() && it.type == "EXPENSE" }.sumOf { it.amountPaise }
    val currentMonthIncome = expenses.filter { YearMonth.from(it.date) == YearMonth.now() && it.type == "INCOME" }.sumOf { it.amountPaise }
    val netBalance = currentMonthIncome - currentMonthExpenses
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Green), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("THIS MONTH", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("⌄  ${LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM"))}", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelLarge)
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
private fun ExpenseRow(expense: Expense, onClick: () -> Unit) {
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
            Text("${expense.category}  ·  ${expense.date.format(DateTimeFormatter.ofPattern("d MMM"))}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        val amountPrefix = if (isIncome) "+" else "−"
        val amountColor = if (isIncome) Green else Ink
        Text("$amountPrefix${money(expense.amountPaise)}", color = amountColor, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AddExpenseDialog(onDismiss: () -> Unit, onSave: (String, String, Long, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("EXPENSE") }
    var category by remember { mutableStateOf("Food") }
    var categoryMenu by remember { mutableStateOf(false) }
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
        confirmButton = { TextButton(onClick = { if (canSave) onSave(title.trim(), category, amountValue!!.movePointRight(2).longValueExact(), type) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(24.dp)
    )
}

private fun money(paise: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(java.math.BigDecimal.valueOf(paise, 2))

private fun exportCsv(context: android.content.Context, expenses: List<Expense>) {
    val csv = StringBuilder().apply {
        append("Title,Category,Type,Amount (INR),Date\n")
        expenses.forEach { e ->
            val amountInr = java.math.BigDecimal.valueOf(e.amountPaise, 2).toPlainString()
            append("\"${e.title.replace("\"", "\"\"")}\",\"${e.category}\",\"${e.type}\",\"$amountInr\",\"${e.date}\"\n")
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
private fun CategoryChart(expenses: List<Expense>) {
    val expensesOnly = expenses.filter { it.type == "EXPENSE" }
    val totalExpense = expensesOnly.sumOf { it.amountPaise }.coerceAtLeast(1L)
    val byCategory = expensesOnly.groupBy { it.category }.mapValues { (_, list) -> list.sumOf { it.amountPaise } }.entries.sortedByDescending { it.value }

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Category Breakdown Chart", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (byCategory.isEmpty()) {
                Text("No expense data for charts yet.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                byCategory.forEach { (category, amount) ->
                    val fraction = (amount.toFloat() / totalExpense).coerceIn(0f, 1f)
                    val percentage = (fraction * 100).toInt()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(category, color = Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
    val expenseOnly = expenses.filter { it.type == "EXPENSE" }
    val categoryTotals = remember(expenseOnly) {
        expenseOnly.groupBy { it.category }.mapValues { it.value.sumOf { expense -> expense.amountPaise } }
    }

    val loadAnalysis: () -> Unit = {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                analysisResult = callOpenAiApi(expenses)
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
                        Text("OpenAI-powered summary", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(OPENAI_KEY_WARNING, color = Muted, style = MaterialTheme.typography.labelSmall)
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

private suspend fun callOpenAiApi(expenses: List<Expense>): String = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val prompt = buildString {
        appendLine("You are analyzing a personal expense tracker. Return concise, polished insights with clear sections. Use markdown headings for: Summary, Key Patterns, Risks, Next Steps. Keep it readable on mobile.")
        appendLine()
        appendLine("Transactions:")
        expenses.takeLast(40).forEach { expense ->
            appendLine("- ${expense.date}: ${expense.title}, ${expense.category}, ${expense.type}, ${money(expense.amountPaise)}")
        }
    }
    val body = """
        {
          "model": "$OPENAI_MODEL",
          "messages": [
            {"role": "system", "content": "You write concise financial insights for a mobile app."},
            {"role": "user", "content": ${JSONObject.quote(prompt)}}
          ],
          "temperature": 0.4
        }
    """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
        .url(OPENAI_ENDPOINT)
        .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
        .addHeader("Content-Type", "application/json")
        .post(body)
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("OpenAI request failed: ${response.code}")
        val json = JSONObject(response.body?.string().orEmpty())
        json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}

@Composable
private fun SummaryStrip(expenses: List<Expense>) {
    val expenseOnly = expenses.filter { it.type == "EXPENSE" }
    val totalSpent = expenseOnly.sumOf { it.amountPaise }
    val totalIncome = expenses.filter { it.type == "INCOME" }.sumOf { it.amountPaise }
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









