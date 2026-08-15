package com.woodward.tailcodex.presentation

import com.woodward.tailcodex.BuildConfig
import com.woodward.tailcodex.domain.ConnectionConfig
import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.TurnState
import com.woodward.tailcodex.domain.SessionState
import com.woodward.tailcodex.domain.CodexSessionFailure
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceState
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentConnectionState
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentException
import com.woodward.tailcodex.hostcontrol.session.HostControlCoordinator
import com.woodward.tailcodex.hostcontrol.protocol.HostControlState
import com.woodward.tailcodex.session.CodexSessionCoordinator
import com.woodward.tailcodex.terminal.TerminalCoordinator
import com.woodward.tailcodex.terminal.UnavailableTerminalCoordinator
import com.woodward.tailcodex.workstation.UnavailableWorkstationCoordinator
import com.woodward.tailcodex.workstation.WorkstationCoordinator
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class HostPlanePhase { DIRECT_WSS_FALLBACK, HOST_OFFLINE, HOST_ONLINE }
enum class CodexLocalPhase { UNKNOWN, CODEX_STOPPED, CODEX_STARTING, CODEX_LOCAL_READY, CODEX_FAILED, CODEX_CONFLICT }
enum class CodexClientPhase {
    CODEX_DISCONNECTED,
    CODEX_WS_CONNECTING,
    CODEX_INITIALIZING,
    CODEX_RECONCILING,
    CODEX_RPC_READY,
    CODEX_READY,
    RPC_DEGRADED,
}
enum class ModelRequestPhase { IDLE, RUNNING, MODEL_REQUEST_FAILED }

enum class AppFailureKind {
    HOST_UNAVAILABLE,
    HOST_AUTHENTICATION_FAILED,
    CODEX_STOPPED,
    CODEX_START_FAILED,
    CODEX_PORT_CONFLICT,
    TRANSPORT_LOST,
    RPC_TIMEOUT,
    INITIALIZATION_FAILED,
    MODEL_REQUEST_FAILED,
    UNKNOWN,
}

data class AppFailure(
    val kind: AppFailureKind,
    val message: String,
)

data class TailCodexAppState(
    val host: HostPlanePhase = HostPlanePhase.DIRECT_WSS_FALLBACK,
    val codexLocal: CodexLocalPhase = CodexLocalPhase.UNKNOWN,
    val codexClient: CodexClientPhase = CodexClientPhase.CODEX_DISCONNECTED,
    val modelRequest: ModelRequestPhase = ModelRequestPhase.IDLE,
    val failure: AppFailure? = null,
)

