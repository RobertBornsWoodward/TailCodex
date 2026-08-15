package com.woodward.tailcodex.domain

data class ConnectionConfig(
    val endpoint: String = "wss://arch.tailc37750.ts.net:8443",
    val token: String = "",
    val defaultCwd: String = "/home/Woodward",
    val hostId: String = "default",
    val hostName: String = "Arch",
    val hostAgentEndpoint: String = "",
    val hostAgentCredential: String = "",
)

data class HostProfile(
    val id: String = "default",
    val name: String = "Arch",
    val endpoint: String,
    val credential: String,
    val defaultCwd: String,
    val lastThreadId: String? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected(),
    val hostAgentEndpoint: String = "",
    val hostAgentCredential: String = "",
)

sealed interface ConnectionState {
    data class Disconnected(
        val reason: String? = null,
        val staleSnapshot: Boolean = false,
    ) : ConnectionState

    data class Connecting(val reconnectAttempt: Int = 0) : ConnectionState
    data class Initializing(val reconnectAttempt: Int = 0) : ConnectionState
    data class Reconciling(val threadId: String, val reconnectAttempt: Int = 0) : ConnectionState
    data object Ready : ConnectionState
}

sealed class CodexSessionFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RpcTimeout(val method: String, cause: Throwable? = null) :
        CodexSessionFailure("RPC timed out: $method", cause)

    class TransportLost(message: String, cause: Throwable? = null) : CodexSessionFailure(message, cause)
    class Protocol(val code: Int, message: String, cause: Throwable? = null) :
        CodexSessionFailure(message, cause)

    class Other(message: String, cause: Throwable? = null) : CodexSessionFailure(message, cause)
}

enum class ThreadLease {
    NONE,
    LOCAL_PHONE,
    OTHER_CLIENT,
    UNKNOWN,
}

data class TailcodexThread(
    val id: String,
    val title: String,
    val preview: String,
    val cwd: String,
    val updatedAt: Long,
    val status: String,
    val pinned: Boolean = false,
    val hostId: String = "default",
)

sealed interface TurnState {
    data object Idle : TurnState

    data class Running(
        val turnId: String,
        val phase: Phase = Phase.EXECUTING,
    ) : TurnState

    data class Completed(val turnId: String?) : TurnState
    data class Interrupted(val turnId: String?) : TurnState
    data class Failed(val turnId: String?, val message: String?) : TurnState

    enum class Phase {
        EXECUTING,
        WAITING_FOR_APPROVAL,
        WAITING_FOR_USER_INPUT,
        WAITING_FOR_MCP_ELICITATION,
    }
}

data class TailcodexTurn(
    val id: String,
    val state: TurnState,
)

enum class MessageRole { USER, ASSISTANT }

sealed interface ConversationItem {
    val id: String

    data class Message(
        override val id: String,
        val role: MessageRole,
        val markdown: String,
    ) : ConversationItem

    data class CommandExecution(
        override val id: String,
        val command: String,
        val cwd: String? = null,
        val status: String,
        val output: String = "",
    ) : ConversationItem

    data class FileChange(
        override val id: String,
        val files: List<String>,
        val status: String,
        val unifiedDiff: String? = null,
    ) : ConversationItem

    data class McpCall(
        override val id: String,
        val server: String,
        val tool: String,
        val status: String,
        val output: String? = null,
    ) : ConversationItem

    data class Review(
        override val id: String,
        val title: String,
        val status: String,
        val body: String? = null,
    ) : ConversationItem

    data class Status(
        override val id: String,
        val label: String,
        val detail: String? = null,
    ) : ConversationItem
}

data class ThreadSnapshot(
    val thread: TailcodexThread,
    val items: List<ConversationItem>,
    val turn: TurnState,
)

sealed interface ThreadState {
    data object NoThread : ThreadState
    data class ReadOnly(val snapshot: ThreadSnapshot, val lease: ThreadLease) : ThreadState
    data class Resuming(val snapshot: ThreadSnapshot) : ThreadState
    data class Active(val snapshot: ThreadSnapshot, val lease: ThreadLease) : ThreadState
}

sealed interface ThreadListState {
    data object Idle : ThreadListState
    data object Loading : ThreadListState
    data class Loaded(
        val threads: List<TailcodexThread>,
        val nextCursor: String?,
    ) : ThreadListState
}

data class RpcId(val raw: Any) {
    override fun toString(): String = raw.toString()
}

enum class ApprovalDecision(val wireValue: String) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

