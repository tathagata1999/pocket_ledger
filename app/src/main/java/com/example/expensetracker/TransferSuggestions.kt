package com.example.expensetracker

import kotlin.math.abs

/** Suggestions never affect totals; users must explicitly confirm each transaction. */
fun findTransferSuggestions(expenses: List<Expense>): Set<String> {
    val transferTerms = Regex("(?i)\\b(self[ -]?transfer|own account|a/c transfer|account transfer|funds transfer|transfer to|transfer from)\\b")
    val accountReference = Regex("(?i)(?:\\b(?:a/c|acct|account)\\s*(?:no\\.?|number|xx|x|\\*|ending)?\\s*[-:]?\\s*[x*0-9]{3,}|\\b(?:imps|neft|rtgs)\\b)")
    val candidates = expenses.filter { !it.selfTransfer && it.timestampMillis > 0L && it.amountPaise > 0L }
    val matches = mutableSetOf<String>()
    for (debit in candidates.filter { it.type == "EXPENSE" }) {
        for (credit in candidates.filter { it.type == "INCOME" }) {
            if (debit.amountPaise != credit.amountPaise || abs(debit.timestampMillis - credit.timestampMillis) > 30 * 60 * 1000L) continue
            val combined = "${debit.sourceSnippet} ${credit.sourceSnippet}"
            // Matching money/time is not sufficient. Require transfer language and account/rail evidence.
            if (transferTerms.containsMatchIn(combined) && accountReference.containsMatchIn(combined)) {
                matches += debit.id
                matches += credit.id
            }
        }
    }
    return matches
}
