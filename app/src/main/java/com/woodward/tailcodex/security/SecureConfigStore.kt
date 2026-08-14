package com.woodward.tailcodex.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.woodward.tailcodex.data.ConnectionConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("tailcodex_config", Context.MODE_PRIVATE)

    fun load(): ConnectionConfig = ConnectionConfig(
        endpoint = preferences.getString(KEY_ENDPOINT, null) ?: ConnectionConfig().endpoint,
        token = preferences.getString(KEY_TOKEN, null)?.let(::decrypt).orEmpty(),
        defaultCwd = preferences.getString(KEY_CWD, null) ?: ConnectionConfig().defaultCwd,
    )

    fun save(config: ConnectionConfig) {
        preferences.edit {
            putString(KEY_ENDPOINT, config.endpoint)
            putString(KEY_CWD, config.defaultCwd)
            putString(KEY_TOKEN, encrypt(config.token))
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val encrypted = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "tailcodex_capability_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_CWD = "cwd"
        const val KEY_TOKEN = "token"
    }
}
