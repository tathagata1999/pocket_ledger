package com.example.expensetracker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SmsAgentTest {
    private fun sms(body: String) = SmsMessageSnapshot("BANK", body, LocalDate.of(2026, 10, 1))

    @Test fun onlyExplicitUpiAndCardPurchasesPass() {
        assertEquals("EXPENSE", SmsAgent.classify(sms("UPI Rs 200 debited to merchant")).candidate?.type)
        assertEquals("INCOME", SmsAgent.classify(sms("UPI Rs 200 credited from friend")).candidate?.type)
        assertEquals("EXPENSE", SmsAgent.classify(sms("Credit card purchase Rs 450 spent at store")).candidate?.type)
        assertEquals("EXPENSE", SmsAgent.classify(sms("Debit card used at store Rs 450 debited")).candidate?.type)
    }

    @Test fun excludedMessagesNeverReachReview() {
        listOf("UPI mandate executed Rs 200 debited", "UPI autopay mandate cancelled Rs 5000",
            "Credit card bill payment of Rs 5000 received successfully", "Rs 800 paid pending confirmation",
            "Rs 100 debited from account", "Rs 500 salary credited", "ATM Rs 500 withdrawn",
            "UPI Rs 100 debited failed", "UPI OTP Rs 100 debited",
            "UPI Rs 100 debited and Rs 200 credited", "Credit card Rs 100 credited",
            "UPI Rs 100 debited and available balance Rs 900"
        ).forEach { assertEquals(it, SmsAgent.Kind.IGNORE, SmsAgent.classify(sms(it)).kind) }
    }

    @Test fun aiCannotOverrideExcludedMessages() {
        val json = org.json.JSONObject("""{"decision":"transaction","type":"EXPENSE","amountPaise":10000}""")
        assertNull(SmsAgent.validate(json, sms("Rs 100 debited")))
        assertNull(SmsAgent.validate(json, sms("UPI Rs 100 debited pending")))
        assertNotNull(SmsAgent.validate(json, sms("UPI Rs 100 debited to merchant")))
        assertFalse(SmsAgent.mask("Rs 1234 to 9876543210").contains("9876543210"))
    }
}
