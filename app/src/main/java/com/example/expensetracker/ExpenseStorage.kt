package com.example.expensetracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "encrypted_expenses")
data class EncryptedExpenseEntity(
    @PrimaryKey val id: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM encrypted_expenses")
    fun observeAll(): Flow<List<EncryptedExpenseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: EncryptedExpenseEntity)

    @Query("DELETE FROM encrypted_expenses WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [EncryptedExpenseEntity::class], version = 1, exportSchema = false)
abstract class ExpenseDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: ExpenseDatabase? = null

        fun open(context: Context): ExpenseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ExpenseDatabase::class.java,
                    "pocket-ledger.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

/** Encrypts every user-visible transaction field; the Keystore key is non-exportable. */
class ExpenseCipher {
    private val alias = "pocket-ledger-expense-key-v1"

    fun encrypt(expense: Expense): EncryptedExpenseEntity {
        val id = expense.id
        val plain = JSONObject()
            .put("title", expense.title)
            .put("category", expense.category)
            .put("amountPaise", expense.amountPaise)
            .put("date", expense.date.toString())
            .put("type", expense.type)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return EncryptedExpenseEntity(id, cipher.iv, cipher.doFinal(plain))
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
            type = value.optString("type", "EXPENSE")
        )
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
        dao.insert(cipher.encrypt(expense))
    }

    suspend fun deleteExpense(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
    }
}