class TailCodexAppCoordinator(
    val codexSession: CodexSessionCoordinator,
    val hostControl: HostControlCoordinator,
    val terminal: TerminalCoordinator = UnavailableTerminalCoordinator,
    val workstation: WorkstationCoordinator = UnavailableWorkstationCoordinator,
    private val scope: CoroutineScope,
    val hostControlEnabled: Boolean = BuildConfig.HOST_CONTROL_ENABLED,
) {
    private val lastFailure = MutableStateFlow<AppFailure?>(null)

    val state: StateFlow<TailCodexAppState> = combine(
        hostControl.state,
        codexSession.state,
        lastFailure,
        ::composeAppState,
    ).stateIn(scope, SharingStarted.Eagerly, TailCodexAppState())

    fun connect(
        config: ConnectionConfig,
        pairingCode: String,
        deviceId: String,
        lastThreadId: String?,
        onConfigReady: (ConnectionConfig) -> Unit,
    ) {
        lastFailure.value = null
        scope.launch {
            runCatching {
                if (!hostControlEnabled || config.hostAgentEndpoint.isBlank()) {
                    config
                } else {
                    require(config.hostAgentEndpoint.startsWith("https://")) {
                        "Host Agent 端点必须使用 https://"
                    }
                    var readyConfig = config
                    hostControl.configure(
                        config.hostAgentEndpoint,
                        config.hostAgentCredential,
                        operationScope(config),
                    )
                    if (config.hostAgentCredential.isBlank()) {
                        require(pairingCode.isNotBlank()) { "首次连接需要 Host Agent 配对码" }
                        val pairing = hostControl.pair(
                            endpoint = config.hostAgentEndpoint,
                            code = pairingCode,
                            deviceId = deviceId,
                            deviceName = "TailCodex Android",
                        )
                        readyConfig = config.copy(hostAgentCredential = pairing.credential)
                        onConfigReady(readyConfig)
                        hostControl.configure(
                            readyConfig.hostAgentEndpoint,
                            readyConfig.hostAgentCredential,
                            operationScope(readyConfig),
                        )
                    }
                    try {
                        hostControl.ensureCodexReady()
                    } catch (error: Throwable) {
                        if (!error.isHostUnavailable()) throw error
                        recordFailure(error, FailureStage.HOST)
                        // Host and Codex planes fail independently. An already-running app-server
                        // may still be reachable through the preserved 0.2 direct-WSS path.
                    }
                    readyConfig
                }
            }.onSuccess { readyConfig ->
                codexSession.connect(readyConfig)
                if (readyConfig.hostAgentEndpoint.isNotBlank()) {
                    runCatching { enterCodex(lastThreadId) }
                        .onSuccess {
                            if (hostControl.state.value.connection is HostAgentConnectionState.Ready) {
                                lastFailure.value = null
                            }
                        }
                        .onFailure {
                            recordFailure(it, FailureStage.CODEX_ENTRY)
                            codexSession.onFailure(it.message ?: "Codex 已连接，但进入会话失败")
                        }
                }
            }.onFailure {
                recordFailure(it, FailureStage.HOST)
                codexSession.onFailure(it.message ?: "主机启动或连接失败")
            }
        }
    }

    fun reconnect(config: ConnectionConfig) {
        lastFailure.value = null
        scope.launch {
            runCatching {
                if (hostControlEnabled && config.hostAgentEndpoint.isNotBlank() && config.hostAgentCredential.isNotBlank()) {
                    hostControl.configure(
                        config.hostAgentEndpoint,
                        config.hostAgentCredential,
                        operationScope(config),
                    )
                    try {
                        hostControl.ensureCodexReady()
                    } catch (error: Throwable) {
                        if (!error.isHostUnavailable()) throw error
                        recordFailure(error, FailureStage.HOST)
                    }
                }
            }.onSuccess { codexSession.reconnect() }.onFailure {
                recordFailure(it, FailureStage.HOST)
                codexSession.onFailure(it.message ?: "主机重连失败")
            }
        }
    }

    fun restartCodex() {
        if (!hostControlEnabled) {
            codexSession.onFailure("此构建已关闭 Host Control")
            return
        }
        scope.launch {
            runCatching { hostControl.restartCodex() }
                .onSuccess { codexSession.reconnect() }
                .onFailure {
                    recordFailure(it, FailureStage.HOST)
                    codexSession.onFailure(it.message ?: "Codex 重启失败")
                }
        }
    }

    fun disconnect() {
        codexSession.disconnect()
        hostControl.disconnect()
        lastFailure.value = null
    }

    private suspend fun enterCodex(lastThreadId: String?) {
        withTimeout(CODEX_ENTRY_TIMEOUT_MILLIS) {
            codexSession.state.first { it.connection is ConnectionState.Ready }
            if (!lastThreadId.isNullOrBlank()) {
                val read = CompletableDeferred<Result<Unit>>()
                codexSession.openThreadById(lastThreadId) { read.complete(it) }
                val readResult = read.await()
                if (readResult.isSuccess) return@withTimeout
                val readError = readResult.exceptionOrNull() ?: error("读取上次 Codex 会话失败")
                if (!readError.isMissingCodexThread()) throw readError
            }
            val started = CompletableDeferred<Result<Unit>>()
            codexSession.startThread { started.complete(it) }
            started.await().getOrThrow()
        }
    }

    private fun Throwable.isHostUnavailable(): Boolean =
        this is IOException ||
            this is HostAgentException && (code == "INVALID_RESPONSE" || httpStatus in HOST_UNAVAILABLE_HTTP_CODES)

    private fun recordFailure(error: Throwable, stage: FailureStage) {
        lastFailure.value = AppFailure(
            classifyAppFailure(error, stage),
            error.message ?: "未知错误",
        )
    }

    private fun operationScope(config: ConnectionConfig): String =
        config.hostId + "\u0000" + config.hostAgentEndpoint.trim().trimEnd('/')

    private companion object {
        const val CODEX_ENTRY_TIMEOUT_MILLIS = 30_000L
        val HOST_UNAVAILABLE_HTTP_CODES = setOf(408, 502, 503, 504)
    }
}

internal enum class FailureStage { HOST, CODEX_ENTRY }

