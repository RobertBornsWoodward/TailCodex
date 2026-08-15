package com.woodward.tailcodex.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.woodward.tailcodex.domain.ConnectionConfig
import com.woodward.tailcodex.domain.HostProfile
import com.woodward.tailcodex.domain.ConnectionState
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("tailcodex_config", Context.MODE_PRIVATE)

    fun load(): ConnectionConfig = ConnectionConfig(
        endpoint = preferences.getString(KEY_ENDPOINT, null) ?: ConnectionConfig().endpoint,
        token = preferences.getString(KEY_TOKEN, null)?.let(::decrypt).orEmpty(),
        defaultCwd = preferences.getString(KEY_CWD, null) ?: ConnectionConfig().defaultCwd,
        hostId = preferences.getString(KEY_HOST_ID, null) ?: "default",
        hostName = preferences.getString(KEY_HOST_NAME, null) ?: "Arch",
        hostAgentEndpoint = preferences.getString(KEY_HOST_AGENT_ENDPOINT, null) ?: ConnectionConfig().hostAgentEndpoint,
        hostAgentCredential = preferences.getString(KEY_HOST_AGENT_CREDENTIAL, null)?.let(::decrypt).orEmpty(),
    )

    fun save(config: ConnectionConfig) {
        val previous = loadProfiles().firstOrNull { it.id == config.hostId }
        preferences.edit {
            putString(KEY_ENDPOINT, config.endpoint)
            putString(KEY_CWD, config.defaultCwd)
            putString(KEY_TOKEN, encrypt(config.token))
            putString(KEY_HOST_ID, config.hostId)
            putString(KEY_HOST_NAME, config.hostName)
            putString(KEY_HOST_AGENT_ENDPOINT, config.hostAgentEndpoint)
            putString(KEY_HOST_AGENT_CREDENTIAL, encrypt(config.hostAgentCredential))
        }
        saveProfile(
            HostProfile(
                id = config.hostId,
                name = config.hostName,
                endpoint = config.endpoint,
                credential = config.token,
                defaultCwd = config.defaultCwd,
                lastThreadId = previous?.lastThreadId,
                connectionState = previous?.connectionState ?: ConnectionState.Disconnected(),
                hostAgentEndpoint = config.hostAgentEndpoint,
                hostAgentCredential = config.hostAgentCredential,
            ),
        )
    }

    fun loadProfiles(): List<HostProfile> = preferences.getStringSet(KEY_PROFILE_IDS, emptySet()).orEmpty()
        .mapNotNull { id ->
            val prefix = "profile.$id."
            val endpoint = preferences.getString(prefix + "endpoint", null) ?: return@mapNotNull null
            HostProfile(
                id = id,
                name = preferences.getString(prefix + "name", id).orEmpty(),
                endpoint = endpoint,
                credential = preferences.getString(prefix + "credential", null)?.let(::decrypt).orEmpty(),
                defaultCwd = preferences.getString(prefix + "cwd", "/").orEmpty(),
                lastThreadId = preferences.getString(prefix + "last_thread", null),
                connectionState = readConnectionState(prefix),
                hostAgentEndpoint = preferences.getString(prefix + "host_agent_endpoint", "").orEmpty(),
                hostAgentCredential = preferences.getString(prefix + "host_agent_credential", null)?.let(::decrypt).orEmpty(),
            )
        }.sortedBy(HostProfile::name)

    fun saveProfile(profile: HostProfile) {
        val prefix = "profile.${profile.id}."
        preferences.edit {
            putStringSet(KEY_PROFILE_IDS, preferences.getStringSet(KEY_PROFILE_IDS, emptySet()).orEmpty() + profile.id)
            putString(prefix + "name", profile.name)
            putString(prefix + "endpoint", profile.endpoint)
            putString(prefix + "credential", encrypt(profile.credential))
            putString(prefix + "cwd", profile.defaultCwd)
            putString(prefix + "host_agent_endpoint", profile.hostAgentEndpoint)
            putString(prefix + "host_agent_credential", encrypt(profile.hostAgentCredential))
            if (profile.lastThreadId == null) remove(prefix + "last_thread")
            else putString(prefix + "last_thread", profile.lastThreadId)
            writeConnectionState(prefix, profile.connectionState)
        }
    }

    fun updateProfileRuntime(hostId: String, connectionState: ConnectionState, lastThreadId: String? = null) {
        if (hostId !in preferences.getStringSet(KEY_PROFILE_IDS, emptySet()).orEmpty()) return
        val prefix = "profile.$hostId."
        preferences.edit {
            if (lastThreadId != null) putString(prefix + "last_thread", lastThreadId)
            writeConnectionState(prefix, connectionState)
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    fun deviceId(): String = preferences.getString(KEY_DEVICE_ID, null) ?: "android-${UUID.randomUUID()}".also {
        preferences.edit { putString(KEY_DEVICE_ID, it) }
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

    private fun SharedPreferences.Editor.writeConnectionState(
        prefix: String,
        state: ConnectionState,
    ) {
        remove(prefix + "connection_reason")
        remove(prefix + "connection_thread")
        remove(prefix + "connection_attempt")
        remove(prefix + "connection_stale")
        when (state) {
            is ConnectionState.Disconnected -> {
                putString(prefix + "connection_kind", "disconnected")
                state.reason?.let { putString(prefix + "connection_reason", it) }
                putBoolean(prefix + "connection_stale", state.staleSnapshot)
            }
            is ConnectionState.Connecting -> {
                putString(prefix + "connection_kind", "connecting")
                putInt(prefix + "connection_attempt", state.reconnectAttempt)
            }
            is ConnectionState.Initializing -> {
                putString(prefix + "connection_kind", "initializing")
                putInt(prefix + "connection_attempt", state.reconnectAttempt)
            }
            is ConnectionState.Reconciling -> {
                putString(prefix + "connection_kind", "reconciling")
                putString(prefix + "connection_thread", state.threadId)
                putInt(prefix + "connection_attempt", state.reconnectAttempt)
            }
            ConnectionState.Ready -> putString(prefix + "connection_kind", "ready")
        }
    }

    private fun readConnectionState(prefix: String): ConnectionState = when (
        preferences.getString(prefix + "connection_kind", "disconnected")
    ) {
        "connecting" -> ConnectionState.Connecting(preferences.getInt(prefix + "connection_attempt", 0))
        "initializing" -> ConnectionState.Initializing(preferences.getInt(prefix + "connection_attempt", 0))
        "reconciling" -> ConnectionState.Reconciling(
            preferences.getString(prefix + "connection_thread", "").orEmpty(),
            preferences.getInt(prefix + "connection_attempt", 0),
        )
        "ready" -> ConnectionState.Ready
        else -> ConnectionState.Disconnected(
            preferences.getString(prefix + "connection_reason", null),
            preferences.getBoolean(prefix + "connection_stale", false),
        )
    }

    private companion object {
        const val KEY_ALIAS = "tailcodex_capability_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_CWD = "cwd"
        const val KEY_TOKEN = "token"
        const val KEY_HOST_ID = "host_id"
        const val KEY_HOST_NAME = "host_name"
        const val KEY_PROFILE_IDS = "profile_ids"
        const val KEY_HOST_AGENT_ENDPOINT = "host_agent_endpoint"
        const val KEY_HOST_AGENT_CREDENTIAL = "host_agent_credential"
        const val KEY_DEVICE_ID = "device_id"
    }
}
