package com.woodward.tailcodex.data

data class ConnectionConfig(
    val endpoint: String = "wss://arch.tailc37750.ts.net:8443",
    val token: String = "",
    val defaultCwd: String = "/home/Woodward",
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

data class ThreadSummary(
    val id: String,
    val title: String,
    val preview: String,
    val cwd: String,
    val updatedAt: Long,
    val status: String,
)

enum class MessageRole { USER, ASSISTANT, EVENT }

data class ChatEntry(
    val id: String,
    val role: MessageRole,
    val text: String,
    val detail: String? = null,
)

enum class ApprovalKind { COMMAND, FILE_CHANGE, PERMISSIONS }

data class ApprovalRequest(
    val rpcId: Any,
    val kind: ApprovalKind,
    val title: String,
    val detail: String,
    val threadId: String?,
    val turnId: String?,
    val rawPermissions: String? = null,
    val availableDecisions: Set<String> = setOf("accept", "acceptForSession", "decline", "cancel"),
)

fun ApprovalRequest.supports(decision: String): Boolean =
    kind == ApprovalKind.PERMISSIONS || decision in availableDecisions

data class TailCodexState(
    val config: ConnectionConfig = ConnectionConfig(),
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val error: String? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val search: String = "",
    val loadingThreads: Boolean = false,
    val activeThread: ThreadSummary? = null,
    val messages: List<ChatEntry> = emptyList(),
    val activeTurnId: String? = null,
    val approvals: List<ApprovalRequest> = emptyList(),
    val reconnectAttempt: Int = 0,
) {
    val approval: ApprovalRequest? get() = approvals.firstOrNull()
}
