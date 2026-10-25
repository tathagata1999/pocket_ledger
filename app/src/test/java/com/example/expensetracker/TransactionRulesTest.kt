package com.example.expensetracker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TransactionRulesTest {
    private val date = LocalDate.of(2026, 10, 1)
    @Test fun classificationsPreserveDirection() {
        assertFalse(TransactionRules.validClassification("EXPENSE", IncomeClassifications.CARD_REFUND))
        assertFalse(TransactionRules.validClassification("EXPENSE", IncomeClassifications.GENUINE))
        assertTrue(TransactionRules.validClassification("INCOME", IncomeClassifications.CARD_REFUND))
        val refund = Expense("1", "Refund", "Shopping", 10000, date, type = "INCOME", classification = IncomeClassifications.CARD_REFUND)
        val expense = Expense("2", "Purchase", "Shopping", 30000, date)
        assertEquals(20000L, LedgerTotals.spending(listOf(refund, expense)))
        assertEquals(0L, LedgerTotals.income(listOf(refund)))
        assertEquals(0L, LedgerTotals.spending(listOf(expense.copy(classification = IncomeClassifications.CARD_BILL))))
    }

    @Test fun onlyEligibleTransactionsCanBeApproved() {
        fun candidate(text: String) = SmsTransactionParser.parseMessage(SmsMessageSnapshot("VM-HDFCBK", text, date))
        val accepted = candidate("UPI Rs 100 debited from HDFC account")!!
        assertTrue(ReviewEligibility.eligible(accepted))
        assertEquals(accepted.bank, accepted.toExpense("fp").first.bank)
        assertEquals(IncomeClassifications.UNREVIEWED, accepted.toExpense("fp").first.classification)
        assertTrue(ReviewEligibility.eligible(candidate("Credit card purchase Rs 100 spent at store")!!))
        assertNull(candidate("Rs 100 debited from account"))
        assertNull(candidate("UPI Rs 100 debited failed"))
        assertNull(candidate("UPI Rs 100 debited and available balance Rs 900"))
        assertNull(candidate("UPI Rs 100 debited and Rs 200 credited"))
        assertFalse(ReviewEligibility.eligible(accepted.copy(paymentMethod = PaymentMethods.UNKNOWN)))
    }
}
