package com.example.expensetracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Uses the same transfer and classification exclusions as the ledger. */
object BankReports {
    data class Breakdown(val bank: String, val income: Long, val spending: Long, val categories: Map<String, Long>)

    fun forMonth(expenses: List<Expense>, month: YearMonth): List<Breakdown> = expenses
        .filter { YearMonth.from(it.date) == month }
        .groupBy { Banks.effectiveBank(it.bank, it.sourceSnippet) }
        .map { (bank, rows) ->
            val categories = rows.filter { LedgerTotals.spending(listOf(it)) != 0L }
                .groupBy { it.category }
                .mapValues { (_, items) -> LedgerTotals.spending(items) }
                .filterValues { it != 0L }
            Breakdown(bank, LedgerTotals.income(rows), LedgerTotals.spending(rows), categories)
        }.sortedWith(compareBy<Breakdown> { it.bank == Banks.UNKNOWN }.thenBy { it.bank })
}

@Composable
fun BankReportCard(expenses: List<Expense>, month: YearMonth) {
    val rows = BankReports.forMonth(expenses, month)
    val formatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    fun money(value: Long) = formatter.format(java.math.BigDecimal.valueOf(value, 2))
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bank-wise monthly report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")) + " · Change month using arrows above", style = MaterialTheme.typography.bodySmall)
            if (rows.isEmpty()) Text("No bank activity in this month.")
            rows.forEach { row ->
                HorizontalDivider()
                Text(row.bank, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("Income ${money(row.income)}  ·  Expenses ${money(row.spending)}")
                Text("Net ${money(row.income - row.spending)}", style = MaterialTheme.typography.bodyMedium)
                if (row.categories.isNotEmpty()) {
                    Text("Expense categories", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    row.categories.entries.sortedByDescending { it.value }.forEach { (category, amount) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(category, style = MaterialTheme.typography.bodySmall)
                            Text(money(amount), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