internal fun composeAppState(
    host: HostControlState,
    codex: SessionState,
    failure: AppFailure?,
): TailCodexAppState {
    val connectionFailure = (codex.connection as? ConnectionState.Disconnected)
        ?.reason
        ?.takeIf(String::isNotBlank)
        ?.let { reason ->
            AppFailure(
                if (reason.contains("initial", ignoreCase = true)) {
                    AppFailureKind.INITIALIZATION_FAILED
                } else {
                    AppFailureKind.TRANSPORT_LOST
                },
                reason,
            )
        }
    val modelFailure = (codex.turn as? TurnState.Failed)?.let {
        AppFailure(AppFailureKind.MODEL_REQUEST_FAILED, it.message ?: "模型请求失败")
    }
    return TailCodexAppState(
        host = when (host.connection) {
            HostAgentConnectionState.Unconfigured -> HostPlanePhase.DIRECT_WSS_FALLBACK
            is HostAgentConnectionState.Ready -> HostPlanePhase.HOST_ONLINE
            else -> HostPlanePhase.HOST_OFFLINE
        },
        codexLocal = when (host.service?.state) {
            null -> CodexLocalPhase.UNKNOWN
            CodexServiceState.STOPPED -> CodexLocalPhase.CODEX_STOPPED
            CodexServiceState.STARTING -> CodexLocalPhase.CODEX_STARTING
            CodexServiceState.LOCAL_READY, CodexServiceState.EXTERNAL -> CodexLocalPhase.CODEX_LOCAL_READY
            CodexServiceState.FAILED -> CodexLocalPhase.CODEX_FAILED
            CodexServiceState.CONFLICT -> CodexLocalPhase.CODEX_CONFLICT
        },
        codexClient = when {
            codex.stale -> CodexClientPhase.RPC_DEGRADED
            codex.connection is ConnectionState.Connecting -> CodexClientPhase.CODEX_WS_CONNECTING
            codex.connection is ConnectionState.Initializing -> CodexClientPhase.CODEX_INITIALIZING
            codex.connection is ConnectionState.Reconciling -> CodexClientPhase.CODEX_RECONCILING
            codex.connection is ConnectionState.Ready &&
                codex.currentThread != null &&
                (host.connection !is HostAgentConnectionState.Ready || host.codexLocallyReady) ->
                CodexClientPhase.CODEX_READY
            codex.connection is ConnectionState.Ready -> CodexClientPhase.CODEX_RPC_READY
            else -> CodexClientPhase.CODEX_DISCONNECTED
        },
        modelRequest = when (codex.turn) {
            is TurnState.Running -> ModelRequestPhase.RUNNING
            is TurnState.Failed -> ModelRequestPhase.MODEL_REQUEST_FAILED
            else -> ModelRequestPhase.IDLE
        },
        failure = modelFailure ?: connectionFailure ?: failure,
    )
}

internal fun classifyAppFailure(error: Throwable, stage: FailureStage): AppFailureKind = when {
    error is HostAgentException && error.httpStatus == 401 -> AppFailureKind.HOST_AUTHENTICATION_FAILED
    error is HostAgentException && error.code == "CODEX_PORT_CONFLICT" -> AppFailureKind.CODEX_PORT_CONFLICT
    error is HostAgentException && error.code == "CODEX_NOT_READY" -> AppFailureKind.CODEX_STOPPED
    error is HostAgentException && error.code in setOf(
        "CODEX_START_FAILED",
        "CODEX_READY_TIMEOUT",
        "NO_LIFECYCLE_ADAPTER",
        "NATIVE_DAEMON_DISABLED",
        "OPERATION_FAILED",
    ) -> AppFailureKind.CODEX_START_FAILED
    error is IOException || error is HostAgentException &&
        (error.code == "INVALID_RESPONSE" || error.httpStatus in setOf(408, 502, 503, 504)) ->
        if (stage == FailureStage.HOST) AppFailureKind.HOST_UNAVAILABLE else AppFailureKind.TRANSPORT_LOST
    error is CodexSessionFailure.RpcTimeout -> AppFailureKind.RPC_TIMEOUT
    error is CodexSessionFailure.TransportLost -> AppFailureKind.TRANSPORT_LOST
    error is TimeoutCancellationException && stage == FailureStage.CODEX_ENTRY -> AppFailureKind.INITIALIZATION_FAILED
    stage == FailureStage.CODEX_ENTRY -> AppFailureKind.INITIALIZATION_FAILED
    else -> AppFailureKind.UNKNOWN
}

internal fun Throwable.isMissingCodexThread(): Boolean {
    if (this !is CodexSessionFailure.Protocol) return false
    val normalized = message.orEmpty().lowercase()
    return normalized.contains("thread") &&
        (normalized.contains("not found") || normalized.contains("does not exist") || normalized.contains("unknown"))
}
