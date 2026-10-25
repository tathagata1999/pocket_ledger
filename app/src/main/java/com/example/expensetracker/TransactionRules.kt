package com.example.expensetracker

object TransactionRules {
    fun validClassification(type: String, classification: String): Boolean = when (classification) {
        IncomeClassifications.UNREVIEWED, IncomeClassifications.SELF_TRANSFER -> true
        IncomeClassifications.GENUINE -> type == "INCOME"
        IncomeClassifications.CARD_REFUND -> type == "INCOME"
        IncomeClassifications.CARD_BILL -> true
        else -> false
    }

    fun options(type: String): List<String> = listOf(IncomeClassifications.UNREVIEWED) +
        IncomeClassifications.options.filter { validClassification(type, it) }
}

object ReviewEligibility {
    fun eligible(candidate: SmsTransactionCandidate): Boolean =
        candidate.amountPaise > 0 && candidate.type in setOf("EXPENSE", "INCOME") &&
            candidate.title.isNotBlank() && candidate.sourceSnippet.isNotBlank() &&
            SmsTransactionParser.parseMessage(SmsMessageSnapshot("", candidate.sourceSnippet, candidate.date))?.let {
                it.amountPaise == candidate.amountPaise && it.type == candidate.type &&
                    it.paymentMethod == candidate.paymentMethod
            } == true &&
            !Regex("(?i)\\b(?:debited|spent|withdrawn)\\b").containsMatchIn(candidate.sourceSnippet).let { debit ->
                debit && Regex("(?i)\\b(?:credited|deposited|refund received)\\b").containsMatchIn(candidate.sourceSnippet)
            } &&
            Regex("(?i)(?:rs\\.?|inr|₹)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
                .findAll(candidate.sourceSnippet).count() <= 1 &&
            SmsAgent.classify(SmsMessageSnapshot("", candidate.sourceSnippet, candidate.date)).let {
                it.kind == SmsAgent.Kind.TRANSACTION && it.candidate?.amountPaise == candidate.amountPaise &&
                    it.candidate.type == candidate.type
            }
}