sealed interface ServerRequest {
    val requestId: RpcId
    val method: String
    val threadId: String?
    val turnId: String?

    data class CommandApproval(
        override val requestId: RpcId,
        override val threadId: String,
        override val turnId: String,
        val itemId: String,
        val command: String?,
        val cwd: String?,
        val reason: String?,
        val networkHost: String?,
        val networkProtocol: String?,
        val availableDecisions: Set<ApprovalDecision>,
    ) : ServerRequest {
        override val method: String = "item/commandExecution/requestApproval"
    }

    data class FileApproval(
        override val requestId: RpcId,
        override val threadId: String,
        override val turnId: String,
        val itemId: String,
        val reason: String?,
        val grantRoot: String?,
    ) : ServerRequest {
        override val method: String = "item/fileChange/requestApproval"
    }

    data class PermissionsApproval(
        override val requestId: RpcId,
        override val threadId: String,
        override val turnId: String,
        val itemId: String,
        val cwd: String,
        val reason: String?,
        val permissionsJson: String,
    ) : ServerRequest {
        override val method: String = "item/permissions/requestApproval"
    }

    data class UserInput(
        override val requestId: RpcId,
        override val threadId: String,
        override val turnId: String,
        val itemId: String,
        val questions: List<UserInputQuestion>,
        val isBlocking: Boolean,
    ) : ServerRequest {
        override val method: String = "item/tool/requestUserInput"
    }

    data class McpElicitation(
        override val requestId: RpcId,
        override val threadId: String,
        override val turnId: String?,
        val serverName: String,
        val mode: String,
        val message: String,
        val requestedSchemaJson: String?,
        val url: String?,
    ) : ServerRequest {
        override val method: String = "mcpServer/elicitation/request"
    }

    data class DynamicToolCall(
        override val requestId: RpcId,
        override val threadId: String?,
        override val turnId: String?,
        val tool: String,
        val argumentsJson: String,
    ) : ServerRequest {
        override val method: String = "item/tool/call"
    }

    data class Unknown(
        override val requestId: RpcId,
        override val method: String,
        val rawPayload: String,
        override val threadId: String? = null,
        override val turnId: String? = null,
    ) : ServerRequest
}

data class UserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<UserInputOption>,
    val allowsOther: Boolean,
    val secret: Boolean,
)

data class UserInputOption(val label: String, val description: String)

data class ImageAttachment(
    val name: String,
    val dataUrl: String,
)

data class CommandExecution(
    val itemId: String,
    val command: String,
    val cwd: String?,
    val status: String,
    val output: String,
)

data class FileChange(
    val itemId: String,
    val paths: List<String>,
    val status: String,
    val unifiedDiff: String?,
)

data class McpElicitation(
    val serverName: String,
    val message: String,
    val mode: String,
)

sealed interface ReviewTarget {
    data object UncommittedChanges : ReviewTarget
    data class BaseBranch(val branch: String) : ReviewTarget
    data class Commit(val sha: String, val title: String? = null) : ReviewTarget
    data class Custom(val instructions: String) : ReviewTarget
}

data class SessionState(
    val config: ConnectionConfig = ConnectionConfig(),
    val connection: ConnectionState = ConnectionState.Disconnected(),
    val threadList: ThreadListState = ThreadListState.Idle,
    val search: String = "",
    val thread: ThreadState = ThreadState.NoThread,
    val turn: TurnState = TurnState.Idle,
    val serverRequests: List<ServerRequest> = emptyList(),
    val stale: Boolean = false,
    val reconnectAttempt: Int = 0,
    val error: String? = null,
    val notice: String? = null,
) {
    val hostId: String get() = config.hostId
    val currentThread: TailcodexThread?
        get() = when (val current = thread) {
            ThreadState.NoThread -> null
            is ThreadState.ReadOnly -> current.snapshot.thread
            is ThreadState.Resuming -> current.snapshot.thread
            is ThreadState.Active -> current.snapshot.thread
        }

    val items: List<ConversationItem>
        get() = when (val current = thread) {
            ThreadState.NoThread -> emptyList()
            is ThreadState.ReadOnly -> current.snapshot.items
            is ThreadState.Resuming -> current.snapshot.items
            is ThreadState.Active -> current.snapshot.items
        }
}

data class RemoteDesktopTarget(
    val hostId: String,
    val label: String,
    val launchUri: String,
)

interface RemoteDesktopEngine {
    val id: String
    val displayName: String
    fun isAvailable(): Boolean
    fun validate(target: RemoteDesktopTarget): Result<Unit>
}
