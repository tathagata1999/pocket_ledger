package com.example.expensetracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Rules are authoritative for non-transactions; AI cannot override them. */
object SmsAgent {
    enum class Kind { TRANSACTION, IGNORE, REVIEW }
    data class Decision(val kind: Kind, val candidate: SmsTransactionCandidate? = null)

    fun classify(message: SmsMessageSnapshot): Decision {
        val candidate = SmsTransactionParser.parseMessage(message) ?: return Decision(Kind.IGNORE)
        return Decision(Kind.TRANSACTION, candidate)
    }

    /** Remove numbers and identifiers before external processing. Never transmit the original SMS. */
    fun mask(body: String): String = body
        .replace(Regex("(?i)https?://\\S+|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}"), "[REDACTED]")
        .replace(Regex("(?i)\\b(?:[a-z]{2,}\\d+[a-z0-9]*|[a-z0-9]*\\d+[a-z]{2,})\\b"), "[ID]")
        .replace(Regex("\\b\\d[\\d,.]*\\b"), "[NUMBER]")
        .replace(Regex("(?i)\\b(?:[a-z]+[._-]){1,}[a-z]+\\b"), "[NAME]")
        .take(350)

    fun fallback(message: SmsMessageSnapshot): SmsTransactionCandidate? = SmsTransactionParser.parseMessage(message)

    /** The only accepted AI result is a conservative candidate for human review. */
    fun validate(json: JSONObject, message: SmsMessageSnapshot): SmsTransactionCandidate? {
        if (json.optString("decision") != "transaction") return null
        val type = json.optString("type")
        if (type !in setOf("EXPENSE", "INCOME")) return null
        val amount = runCatching { json.getLong("amountPaise") }.getOrNull() ?: return null
        if (amount <= 0 || amount > 100_000_000_000L) return null
        val original = fallback(message)
        // AI cannot invent or change the amount or direction inferred by the local parser.
        if (original == null || original.amountPaise != amount || original.type != type) return null
        return original
    }
}

class SmsOpenAiClient {
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun suggest(message: SmsMessageSnapshot, apiKey: String): SmsTransactionCandidate? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val prompt = JSONObject().put("model", "gpt-4o-mini")
            .put("store", false)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", "sms_decision").put("strict", true)
                .put("schema", JSONObject().put("type", "object").put("additionalProperties", false)
                    .put("properties", JSONObject()
                        .put("decision", JSONObject().put("type", "string").put("enum", org.json.JSONArray(listOf("transaction", "ignore", "uncertain"))))
                        .put("type", JSONObject().put("type", "string").put("enum", org.json.JSONArray(listOf("EXPENSE", "INCOME", "UNKNOWN"))))
                        .put("amountPaise", JSONObject().put("type", "integer")))
                    .put("required", org.json.JSONArray(listOf("decision", "type", "amountPaise"))))))
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Classify banking SMS. Return uncertain unless certain it is an executed transaction. Mandate setup/cancellation and credit card bill settlement are ignore. Numeric amounts are redacted, so use 0 and uncertain if amount cannot be verified. No guesses."))
                .put(JSONObject().put("role", "user").put("content", SmsAgent.mask(message.body))))
        val request = Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(prompt.toString().toRequestBody("application/json".toMediaType())).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val content = JSONObject(response.body?.string().orEmpty()).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                SmsAgent.validate(JSONObject(content), message)
            }
        }.getOrNull()
    }
}
