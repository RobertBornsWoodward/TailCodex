package com.woodward.tailcodex.hostcontrol.session

import com.woodward.tailcodex.BuildConfig
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceSnapshot
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceState
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentConfig
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentConnectionState
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentException
import com.woodward.tailcodex.hostcontrol.protocol.HostControlState
import com.woodward.tailcodex.hostcontrol.protocol.HostHello
import com.woodward.tailcodex.hostcontrol.protocol.HostOperation
import com.woodward.tailcodex.hostcontrol.protocol.HostOperationStatus
import com.woodward.tailcodex.hostcontrol.protocol.HostPairingResult
import com.woodward.tailcodex.hostcontrol.repository.HostAgentRepository
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HostControlCoordinator(
    private val api: HostAgentRepository,
    private val checkpoints: HostOperationCheckpointStore = InMemoryHostOperationCheckpointStore(),
) {
    private val mutableState = MutableStateFlow(HostControlState())
    val state: StateFlow<HostControlState> = mutableState.asStateFlow()

    private var config: HostAgentConfig? = null
    private var profileId: String? = null

    fun configure(endpoint: String, credential: String, profileId: String = endpoint) {
        config = HostAgentConfig(endpoint.trimEnd('/'), credential.trim())
        this.profileId = profileId
        mutableState.value = mutableState.value.copy(
            connection = if (credential.isBlank()) HostAgentConnectionState.Disconnected else HostAgentConnectionState.Connecting,
            error = null,
        )
    }

    suspend fun pair(endpoint: String, code: String, deviceId: String, deviceName: String): HostPairingResult {
        mutableState.value = mutableState.value.copy(connection = HostAgentConnectionState.Pairing, error = null)
        return runCatching { api.pair(endpoint.trimEnd('/'), code.trim(), deviceId, deviceName) }
            .onSuccess { configure(endpoint, it.credential) }
            .onFailure(::recordFailure)
            .getOrThrow()
    }

    suspend fun refresh(): HostControlState {
        val active = requireConfig()
        mutableState.value = mutableState.value.copy(connection = HostAgentConnectionState.Connecting, error = null)
        return runCatching {
            val hello = api.hello(active.endpoint)
            validateCompatibility(hello)
            val (features, grants) = api.capabilities(active)
            val snapshot = api.services(active)
            var logSummaryError: String? = null
            val recentLogs = if (LOGS_CAPABILITY in features && LOGS_CAPABILITY in grants) {
                runCatching { api.logSummary(active) }
                    .onFailure { logSummaryError = it.message ?: "主机日志摘要不可用" }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            mutableState.value = mutableState.value.copy(
                connection = HostAgentConnectionState.Ready(hello.agentVersion),
                service = snapshot,
                features = features,
                grants = grants,
                recentLogs = recentLogs,
                logSummaryError = logSummaryError,
                error = null,
            )
            mutableState.value
        }.onFailure(::recordFailure).getOrThrow()
    }

    suspend fun ensureCodexReady(): CodexServiceSnapshot {
        try {
            val active = requireConfig()
            val refreshed = refresh().service ?: error("Host Agent 未返回 Codex 状态")
            requireLifecycleCapability()
            recoverOperation(active)?.let { operation ->
                requireSuccessful(operation, "Codex 启动")
                return refresh().service?.requireReady()
                    ?: error("Codex 操作成功但服务状态缺失")
            }
            if (refreshed.state == CodexServiceState.LOCAL_READY || refreshed.state == CodexServiceState.EXTERNAL) {
                return refreshed
            }
            val operation = startAndAwait(active, ACTION_ENSURE) { requestId, idempotencyKey ->
                api.ensureCodexRunning(active, requestId, idempotencyKey)
            }
            requireSuccessful(operation, "Codex 启动")
            return refresh().service?.requireReady() ?: error("Codex 操作成功但服务状态缺失")
        } catch (error: Throwable) {
            recordFailure(error)
            throw error
        }
    }

    suspend fun restartCodex(): CodexServiceSnapshot {
        try {
            val active = requireConfig()
            refresh()
            requireLifecycleCapability()
            val operation = recoverOperation(active) ?: startAndAwait(active, ACTION_RESTART) { requestId, idempotencyKey ->
                api.restartCodex(active, requestId, idempotencyKey)
            }
            requireSuccessful(operation, "Codex 重启")
            return refresh().service?.requireReady() ?: error("Codex 重启成功但服务状态缺失")
        } catch (error: Throwable) {
            recordFailure(error)
            throw error
        }
    }

    fun disconnect() {
        config = null
        profileId = null
        mutableState.value = HostControlState()
    }

    private suspend fun recoverOperation(active: HostAgentConfig): HostOperation? {
        val activeProfile = requireProfileId()
        val checkpoint = checkpoints.load(activeProfile) ?: return null
        val operationId = checkpoint.operationId ?: run {
            val acceptedId = submitCheckpoint(active, checkpoint)
            checkpoints.save(activeProfile, checkpoint.copy(operationId = acceptedId))
            acceptedId
        }
        return try {
            awaitCheckpointedOperation(active, activeProfile, operationId)
        } catch (error: HostAgentException) {
            if (error.code != "OPERATION_NOT_FOUND" && error.httpStatus != 404) throw error
            // A revoked/re-paired device cannot read the old device's operation. A definitive 404
            // is safe to discard; transport failures are not and leave the checkpoint intact.
            checkpoints.clear(activeProfile)
            null
        }
    }

    private suspend fun startAndAwait(
        active: HostAgentConfig,
        kind: String,
        submit: suspend (requestId: String, idempotencyKey: String) -> String,
    ): HostOperation {
        val activeProfile = requireProfileId()
        val requestId = "android-${UUID.randomUUID()}"
        val checkpoint = HostOperationCheckpoint(kind, requestId, requestId)
        // Commit before POST. If Android dies after the host accepts the request, the same
        // idempotency key is submitted again and resolves to the original operation.
        checkpoints.save(activeProfile, checkpoint)
        val operationId = submit(requestId, requestId)
        checkpoints.save(activeProfile, checkpoint.copy(operationId = operationId))
        return awaitCheckpointedOperation(active, activeProfile, operationId)
    }

    private suspend fun submitCheckpoint(active: HostAgentConfig, checkpoint: HostOperationCheckpoint): String =
        when (checkpoint.kind) {
            ACTION_ENSURE -> api.ensureCodexRunning(active, checkpoint.requestId, checkpoint.idempotencyKey)
            ACTION_RESTART -> api.restartCodex(active, checkpoint.requestId, checkpoint.idempotencyKey)
            else -> {
                checkpoints.clear(requireProfileId())
                throw HostAgentException("UNKNOWN_OPERATION_CHECKPOINT", "无法恢复未知 Host Agent 操作")
            }
        }

    private suspend fun awaitCheckpointedOperation(
        active: HostAgentConfig,
        activeProfile: String,
        operationId: String,
    ): HostOperation {
        val operation = awaitOperation(active, operationId)
        if (operation.status in TERMINAL_OPERATION_STATES) checkpoints.clear(activeProfile)
        return operation
    }

    private fun requireSuccessful(operation: HostOperation, label: String) {
        if (operation.status == HostOperationStatus.SUCCEEDED) return
        val message = operation.errorMessage ?: "$label 操作失败：${operation.status}"
        throw HostAgentException(operation.errorCode ?: "OPERATION_FAILED", message)
    }

    private fun CodexServiceSnapshot.requireReady(): CodexServiceSnapshot {
        if (state == CodexServiceState.LOCAL_READY || state == CodexServiceState.EXTERNAL) return this
        val code = if (state == CodexServiceState.CONFLICT) "CODEX_PORT_CONFLICT" else "CODEX_NOT_READY"
        throw HostAgentException(code, detail ?: "Host Agent 操作完成，但 Codex 尚未就绪：$state")
    }

    private fun requireLifecycleCapability() {
        val current = mutableState.value
        if (LIFECYCLE_CAPABILITY !in current.features) {
            throw HostAgentException("FEATURE_UNAVAILABLE", "此 Host Agent 不支持 Codex 生命周期控制")
        }
        if (LIFECYCLE_CAPABILITY !in current.grants) {
            throw HostAgentException("GRANT_REQUIRED", "此设备没有 Codex 生命周期控制授权", 403)
        }
    }

    private suspend fun awaitOperation(active: HostAgentConfig, operationId: String): HostOperation {
        repeat(MAX_OPERATION_POLLS) {
            val operation = api.operation(active, operationId)
            mutableState.value = mutableState.value.copy(operation = operation, error = null)
            if (operation.status in TERMINAL_OPERATION_STATES) return operation
            delay(OPERATION_POLL_MILLIS)
        }
        throw HostAgentException("OPERATION_TIMEOUT", "等待 Host Agent 操作完成超时")
    }

    private fun validateCompatibility(hello: HostHello) {
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            val message = "Host Agent 协议 ${hello.protocolVersion} 与客户端协议 $PROTOCOL_VERSION 不兼容"
            mutableState.value = mutableState.value.copy(connection = HostAgentConnectionState.Incompatible(message), error = message)
            throw HostAgentException("INCOMPATIBLE_PROTOCOL", message)
        }
        if (compareVersions(BuildConfig.VERSION_NAME, hello.minClientVersion) < 0) {
            val message = "Host Agent 要求 TailCodex ${hello.minClientVersion} 或更高版本"
            mutableState.value = mutableState.value.copy(connection = HostAgentConnectionState.Incompatible(message), error = message)
            throw HostAgentException("CLIENT_TOO_OLD", message)
        }
    }

    private fun requireConfig(): HostAgentConfig = config?.takeIf { it.credential.isNotBlank() }
        ?: throw HostAgentException("HOST_AGENT_UNCONFIGURED", "尚未配对 Host Agent")

    private fun requireProfileId(): String = profileId
        ?: throw HostAgentException("HOST_AGENT_UNCONFIGURED", "尚未配置 Host Agent 主机身份")

    private fun recordFailure(error: Throwable) {
        val hostError = error as? HostAgentException
        val connection = when {
            hostError?.httpStatus == 401 -> HostAgentConnectionState.AuthenticationFailed(error.message.orEmpty())
            hostError?.code == "INCOMPATIBLE_PROTOCOL" || hostError?.code == "CLIENT_TOO_OLD" ->
                HostAgentConnectionState.Incompatible(error.message.orEmpty())
            hostError?.httpStatus != null && hostError.httpStatus in HOST_UNAVAILABLE_HTTP_CODES ->
                HostAgentConnectionState.Disconnected
            hostError != null && hostError.code != "INVALID_RESPONSE" -> mutableState.value.connection
            else -> HostAgentConnectionState.Disconnected
        }
        mutableState.value = mutableState.value.copy(connection = connection, error = error.message ?: "Host Agent 请求失败")
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(a.size, b.size)) { index ->
            val difference = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val LIFECYCLE_CAPABILITY = "codex.lifecycle"
        const val LOGS_CAPABILITY = "host.logs"
        const val ACTION_ENSURE = "codex.ensure-running"
        const val ACTION_RESTART = "codex.restart"
        const val MAX_OPERATION_POLLS = 140
        const val OPERATION_POLL_MILLIS = 250L
        val TERMINAL_OPERATION_STATES = setOf(
            HostOperationStatus.SUCCEEDED,
            HostOperationStatus.FAILED,
            HostOperationStatus.CANCELLED,
        )
        val HOST_UNAVAILABLE_HTTP_CODES = setOf(408, 502, 503, 504)
    }
}
