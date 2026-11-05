package com.example.autop.core.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.autop.core.common.SecretStore
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] 的 Android 实现（docs/tech/02 §4.2）：Android Keystore 生成 AES/GCM 密钥
 * （别名 `autop_secret_key`），密文 + IV 存独立 DataStore `secrets.preferences_pb`。
 *
 * 明文只在内存中出现，禁止写入日志、配置导出与事件日志（任务书硬性规则 6）。
 * Keystore 操作失败直接抛异常（密钥不可用属环境级故障，静默降级会造成密文丢失）。
 * 真机才能验证（JVM 无 AndroidKeyStore），见 progress.md。
 */
public class KeystoreSecretStore(
    private val dataStore: DataStore<Preferences>,
) : SecretStore {
    /** 用应用私有目录下的 `secrets.preferences_pb` 建库。 */
    public constructor(context: Context) : this(
        PreferenceDataStoreFactory.create { File(context.filesDir, SECRETS_FILE) },
    )

    override suspend fun put(
        ref: String,
        secret: String,
    ) {
        val payload = encrypt(secret.encodeToByteArray())
        dataStore.edit { it[stringPreferencesKey(ref)] = payload }
    }

    override suspend fun get(ref: String): String? {
        val payload = dataStore.data.first()[stringPreferencesKey(ref)] ?: return null
        return decrypt(payload).decodeToString()
    }

    override suspend fun remove(ref: String) {
        dataStore.edit { it.remove(stringPreferencesKey(ref)) }
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return "${encode(iv)}:${encode(ciphertext)}"
    }

    private fun decrypt(payload: String): ByteArray {
        val parts = payload.split(":", limit = 2)
        check(parts.size == 2) { "secret payload is malformed" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, decode(parts[0])))
        return cipher.doFinal(decode(parts[1]))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    private companion object {
        const val SECRETS_FILE = "secrets.preferences_pb"
        const val KEY_ALIAS = "autop_secret_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
