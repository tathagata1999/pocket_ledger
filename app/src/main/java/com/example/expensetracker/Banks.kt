package com.example.expensetracker

/** Bank identity is independent of spending category and payment rail. */
object Banks {
    const val UNKNOWN = "Unknown bank"

    private data class Bank(val name: String, val names: List<String>, val senderCodes: List<String>)
    private val banks = listOf(
        Bank("State Bank of India", listOf("state bank of india", "sbi bank", "state bank"), listOf("SBI", "SBIINB")),
        Bank("HDFC Bank", listOf("hdfc bank", "hdfc"), listOf("HDFC")),
        Bank("ICICI Bank", listOf("icici bank", "icici"), listOf("ICICI")),
        Bank("Axis Bank", listOf("axis bank"), listOf("AXIS")),
        Bank("Kotak Mahindra Bank", listOf("kotak mahindra", "kotak bank"), listOf("KOTAK")),
        Bank("Punjab National Bank", listOf("punjab national bank"), listOf("PNB")),
        Bank("Bank of Baroda", listOf("bank of baroda"), listOf("BOB", "BARODA")),
        Bank("Canara Bank", listOf("canara bank"), listOf("CANARA")),
        Bank("Union Bank of India", listOf("union bank of india"), listOf("UNION")),
        Bank("IDFC FIRST Bank", listOf("idfc first bank", "idfc bank"), listOf("IDFC")),
        Bank("IndusInd Bank", listOf("indusind bank"), listOf("INDUS")),
        Bank("Yes Bank", listOf("yes bank"), listOf("YESBK")),
        Bank("Federal Bank", listOf("federal bank"), listOf("FEDERAL")),
        Bank("IDBI Bank", listOf("idbi bank"), listOf("IDBI")),
        Bank("AU Small Finance Bank", listOf("au small finance bank", "au bank"), listOf("AUBANK"))
    )

    val options: List<String> = listOf(UNKNOWN) + banks.map { it.name }

    fun normalize(value: String?): String = banks.firstOrNull { it.name.equals(value?.trim(), true) }?.name ?: UNKNOWN

    fun detect(sender: String, body: String): String {
        // Indian SMS headers usually have a two-letter route prefix and a compact
        // six-character sender ID, e.g. VM-HDFCBK or AD-SBIINB.
        val senderId = sender.trim().uppercase().replace(Regex("^[A-Z]{2}-"), "")
        val senderMatches = banks.filter { bank -> bank.senderCodes.any { code ->
            senderId == code || (senderId.length in 5..8 && senderId.startsWith(code) &&
                senderId.all { it in 'A'..'Z' })
        } }.map { it.name }.toSet()
        if (senderMatches.size == 1) return senderMatches.single()
        if (senderMatches.isNotEmpty()) return UNKNOWN
        // A sender may carry a bank code after a generic routing prefix, but only
        // accept exact code tokens; substring matching would invent issuers.
        val tokens = senderId.split(Regex("[^A-Z0-9]+"))
        val tokenMatches = banks.filter { bank -> bank.senderCodes.any { code -> code in tokens } }.map { it.name }.toSet()
        if (tokenMatches.size == 1) return tokenMatches.single()
        return inferFromSnippet(body)
    }

    /** Only explicit issuer names are safe for old SMS rows without stored sender IDs. */
    fun inferFromSnippet(snippet: String): String {
        val matches = banks.filter { bank -> bank.names.any { name ->
            Regex("(?i)(?<![a-z])${Regex.escape(name)}(?![a-z])").containsMatchIn(snippet)
        } }.map { it.name }.toSet()
        return if (matches.size == 1) matches.single() else UNKNOWN
    }

    fun effectiveBank(bank: String?, sourceSnippet: String): String =
        normalize(bank).takeUnless { it == UNKNOWN } ?: inferFromSnippet(sourceSnippet)
}
