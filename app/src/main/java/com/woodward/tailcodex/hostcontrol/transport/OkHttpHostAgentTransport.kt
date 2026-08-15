package com.woodward.tailcodex.hostcontrol.transport

import com.woodward.tailcodex.hostcontrol.protocol.CodexOwnership
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceSnapshot
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceState
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentConfig
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentException
import com.woodward.tailcodex.hostcontrol.protocol.HostHello
import com.woodward.tailcodex.hostcontrol.protocol.HostOperation
import com.woodward.tailcodex.hostcontrol.protocol.HostOperationStatus
import com.woodward.tailcodex.hostcontrol.protocol.HostLogSummaryEntry
import com.woodward.tailcodex.hostcontrol.protocol.HostPairingResult
import com.woodward.tailcodex.hostcontrol.repository.HostAgentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class OkHttpHostAgentTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : HostAgentRepository {
    override suspend fun hello(endpoint: String): HostHello = get(endpoint, "/v1/hello").let {
        HostHello(
            protocolVersion = it.getInt("protocolVersion"),
            agentVersion = it.getString("agentVersion"),
            minClientVersion = it.getString("minClientVersion"),
        )
    }

    override suspend fun pair(
        endpoint: String,
        code: String,
        deviceId: String,
        deviceName: String,
    ): HostPairingResult {
        val body = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId)
            .put("name", deviceName)
        return post(endpoint, "/v1/pair", body).let {
            HostPairingResult(
                deviceId = it.getString("deviceId"),
                credential = it.getString("credential"),
                grants = it.getJSONArray("grants").toStringSet(),
            )
        }
    }

    override suspend fun capabilities(config: HostAgentConfig): Pair<Set<String>, Set<String>> =
        get(config.endpoint, "/v1/capabilities", config.credential).let {
            it.getJSONArray("features").toStringSet() to it.getJSONArray("grants").toStringSet()
        }

    override suspend fun services(config: HostAgentConfig): CodexServiceSnapshot {
        val response = get(config.endpoint, "/v1/services", config.credential)
        val services = response.getJSONArray("services")
        for (index in 0 until services.length()) {
            val service = services.getJSONObject(index)
            if (service.getString("id") == "codex-app-server") return service.getJSONObject("snapshot").toSnapshot()
        }
        throw HostAgentException("CODEX_SERVICE_MISSING", "Host Agent 未返回 Codex 服务状态")
    }

    override suspend fun logSummary(config: HostAgentConfig): List<HostLogSummaryEntry> {
        val entries = get(config.endpoint, "/v1/logs/summary", config.credential).getJSONArray("entries")
        return (0 until entries.length()).map { index ->
            entries.getJSONObject(index).let {
                HostLogSummaryEntry(
                    timestamp = it.optString("timestamp"),
                    actor = it.optString("actor"),
                    action = it.optString("action"),
                    riskLevel = it.optString("riskLevel"),
                    outcome = it.optString("outcome"),
                )
            }
        }
    }

    override suspend fun ensureCodexRunning(
        config: HostAgentConfig,
        requestId: String,
        idempotencyKey: String,
    ): String = action(config, "/v1/actions/codex.ensure-running", requestId, idempotencyKey)

    override suspend fun restartCodex(
        config: HostAgentConfig,
        requestId: String,
        idempotencyKey: String,
    ): String = action(config, "/v1/actions/codex.restart", requestId, idempotencyKey)

    override suspend fun operation(config: HostAgentConfig, operationId: String): HostOperation =
        get(config.endpoint, "/v1/operations/$operationId", config.credential)
            .getJSONObject("operation")
            .toOperation()

    private suspend fun action(
        config: HostAgentConfig,
        path: String,
        requestId: String,
        idempotencyKey: String,
    ): String = post(
        endpoint = config.endpoint,
        path = path,
        body = JSONObject().put("requestId", requestId),
        credential = config.credential,
        idempotencyKey = idempotencyKey,
    ).getString("operationId")

    private suspend fun get(endpoint: String, path: String, credential: String? = null): JSONObject =
        execute(
            Request.Builder().url(url(endpoint, path)).get().apply {
                credential?.let { header("Authorization", "Bearer $it") }
            }.build(),
        )

    private suspend fun post(
        endpoint: String,
        path: String,
        body: JSONObject,
        credential: String? = null,
        idempotencyKey: String? = null,
    ): JSONObject = execute(
        Request.Builder()
            .url(url(endpoint, path))
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .apply {
                credential?.let { header("Authorization", "Bearer $it") }
                idempotencyKey?.let { header("Idempotency-Key", it) }
            }
            .build(),
    )

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw HostAgentException("INVALID_RESPONSE", "Host Agent 返回了无效 JSON", response.code)
            }
            if (!response.isSuccessful) {
                throw HostAgentException(
                    json.optString("code", "HTTP_${response.code}"),
                    json.optString("message", "Host Agent 请求失败"),
                    response.code,
                )
            }
            json
        }
    }

    private fun url(endpoint: String, path: String): String = endpoint.trimEnd('/') + path

    private fun JSONObject.toSnapshot(): CodexServiceSnapshot = CodexServiceSnapshot(
        ownership = enumValueOr(CodexOwnership.UNKNOWN, getString("ownership")),
        state = enumValueOr(CodexServiceState.FAILED, getString("state")),
        portOpen = optBoolean("portOpen"),
        ready = optBoolean("ready"),
        detail = optString("detail").takeIf(String::isNotBlank),
    )

    private fun JSONObject.toOperation(): HostOperation {
        val error = optJSONObject("error")
        return HostOperation(
            id = getString("operationId"),
            kind = getString("kind"),
            status = enumValueOr(HostOperationStatus.FAILED, getString("status")),
            errorCode = error?.optString("code")?.takeIf(String::isNotBlank),
            errorMessage = error?.optString("message")?.takeIf(String::isNotBlank),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOr(fallback: T, value: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun org.json.JSONArray.toStringSet(): Set<String> =
        (0 until length()).mapTo(linkedSetOf()) { getString(it) }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
