package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ApprovalDecision
import com.woodward.tailcodex.domain.ConnectionConfig
import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.ReviewTarget
import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.domain.SessionState
import com.woodward.tailcodex.domain.ThreadListState
import com.woodward.tailcodex.domain.ThreadState
import com.woodward.tailcodex.domain.TailcodexThread
import com.woodward.tailcodex.domain.TurnState
import com.woodward.tailcodex.domain.ImageAttachment
import com.woodward.tailcodex.domain.CodexSessionFailure
import com.woodward.tailcodex.rpc.RpcFailure
import com.woodward.tailcodex.protocol.CodexWireProtocol
import com.woodward.tailcodex.repository.CodexRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Owns cross-component ordering. UI talks to this class only in domain terms; transport and wire
 * objects terminate at the listeners below.
 */
class SessionCoordinator(
    initialConfig: ConnectionConfig,
    private val connectionManager: ConnectionManager,
    private val repository: CodexRepository,
    private val threadSession: ThreadSession,
    private val serverRequests: ServerRequestManager,
    private val isPinned: (String) -> Boolean = { false },
) : ConnectionManager.Listener, ThreadSession.Listener, ServerRequestManager.Listener {
    private val _state = MutableStateFlow(SessionState(config = initialConfig))
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private var turnBeforeDisconnect: TurnState? = null

    init {
        connectionManager.setListener(this)
        threadSession.setListener(this)
        serverRequests.setListener(this)
    }

    fun connect(config: ConnectionConfig) {
        dispatch(SessionEvent.Configured(config))
        connectionManager.connect(config)
    }

    fun reconnect() = connectionManager.reconnectNow()

    fun disconnect() {
        serverRequests.clear()
        threadSession.close()
        connectionManager.disconnect()
        dispatch(SessionEvent.ClearThread)
    }

    fun updateSearch(value: String) = dispatch(SessionEvent.SearchChanged(value))

    fun loadThreads(cursor: String? = null) {
        if (!connectionManager.isReady()) return
        val previous = (_state.value.threadList as? ThreadListState.Loaded)?.threads.orEmpty()
        dispatch(SessionEvent.ThreadListChanged(ThreadListState.Loading))
        repository.listThreads(_state.value.search, cursor) { result ->
            result.onSuccess { page ->
                val incoming = page.threads.map {
                    it.copy(pinned = isPinned(it.id), hostId = _state.value.config.hostId)
                }
                val threads = if (cursor == null) incoming else (previous + incoming).distinctBy { it.id }
                dispatch(SessionEvent.ThreadListChanged(ThreadListState.Loaded(threads, page.nextCursor)))
            }.onFailure(::fail)
        }
    }

    fun openThread(thread: TailcodexThread) = threadSession.openReadOnly(thread)
    fun startThread() = threadSession.startThread(_state.value.config.defaultCwd)

    fun openThreadById(threadId: String, onComplete: (Result<Unit>) -> Unit) = threadSession.openReadOnly(
        TailcodexThread(
            id = threadId,
            title = "",
            preview = "",
            cwd = _state.value.config.defaultCwd,
            updatedAt = 0,
            status = "unknown",
            hostId = _state.value.config.hostId,
        ),
        { onComplete(it.toDomainFailure()) },
        reportFailure = false,
    )

    fun startThread(onComplete: (Result<Unit>) -> Unit) =
        threadSession.startThread(_state.value.config.defaultCwd) { onComplete(it.toDomainFailure()) }

    fun closeThread() {
        serverRequests.clear()
        threadSession.close()
        dispatch(SessionEvent.ClearThread)
        loadThreads()
    }

    fun send(text: String, images: List<ImageAttachment> = emptyList()) = threadSession.send(text, images)
    fun interruptTurn() = threadSession.interrupt()
    fun resolveApproval(request: ServerRequest, decision: ApprovalDecision) =
        serverRequests.resolveApproval(request, decision)

    fun answerUserInput(request: ServerRequest.UserInput, answers: Map<String, List<String>>) =
        serverRequests.answerUserInput(request, answers)

    fun answerMcp(
        request: ServerRequest.McpElicitation,
        action: String,
        content: Map<String, String>? = null,
    ) = serverRequests.answerMcp(
        request,
        action,
        content?.let { values -> JSONObject().apply { values.forEach(::put) } },
    )

    fun forkThread() {
        threadSession.withWritableThread { snapshot ->
            repository.forkThread(snapshot.thread.id) { result ->
                result.onSuccess { fork -> threadSession.openReadOnly(fork.thread) }.onFailure(::fail)
            }
        }
    }

    fun archiveThread() {
        threadSession.withWritableThread { snapshot ->
            repository.archiveThread(snapshot.thread.id) { result ->
                result.onSuccess { closeThread() }.onFailure(::fail)
            }
        }
    }

    fun pinThreadLocal(pinned: Boolean) {
        val id = threadSession.currentThreadId() ?: return
        dispatch(SessionEvent.ThreadPinned(id, pinned))
    }

    fun startReview(target: ReviewTarget) {
        threadSession.withWritableThread { snapshot ->
            repository.startReview(snapshot.thread.id, target) { result -> result.onFailure(::fail) }
        }
    }

    fun clearNotice() = dispatch(SessionEvent.Notice(null))

    override fun onConnectionStateChanged(state: ConnectionState) =
        dispatch(SessionEvent.ConnectionChanged(state))

    override fun onReady(reconnected: Boolean) {
        val threadId = threadSession.currentThreadId()
        if (reconnected && threadId != null) {
            if (serverRequests.current().isNotEmpty()) {
                serverRequests.clear()
                dispatch(SessionEvent.Notice("旧连接上的待处理请求已作废；等待服务端在新连接重新发送"))
            }
            connectionManager.markReconciling(threadId)
            threadSession.reconcile { result ->
                result.onSuccess {
                    connectionManager.markReconciled()
                    dispatch(SessionEvent.ReconciliationCompleted)
                    publishOfflineOutcome()
                    loadThreads()
                }.onFailure {
                    dispatch(SessionEvent.Failure("重连后对账失败：${it.message ?: "未知错误"}"))
                }
            }
        } else {
            dispatch(SessionEvent.ReconciliationCompleted)
            loadThreads()
        }
    }

    override fun onDisconnected(reason: String) {
        turnBeforeDisconnect = threadSession.currentTurn()
        threadSession.onDisconnected()
        dispatch(SessionEvent.SocketDisconnected(reason))
    }

    override fun onNotification(method: String, params: JSONObject) {
        threadSession.handleNotification(method, params)
        when (method) {
            "serverRequest/resolved" -> resolvedRequestId(params)?.let(serverRequests::resolved)
            "turn/completed" -> {
                val turnId = params.optJSONObject("turn")?.optString("id")?.takeIf(String::isNotBlank)
                serverRequests.turnCompleted(turnId)
            }
            "error" -> fail(IllegalStateException(params.optString("message", "Codex error")))
        }
    }

    override fun onServerRequest(requestId: RpcId, method: String, params: JSONObject) {
        val request = runCatching { CodexWireProtocol.parseServerRequest(requestId, method, params) }
            .getOrElse {
                ServerRequest.Unknown(requestId, method, params.toString())
            }
        when (request) {
            is ServerRequest.CommandApproval,
            is ServerRequest.FileApproval,
            is ServerRequest.PermissionsApproval,
            -> threadSession.markWaiting(request.turnId, TurnState.Phase.WAITING_FOR_APPROVAL)
            is ServerRequest.UserInput -> threadSession.markWaiting(
                request.turnId,
                TurnState.Phase.WAITING_FOR_USER_INPUT,
            )
            is ServerRequest.McpElicitation -> threadSession.markWaiting(
                request.turnId,
                TurnState.Phase.WAITING_FOR_MCP_ELICITATION,
            )
            is ServerRequest.DynamicToolCall, is ServerRequest.Unknown -> Unit
        }
        serverRequests.handle(request)
    }

    override fun onProtocolIssue(message: String) = dispatch(SessionEvent.Notice(message))

    override fun onThreadChanged(state: ThreadState, turn: TurnState) =
        dispatch(SessionEvent.ThreadChanged(state.withPin(), turn))

    override fun onRequestsChanged(requests: List<ServerRequest>) =
        dispatch(SessionEvent.RequestsChanged(requests))

    override fun onUnsupportedRequest(request: ServerRequest) = dispatch(
        SessionEvent.Notice("已显式拒绝不支持的服务端请求：${request.method}"),
    )

    override fun onFailure(message: String) = dispatch(SessionEvent.Failure(message))

    private fun fail(error: Throwable) = onFailure(error.message ?: "未知错误")

    private fun Result<Unit>.toDomainFailure(): Result<Unit> = fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { error ->
            Result.failure(
                when (error) {
                    is RpcFailure.Timeout -> CodexSessionFailure.RpcTimeout(error.method, error)
                    is RpcFailure.Disconnected, is RpcFailure.SendFailed ->
                        CodexSessionFailure.TransportLost(error.message ?: "Codex transport lost", error)
                    is RpcFailure.Protocol ->
                        CodexSessionFailure.Protocol(error.code, error.message ?: "Codex protocol error", error)
                    else -> CodexSessionFailure.Other(error.message ?: "Codex session failed", error)
                },
            )
        },
    )

    @Synchronized
    private fun dispatch(event: SessionEvent) {
        _state.value = SessionReducer.reduce(_state.value, event)
    }

    private fun resolvedRequestId(params: JSONObject): String? {
        val raw = when {
            params.has("requestId") -> params.opt("requestId")
            params.has("id") -> params.opt("id")
            else -> null
        }
        return raw?.toString()?.takeIf(String::isNotBlank)
    }

    private fun publishOfflineOutcome() {
        val before = turnBeforeDisconnect
        val after = threadSession.currentTurn()
        val message = when {
            before is TurnState.Running && after is TurnState.Completed -> "已重连；Turn 在离线期间完成"
            before is TurnState.Running && after is TurnState.Failed -> "已重连；Turn 在离线期间失败"
            before is TurnState.Running && after is TurnState.Interrupted -> "已重连；Turn 在离线期间被中断"
            before != null -> "已重连并完成服务端状态对账"
            else -> null
        }
        turnBeforeDisconnect = null
        if (message != null) dispatch(SessionEvent.Notice(message))
    }

    private fun ThreadState.withPin(): ThreadState = when (this) {
        ThreadState.NoThread -> this
        is ThreadState.ReadOnly -> copy(snapshot = snapshot.copy(thread = snapshot.thread.copy(
            pinned = isPinned(snapshot.thread.id), hostId = _state.value.config.hostId,
        )))
        is ThreadState.Resuming -> copy(snapshot = snapshot.copy(thread = snapshot.thread.copy(
            pinned = isPinned(snapshot.thread.id), hostId = _state.value.config.hostId,
        )))
        is ThreadState.Active -> copy(snapshot = snapshot.copy(thread = snapshot.thread.copy(
            pinned = isPinned(snapshot.thread.id), hostId = _state.value.config.hostId,
        )))
    }
}
