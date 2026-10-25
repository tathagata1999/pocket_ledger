package com.example.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BanksTest {
    @Test fun compactSenderHeadersIdentifyIssuer() {
        assertEquals("HDFC Bank", Banks.detect("VM-HDFCBK", "Rs 100 debited"))
        assertEquals("State Bank of India", Banks.detect("AD-SBIINB", "Rs 100 debited"))
        assertEquals("ICICI Bank", Banks.detect("JD-ICICIB", "Rs 100 debited"))
        assertEquals("HDFC Bank", Banks.detect("VM-HDFCBK", "Rs 100 paid to ICICI Bank"))
    }

    @Test fun unrelatedOrAmbiguousContentRemainsUnknown() {
        assertEquals(Banks.UNKNOWN, Banks.detect("VM-OTHERX", "Rs 100 debited"))
        assertEquals(Banks.UNKNOWN, Banks.detect("VM-OTHERX", "Transfer from HDFC Bank to Axis Bank"))
        assertEquals(Banks.UNKNOWN, Banks.detect("VM-NOTHDFC", "Rs 100 debited"))
        assertEquals(Banks.UNKNOWN, Banks.inferFromSnippet("Rs 100 debited via UPI"))
        assertEquals(Banks.UNKNOWN, Banks.inferFromSnippet("Sent from HDFC Bank to Axis Bank"))
    }

    @Test fun historicalSnippetsRecoverOnlyExplicitUnambiguousBankNames() {
        assertEquals("HDFC Bank", Banks.effectiveBank(Banks.UNKNOWN, "Rs 100 debited from HDFC Bank account"))
        assertEquals("State Bank of India", Banks.effectiveBank(null, "State Bank of India: INR 200 debited"))
        assertEquals("Axis Bank", Banks.effectiveBank("Axis Bank", "HDFC Bank transaction"))
        assertEquals(Banks.UNKNOWN, Banks.effectiveBank(Banks.UNKNOWN, "Rs 200 debited from a/c XX12"))
    }

    @Test fun reportShowsInferredHistoricalBankAndUnknownSeparately() {
        val date = LocalDate.of(2026, 10, 1)
        val items = listOf(
            Expense("sms-1", "SMS Debit", "Other", 10000, date, bank = Banks.UNKNOWN, sourceSnippet = "HDFC Bank: Rs 100 debited"),
            Expense("sms-2", "SMS Debit", "Other", 20000, date, bank = Banks.UNKNOWN, sourceSnippet = "Rs 200 debited")
        )
        val rows = BankReports.forMonth(items, YearMonth.of(2026, 10))
        assertEquals(listOf("HDFC Bank", Banks.UNKNOWN), rows.map { it.bank })
        assertEquals(listOf(10000L, 20000L), rows.map { it.spending })
    }
}
