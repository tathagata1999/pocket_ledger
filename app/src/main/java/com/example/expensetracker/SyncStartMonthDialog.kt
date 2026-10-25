package com.example.expensetracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun SyncStartMonthDialog(initial: YearMonth, onDismiss: () -> Unit, onSave: (YearMonth) -> Unit) {
    var year by remember { mutableStateOf(initial.year) }
    var month by remember { mutableStateOf(initial.monthValue) }
    var yearMenu by remember { mutableStateOf(false) }
    var monthMenu by remember { mutableStateOf(false) }
    val current = YearMonth.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SMS sync starting month") },
        text = {
            Column {
                Text("Import messages from the first day of this month through today. Older saved transactions stay untouched.")
                Row {
                    OutlinedButton(onClick = { monthMenu = true }) { Text(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("MMMM"))) }
                    DropdownMenu(expanded = monthMenu, onDismissRequest = { monthMenu = false }) {
                        (1..12).forEach { value -> DropdownMenuItem(text = { Text(YearMonth.of(year, value).format(DateTimeFormatter.ofPattern("MMMM"))) }, onClick = { month = value; monthMenu = false }) }
                    }
                    OutlinedButton(onClick = { yearMenu = true }) { Text(year.toString()) }
                    DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                        (2000..current.year).reversed().forEach { value -> DropdownMenuItem(text = { Text(value.toString()) }, onClick = { year = value; yearMenu = false }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(YearMonth.of(year, month)) }, enabled = YearMonth.of(year, month) <= current) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
