package com.example.expensetracker

object PaymentMethods {
    const val UPI = "UPI"
    const val CREDIT_CARD = "Credit Card"
    const val DEBIT_CARD = "Debit Card"
    const val ATM = "ATM"
    const val UNKNOWN = "Other/Unknown"
    val all = listOf(UPI, CREDIT_CARD, DEBIT_CARD, ATM, UNKNOWN)

    fun normalize(value: String): String = value.takeIf { it in all } ?: UNKNOWN

    fun detect(body: String): String {
        val text = body.lowercase()
        val methods = mutableSetOf<String>()
        if (Regex("\\bupi\\b|\\bvpa\\b|@[a-z][a-z0-9]+\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) methods += UPI
        if (Regex("\\bcredit[ -]?card\\b|\\bcc\\s*(?:xx|ending|no\\b)").containsMatchIn(text)) methods += CREDIT_CARD
        if (Regex("\\bdebit[ -]?card\\b|\\bdc\\s*(?:xx|ending|no\\b)").containsMatchIn(text)) methods += DEBIT_CARD
        if (Regex("\\batm\\b").containsMatchIn(text)) methods += ATM
        // 'card' alone, account debits, and contradictory signals are not reliable identifiers.
        return methods.singleOrNull() ?: UNKNOWN
    }
}
