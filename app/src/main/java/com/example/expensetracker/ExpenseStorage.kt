package com.example.expensetracker

import android.content.Context
import android.provider.Telephony
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SmsTransactionCandidate(
    val title: String,
    val category: String,
    val amountPaise: Long,
    val type: String,
    val date: LocalDate,
    val sourceSnippet: String,
    val paymentMethod: String = PaymentMethods.UNKNOWN,
    val bank: String = Banks.UNKNOWN,
    val timestampMillis: Long = 0L,
    val smsFingerprint: String = "",
)

@Entity(tableName = "encrypted_expenses", indices = [Index(value = ["fingerprint"], unique = true)])
data class EncryptedExpenseEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray
)

@Entity(tableName = "sms_reviews")
data class SmsReviewEntity(@PrimaryKey val fingerprint: String, val status: String, val nonce: ByteArray, val ciphertext: ByteArray)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM sms_reviews WHERE status = 'PENDING'")
    fun observeReviews(): Flow<List<SmsReviewEntity>>

    @Query("SELECT fingerprint FROM sms_reviews")
    suspend fun reviewFingerprints(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviews(rows: List<SmsReviewEntity>)

    @Query("UPDATE sms_reviews SET status = :status WHERE fingerprint = :fingerprint AND status = 'PENDING'")
    suspend fun resolveReview(fingerprint: String, status: String): Int

    @Query("SELECT * FROM encrypted_expenses")
    fun observeAll(): Flow<List<EncryptedExpenseEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: EncryptedExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(expenses: List<EncryptedExpenseEntity>): List<Long>

    @Query("SELECT fingerprint FROM encrypted_expenses")
    suspend fun getFingerprints(): List<String>

    @Query("UPDATE encrypted_expenses SET nonce = :nonce, ciphertext = :ciphertext WHERE id = :id")
    suspend fun updatePayload(id: String, nonce: ByteArray, ciphertext: ByteArray): Int

    @Query("SELECT * FROM encrypted_expenses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EncryptedExpenseEntity?

    @Query("DELETE FROM encrypted_expenses WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [EncryptedExpenseEntity::class, SmsReviewEntity::class], version = 3, exportSchema = false)
abstract class ExpenseDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: ExpenseDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 1 stored id, nonce and ciphertext; preserve encrypted payloads unchanged.
                // Existing manual entries use their unique id as fingerprint.
                db.execSQL("ALTER TABLE encrypted_expenses ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE encrypted_expenses SET fingerprint = id")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_encrypted_expenses_fingerprint ON encrypted_expenses (fingerprint)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sms_reviews (fingerprint TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, nonce BLOB NOT NULL, ciphertext BLOB NOT NULL)")
            }
        }

        fun open(context: Context): ExpenseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ExpenseDatabase::class.java,
                    "pocket-ledger.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}

/** Encrypts every user-visible transaction field; the Keystore key is non-exportable. */
class ExpenseCipher {
    private val alias = "pocket-ledger-expense-key-v1"

    fun encrypt(expense: Expense, fingerprint: String = expense.id): EncryptedExpenseEntity {
        val id = expense.id
        val plain = JSONObject()
            .put("title", expense.title)
            .put("category", expense.category)
            .put("amountPaise", expense.amountPaise)
            .put("date", expense.date.toString())
            .put("type", expense.type)
            .put("paymentMethod", expense.paymentMethod)
            .put("bank", expense.bank)
            .put("selfTransfer", expense.selfTransfer)
            .put("classification", expense.classification)
            .put("timestampMillis", expense.timestampMillis)
            .put("sourceSnippet", expense.sourceSnippet)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return EncryptedExpenseEntity(id, fingerprint, cipher.iv, cipher.doFinal(plain))
    }

    fun decrypt(entity: EncryptedExpenseEntity): Expense {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, entity.nonce))
        cipher.updateAAD(entity.id.toByteArray(Charsets.UTF_8))
        val value = JSONObject(String(cipher.doFinal(entity.ciphertext), Charsets.UTF_8))
        return Expense(
            id = entity.id,
            title = value.getString("title"),
            category = value.getString("category"),
            amountPaise = value.getLong("amountPaise"),
            date = LocalDate.parse(value.getString("date")),
            type = value.optString("type", "EXPENSE"),
            paymentMethod = PaymentMethods.normalize(value.optString("paymentMethod", PaymentMethods.UNKNOWN)),
            bank = Banks.effectiveBank(value.optString("bank", Banks.UNKNOWN), value.optString("sourceSnippet", "")),
            selfTransfer = value.optBoolean("selfTransfer", false),
            classification = value.optString("classification", IncomeClassifications.UNREVIEWED),
            timestampMillis = value.optLong("timestampMillis", 0L),
            sourceSnippet = value.optString("sourceSnippet", "")
        )
    }

    fun encryptReview(fingerprint: String, candidate: SmsTransactionCandidate): SmsReviewEntity {
        val payload = encrypt(candidate.toExpense(fingerprint).first, fingerprint)
        return SmsReviewEntity(fingerprint, "PENDING", payload.nonce, payload.ciphertext)
    }

    fun decryptReview(review: SmsReviewEntity): SmsTransactionCandidate {
        val expense = decrypt(EncryptedExpenseEntity("sms-${review.fingerprint}", review.fingerprint, review.nonce, review.ciphertext))
        return SmsTransactionCandidate(expense.title, expense.category, expense.amountPaise, expense.type, expense.date, expense.sourceSnippet, expense.paymentMethod, expense.bank, expense.timestampMillis)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

class ExpenseRepository(private val dao: ExpenseDao, private val cipher: ExpenseCipher) {
    fun observeExpenses(): Flow<List<Expense>> = dao.observeAll()
        .map { rows -> rows.map(cipher::decrypt).sortedByDescending { it.date } }
        .flowOn(Dispatchers.IO)

    suspend fun addExpense(expense: Expense) = withContext(Dispatchers.IO) {
        dao.insert(cipher.encrypt(expense, expense.id))
    }

    suspend fun addExpenses(expenses: List<Expense>) = withContext(Dispatchers.IO) {
        dao.insertAll(expenses.map { cipher.encrypt(it, it.id) })
    }

    suspend fun addSmsExpenses(expenses: List<SmsTransactionCandidate>): Int = withContext(Dispatchers.IO) {
        val existingFingerprints = dao.getFingerprints().toHashSet()
        val mapped = expenses.filter { ReviewEligibility.eligible(it) }.map { candidate ->
            val fingerprint = candidate.smsFingerprint.ifBlank { candidate.fingerprint() }
            candidate.toExpense(fingerprint)
        }
        val seen = existingFingerprints.toMutableSet()
        val unique = mapped.filter { (_, fingerprint) -> seen.add(fingerprint) }
        if (unique.isEmpty()) 0 else dao.insertAll(
            unique.map { (expense, fingerprint) -> cipher.encrypt(expense, fingerprint) }
        ).count { it != -1L }
    }

    fun observeReviews(): Flow<List<Pair<String, SmsTransactionCandidate>>> = dao.observeReviews()
        .map { rows -> rows.map { it.fingerprint to cipher.decryptReview(it) } }.flowOn(Dispatchers.IO)

    suspend fun addReviews(candidates: List<Pair<String, SmsTransactionCandidate>>) = withContext(Dispatchers.IO) {
        val existing = (dao.reviewFingerprints() + dao.getFingerprints()).toHashSet()
        dao.insertReviews(candidates.filter { ReviewEligibility.eligible(it.second) && existing.add(it.first) }.map { (fp, candidate) -> cipher.encryptReview(fp, candidate) })
    }

    suspend fun knownFingerprints(): Set<String> = withContext(Dispatchers.IO) {
        (dao.reviewFingerprints() + dao.getFingerprints()).toSet()
    }

    suspend fun decideReview(fp: String, candidate: SmsTransactionCandidate, accept: Boolean) = withContext(Dispatchers.IO) {
        if (!accept) {
            dao.resolveReview(fp, "REJECTED")
        } else if (ReviewEligibility.eligible(candidate)) {
            // Insert first: a failed insert must not silently mark a review accepted.
            // Unique fingerprints make repeated taps and simultaneous syncs idempotent.
            dao.insert(cipher.encrypt(candidate.toExpense(fp).first, fp))
            dao.resolveReview(fp, "ACCEPTED")
        }
    }

    suspend fun approveEligibleReviews(): Int = withContext(Dispatchers.IO) {
        var approved = 0
        dao.observeReviews().first().forEach { review ->
            val candidate = cipher.decryptReview(review)
            if (ReviewEligibility.eligible(candidate)) {
                dao.insert(cipher.encrypt(candidate.toExpense(review.fingerprint).first, review.fingerprint))
                approved += dao.resolveReview(review.fingerprint, "ACCEPTED")
            }
        }
        approved
    }

    suspend fun updateExpense(id: String, bank: String, category: String, classification: String) = withContext(Dispatchers.IO) {
        val original = dao.getById(id) ?: return@withContext
        val current = cipher.decrypt(original)
        if (!TransactionRules.validClassification(current.type, classification)) return@withContext
        val updated = cipher.encrypt(current.copy(
            bank = Banks.normalize(bank), category = category,
            classification = classification,
            selfTransfer = classification == IncomeClassifications.SELF_TRANSFER
        ), original.fingerprint)
        dao.updatePayload(id, updated.nonce, updated.ciphertext)
    }

    suspend fun setSelfTransfer(id: String, confirmed: Boolean) = withContext(Dispatchers.IO) {
        val original = dao.getById(id) ?: return@withContext
        val current = cipher.decrypt(original)
        val updated = cipher.encrypt(current.copy(selfTransfer = confirmed,
            classification = if (confirmed) IncomeClassifications.SELF_TRANSFER else IncomeClassifications.UNREVIEWED), original.fingerprint)
        dao.updatePayload(id, updated.nonce, updated.ciphertext)
    }

    suspend fun deleteExpense(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
    }
}

object SmsTransactionParser {
    private val debitKeywords = Regex("\\b(debited|spent|purchased|purchase|paid|used)\\b", RegexOption.IGNORE_CASE)
    private val creditKeywords = Regex("\\b(credited|received)\\b", RegexOption.IGNORE_CASE)
    private val excluded = Regex("\\b(otp|one.time.password|mandate|autopay|standing instruction|withdrawal|withdrawn|atm|cash|salary|payroll|neft|rtgs|imps|failed|declined|reversed|reversal|pending|initiated|scheduled|request|collect request|due|limit|verification|authori[sz]ation|pre.auth|blocked)\\b", RegexOption.IGNORE_CASE)
    private val cardPurchase = Regex("\\b(purchase|purchased|spent|used at|transaction at|txn at|pos txn|paid at|payment at|ecom|online purchase)\\b", RegexOption.IGNORE_CASE)
    private val amountRegex = Regex("(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    private val bareAmountRegex = Regex("\\b([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\b")

    fun parseMessages(messages: List<SmsMessageSnapshot>): List<SmsTransactionCandidate> = messages.mapNotNull { parseMessage(it) }

    fun parseMessage(message: SmsMessageSnapshot): SmsTransactionCandidate? {
        val text = message.body.lowercase()
        if (excluded.containsMatchIn(text) || isCardBillPayment(text) ||
            Regex("\\b(?:credit card|card bill|card dues|cc bill)\\b.*\\b(?:paid|payment|settled|received)\\b|\\b(?:paid|payment)\\b.*\\b(?:credit card|card bill|card dues|cc bill)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val method = PaymentMethods.detect(message.body)
        val debit = debitKeywords.containsMatchIn(text)
        val credit = creditKeywords.containsMatchIn(text)
        if (debit == credit) return null
        val type = when (method) {
            PaymentMethods.UPI -> if (debit) "EXPENSE" else "INCOME"
            PaymentMethods.CREDIT_CARD, PaymentMethods.DEBIT_CARD -> {
                if (!debit || !cardPurchase.containsMatchIn(text)) return null
                "EXPENSE"
            }
            else -> return null
        }
        if (amountRegex.findAll(message.body).count() != 1) return null
        val amount = extractAmountPaise(message.body) ?: return null
        val category = inferCategory(message.body, type)
        val title = inferTitle(message.body, type)
        return SmsTransactionCandidate(title, category, amount, type, message.date, message.body.take(120), PaymentMethods.detect(message.body), Banks.detect(message.address, message.body), message.timestampMillis)
    }

    fun isMandateSetup(text: String): Boolean {
        val lower = text.lowercase()
        return ("mandate" in lower || "autopay" in lower || "standing instruction" in lower) &&
            listOf("created", "registered", "set up", "setup", "activated", "cancelled", "canceled", "revoked", "paused", "modified", "approved", "request", "registration").any { it in lower } &&
            !listOf("debited", "deducted", "executed", "paid successfully").any { it in lower }
    }

    fun isCardBillPayment(text: String): Boolean {
        val lower = text.lowercase()
        return ("credit card" in lower || "card bill" in lower || "card ending" in lower) &&
            ("payment" in lower || "bill" in lower || "dues" in lower) &&
            listOf("received", "credited", "paid", "successful", "thank you", "settled").any { it in lower } &&
            !listOf("purchase", "spent at", "transaction at", "pos txn", "used at").any { it in lower }
    }

    private fun extractAmountPaise(body: String): Long? {
        val matches = listOf(amountRegex.findAll(body).firstOrNull(), bareAmountRegex.findAll(body).firstOrNull())
        val raw = matches.firstOrNull()?.groupValues?.getOrNull(1)?.replace(",", "") ?: return null
        return runCatching { raw.toBigDecimalOrNull()?.movePointRight(2)?.longValueExact()?.takeIf { it > 0 } }.getOrNull()
    }

    private fun inferTitle(body: String, type: String): String {
        val bankOrMerchant = Regex("(?:at|to|from)\\s+([A-Za-z0-9 &._-]{2,30})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)
        val fallback = if (type == "INCOME") "SMS Credit" else "SMS Debit"
        return bankOrMerchant?.trim()?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun inferCategory(body: String, type: String): String {
        val lower = body.lowercase()
        return when {
            "food" in lower || "restaurant" in lower || "swiggy" in lower || "zomato" in lower -> "Food"
            "uber" in lower || "ola" in lower || "travel" in lower || "taxi" in lower -> "Travel"
            "amazon" in lower || "flipkart" in lower || "shopping" in lower -> "Shopping"
            "electricity" in lower || "bill" in lower || "recharge" in lower || "gas" in lower -> "Bills"
            type == "INCOME" -> "Income"
            else -> "Other"
        }
    }
}

data class SmsMessageSnapshot(
    val address: String,
    val body: String,
    val date: LocalDate,
    val timestampMillis: Long = 0L,
) {
    fun fingerprint(): String {
        val normalized = listOf(address.trim().lowercase(), body.trim().lowercase(), date.toString()).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

fun SmsTransactionCandidate.fingerprint(): String {
    val normalized = listOf(title.trim().lowercase(), category.trim().lowercase(), amountPaise.toString(), type, date.toString(), sourceSnippet.trim().lowercase()).joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun SmsTransactionCandidate.toExpense(fingerprint: String): Pair<Expense, String> {
    val expense = Expense(
        id = "sms-$fingerprint",
        title = title,
        category = category,
        amountPaise = amountPaise,
        date = date,
        type = type,
        paymentMethod = paymentMethod,
        bank = bank,
        timestampMillis = timestampMillis,
        sourceSnippet = sourceSnippet
    )
    return expense to fingerprint
}

object SmsReader {
    fun readInbox(context: Context): List<SmsMessageSnapshot> {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val sortOrder = "${Telephony.Sms.DATE} DESC"
        val messages = mutableListOf<SmsMessageSnapshot>()
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, sortOrder)?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val millis = cursor.getLong(dateIdx)
                val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toLocalDate()
                messages += SmsMessageSnapshot(
                    address = cursor.getString(addressIdx).orEmpty(),
                    body = cursor.getString(bodyIdx).orEmpty(),
                    date = date,
                    timestampMillis = millis,
                )
            }
        }
        return messages
    }
}
