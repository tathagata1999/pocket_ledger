package com.example.expensetracker

object IncomeClassifications {
    const val UNREVIEWED = "UNREVIEWED"
    const val GENUINE = "GENUINE_INCOME"
    const val SELF_TRANSFER = "SELF_TRANSFER"
    const val CARD_REFUND = "CARD_REFUND"
    const val CARD_BILL = "CARD_BILL_PAYMENT"
    val options = listOf(GENUINE, SELF_TRANSFER, CARD_REFUND, CARD_BILL)
}

object LedgerTotals {
    fun income(expenses: List<Expense>): Long = expenses.filter {
        it.type == "INCOME" && !it.selfTransfer && it.classification in setOf(IncomeClassifications.UNREVIEWED, IncomeClassifications.GENUINE)
    }.sumOf { it.amountPaise }

    fun spending(expenses: List<Expense>): Long = expenses.sumOf {
        when {
            it.selfTransfer || it.classification == IncomeClassifications.SELF_TRANSFER || it.classification == IncomeClassifications.CARD_BILL -> 0L
            it.classification == IncomeClassifications.CARD_REFUND && it.type == "INCOME" -> -it.amountPaise
            it.type == "EXPENSE" -> it.amountPaise
            else -> 0L
        }
    }

    fun spendingByMethod(expenses: List<Expense>): Map<String, Long> = expenses.groupBy {
        if (it.classification == IncomeClassifications.CARD_REFUND) PaymentMethods.CREDIT_CARD else PaymentMethods.normalize(it.paymentMethod)
    }.mapValues { (_, rows) -> spending(rows) }
}
