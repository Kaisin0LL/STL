package com.screentranslator.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

enum class KeyStatus { MISSING, SAVED, UNREADABLE }
class SecretUnavailable : Exception("Не удалось расшифровать ключ. Сохраните его заново.")
interface ApiKeyRepository {
    suspend fun status(): KeyStatus
    suspend fun read(): String?
    suspend fun save(value: String)
    suspend fun delete()
}
/** Credential-encrypted private storage, excluded from both backup and device transfer. */
class KeystoreSecretStore(context: Context) : ApiKeyRepository {
    private val file = AtomicFile(File(context.noBackupFilesDir, "gemini-secret-v1.json"))
    private val mutex = Mutex()
    private val alias = "screen-translator.gemini.aes.v1"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun encryptionKey(): SecretKey {
        val existing = keyStore().getKey(alias, null)
        if (existing is SecretKey) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
        }.generateKey()
    }
    override suspend fun status(): KeyStatus = try { if (read() == null) KeyStatus.MISSING else KeyStatus.SAVED } catch (_: SecretUnavailable) { KeyStatus.UNREADABLE }
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists()) return@withLock null
            try {
                val data = Json.parseToJsonElement(file.readFully().decodeToString()).jsonObject
                check(data["version"]?.jsonPrimitive?.int == 1)
                val iv = Base64.decode(data.getValue("iv").jsonPrimitive.content, Base64.NO_WRAP)
                check(iv.size == 12)
                val key = keyStore().getKey(alias, null) as? SecretKey ?: throw SecretUnavailable()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
                val plain = cipher.doFinal(Base64.decode(data.getValue("ciphertext").jsonPrimitive.content, Base64.NO_WRAP))
                try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
            } catch (_: Exception) { throw SecretUnavailable() }
        }
    }
    override suspend fun save(value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(value.isNotBlank() && value.length <= 512 && value.all { it.code in 33..126 }) { "Проверьте формат ключа" }
            val cipher = try { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) } }
            catch (_: Exception) {
                // An invalidated Keystore entry cannot decrypt the old ciphertext; a user save may replace it.
                keyStore().deleteEntry(alias)
                Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
            }
            val bytes = value.toByteArray(Charsets.UTF_8)
            val encrypted = try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
            val json = buildJsonObject {
                put("version", 1)
                put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            }.toString().toByteArray()
            val stream = file.startWrite()
            try { stream.write(json); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
    override suspend fun delete() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete(); keyStore().deleteEntry(alias)
            check(!file.baseFile.exists()) { "Не удалось удалить сохранённый ключ" }
        }
    }
}
object ApiKeys {
    @Volatile private var instance: ApiKeyRepository? = null
    fun get(context: Context): ApiKeyRepository = instance ?: synchronized(this) {
        instance ?: KeystoreSecretStore(context.applicationContext).also { instance = it }
    }
}
