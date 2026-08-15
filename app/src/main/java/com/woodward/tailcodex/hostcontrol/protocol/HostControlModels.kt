package com.woodward.tailcodex.hostcontrol.protocol

data class HostAgentConfig(
    val endpoint: String,
    val credential: String,
)

data class HostHello(
    val protocolVersion: Int,
    val agentVersion: String,
    val minClientVersion: String,
)

data class HostPairingResult(
    val deviceId: String,
    val credential: String,
    val grants: Set<String>,
)

enum class CodexOwnership {
    MANAGED_SYSTEMD,
    MANAGED_NATIVE,
    EXTERNAL,
    UNKNOWN,
    CONFLICT,
}

enum class CodexServiceState {
    STOPPED,
    STARTING,
    LOCAL_READY,
    FAILED,
    EXTERNAL,
    CONFLICT,
}

data class CodexServiceSnapshot(
    val ownership: CodexOwnership,
    val state: CodexServiceState,
    val portOpen: Boolean,
    val ready: Boolean,
    val detail: String?,
)

enum class HostOperationStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class HostOperation(
    val id: String,
    val kind: String,
    val status: HostOperationStatus,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

data class HostLogSummaryEntry(
    val timestamp: String,
    val actor: String,
    val action: String,
    val riskLevel: String,
    val outcome: String,
)

sealed interface HostAgentConnectionState {
    data object Unconfigured : HostAgentConnectionState
    data object Disconnected : HostAgentConnectionState
    data object Connecting : HostAgentConnectionState
    data object Pairing : HostAgentConnectionState
    data class Ready(val agentVersion: String) : HostAgentConnectionState
    data class AuthenticationFailed(val message: String) : HostAgentConnectionState
    data class Incompatible(val message: String) : HostAgentConnectionState
}

data class HostControlState(
    val connection: HostAgentConnectionState = HostAgentConnectionState.Unconfigured,
    val service: CodexServiceSnapshot? = null,
    val operation: HostOperation? = null,
    val features: Set<String> = emptySet(),
    val grants: Set<String> = emptySet(),
    val recentLogs: List<HostLogSummaryEntry> = emptyList(),
    val logSummaryError: String? = null,
    val error: String? = null,
) {
    val codexLocallyReady: Boolean
        get() = service?.state == CodexServiceState.LOCAL_READY || service?.state == CodexServiceState.EXTERNAL
}

class HostAgentException(
    val code: String,
    override val message: String,
    val httpStatus: Int? = null,
) : Exception(message)
