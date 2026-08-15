package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ConversationItem
import com.woodward.tailcodex.domain.MessageRole
import com.woodward.tailcodex.domain.ThreadLease
import com.woodward.tailcodex.domain.ThreadSnapshot
import com.woodward.tailcodex.domain.ThreadState
import com.woodward.tailcodex.domain.TailcodexThread
import com.woodward.tailcodex.domain.TurnState
import com.woodward.tailcodex.domain.ImageAttachment
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.protocol.CodexWireProtocol
import com.woodward.tailcodex.repository.CodexRepository
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class ThreadSession(
    private val repository: CodexRepository,
    private val leaseManager: LeaseManager,
    private val connectionReady: () -> Boolean,
    private val connectionGeneration: () -> Long,
    private val hostIdentity: () -> String = { "default" },
) {
    interface Listener {
        fun onThreadChanged(state: ThreadState, turn: TurnState)
        fun onFailure(message: String)
    }

    private val localIds = AtomicLong(1)

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var state: ThreadState = ThreadState.NoThread

    @Volatile
    private var turn: TurnState = TurnState.Idle

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun currentState(): ThreadState = state
    fun currentTurn(): TurnState = turn
    fun currentThreadId(): String? = snapshot()?.thread?.id

    fun canRespond(request: ServerRequest): Boolean {
        val active = state as? ThreadState.Active ?: return false
        if (request.threadId != null && request.threadId != active.snapshot.thread.id) return false
        return connectionReady() && leaseManager.isValidLocal(connectionGeneration(), hostIdentity())
    }

    fun openReadOnly(
        thread: TailcodexThread,
        onComplete: ((Result<Unit>) -> Unit)? = null,
        reportFailure: Boolean = true,
    ) {
        repository.readThread(thread.id) { result ->
            result.onSuccess { snapshot ->
                val lease = leaseManager.fromRead(snapshot.thread.status, hostIdentity())
                turn = snapshot.turn
                update(ThreadState.ReadOnly(snapshot, lease))
                onComplete?.invoke(Result.success(Unit))
            }.onFailure {
                if (reportFailure) fail(it)
                onComplete?.invoke(Result.failure(it))
            }
        }
    }

    fun reconcile(onComplete: (Result<Unit>) -> Unit) {
        val threadId = currentThreadId()
        if (threadId == null) {
            onComplete(Result.success(Unit))
            return
        }
        repository.readThread(threadId) { result ->
            result.onSuccess { snapshot ->
                val lease = leaseManager.fromReconciliation(snapshot.thread.status, hostIdentity())
                turn = snapshot.turn
                update(ThreadState.ReadOnly(snapshot, lease))
                onComplete(Result.success(Unit))
            }.onFailure {
                fail(it)
                onComplete(Result.failure(it))
            }
        }
    }

    fun startThread(cwd: String, onComplete: ((Result<Unit>) -> Unit)? = null) {
        if (!connectionReady()) {
            val error = IllegalStateException("Connection is not ready")
            fail(error)
            onComplete?.invoke(Result.failure(error))
            return
        }
        repository.startThread(cwd) { result ->
            result.onSuccess { snapshot ->
                leaseManager.claimLocal(connectionGeneration(), hostIdentity())
                turn = snapshot.turn
                update(ThreadState.Active(snapshot, ThreadLease.LOCAL_PHONE))
                onComplete?.invoke(Result.success(Unit))
            }.onFailure {
                fail(it)
                onComplete?.invoke(Result.failure(it))
            }
        }
    }

    fun close() {
        leaseManager.clear()
        turn = TurnState.Idle
        update(ThreadState.NoThread)
    }

    fun onDisconnected() {
        leaseManager.onDisconnected(state !is ThreadState.NoThread)
    }

    fun send(text: String, images: List<ImageAttachment> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isBlank() && images.isEmpty()) return
        ensureWritable { snapshot ->
            val optimistic = ConversationItem.Message(
                id = "local-${localIds.getAndIncrement()}",
                role = MessageRole.USER,
                markdown = buildString {
                    append(trimmed)
                    if (images.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append(images.joinToString("\n") { "[图片: ${it.name}]" })
                    }
                },
            )
            replaceSnapshot(snapshot.copy(items = snapshot.items + optimistic))
            when (val currentTurn = turn) {
                is TurnState.Running -> repository.steerTurn(
                    snapshot.thread.id,
                    currentTurn.turnId,
                    trimmed,
                    images,
                ) { result -> result.onFailure(::fail) }
                else -> repository.startTurn(snapshot.thread.id, trimmed, images) { result ->
                    result.onSuccess { turnId ->
                        if (turnId != null) setTurn(TurnState.Running(turnId))
                    }.onFailure(::fail)
                }
            }
        }
    }

    fun interrupt() {
        val running = turn as? TurnState.Running ?: return
        ensureWritable { snapshot ->
            repository.interruptTurn(snapshot.thread.id, running.turnId) { result -> result.onFailure(::fail) }
        }
    }

    fun withWritableThread(block: (ThreadSnapshot) -> Unit) = ensureWritable(block)

    fun markWaiting(requestTurnId: String?, phase: TurnState.Phase) {
        val running = turn as? TurnState.Running ?: requestTurnId?.let { TurnState.Running(it) } ?: return
        setTurn(running.copy(phase = phase))
    }

    fun markRequestResolved(requestTurnId: String?) {
        val running = turn as? TurnState.Running ?: return
        if (requestTurnId == null || requestTurnId == running.turnId) {
            setTurn(running.copy(phase = TurnState.Phase.EXECUTING))
        }
    }

    fun handleNotification(method: String, params: JSONObject) {
        val activeId = currentThreadId() ?: return
        val eventThreadId = params.optString("threadId")
        if (eventThreadId.isNotBlank() && eventThreadId != activeId) return
        when (method) {
            "item/agentMessage/delta" -> appendAgentDelta(
                params.optString("itemId").ifBlank { "streaming-agent" },
                params.optString("delta"),
            )
            "item/commandExecution/outputDelta" -> appendCommandDelta(
                params.optString("itemId"),
                params.optString("delta"),
            )
            "item/started", "item/completed" -> {
                params.optJSONObject("item")?.let(CodexWireProtocol::parseItem)?.let(::upsertItem)
            }
            "turn/started" -> {
                val id = params.optJSONObject("turn")?.optString("id").orEmpty()
                if (id.isNotBlank()) setTurn(TurnState.Running(id))
            }
            "turn/completed" -> {
                val wireTurn = params.optJSONObject("turn") ?: JSONObject()
                setTurn(
                    CodexWireProtocol.parseTurnState(
                        wireTurn.optString("id"),
                        wireTurn.opt("status"),
                        wireTurn.optJSONObject("error"),
                    ),
                )
            }
        }
    }

    private fun ensureWritable(block: (ThreadSnapshot) -> Unit) {
        if (!connectionReady()) {
            fail(IllegalStateException("Write rejected: connection is not ready"))
            return
        }
        when (val current = state) {
            ThreadState.NoThread -> fail(IllegalStateException("Write rejected: no thread selected"))
            is ThreadState.Resuming -> fail(IllegalStateException("Write rejected: thread is still resuming"))
            is ThreadState.Active -> {
                if (!leaseManager.isValidLocal(connectionGeneration(), hostIdentity())) {
                    fail(IllegalStateException("Write rejected: local soft lease is stale"))
                } else {
                    block(current.snapshot)
                }
            }
            is ThreadState.ReadOnly -> revalidateAndResume(current.snapshot, block)
        }
    }

    private fun revalidateAndResume(snapshot: ThreadSnapshot, block: (ThreadSnapshot) -> Unit) {
        repository.readThread(snapshot.thread.id) { readResult ->
            readResult.onSuccess { confirmed ->
                val reconnectContinuation =
                    (state as? ThreadState.ReadOnly)?.lease == ThreadLease.UNKNOWN && confirmed.thread.status == "active"
                val observedLease = if (reconnectContinuation) ThreadLease.UNKNOWN
                else leaseManager.fromRead(confirmed.thread.status, hostIdentity())
                if (observedLease == ThreadLease.OTHER_CLIENT) {
                    turn = confirmed.turn
                    update(ThreadState.ReadOnly(confirmed, observedLease))
                    fail(IllegalStateException("Write rejected: another client may be using this thread"))
                    return@onSuccess
                }
                update(ThreadState.Resuming(confirmed))
                repository.resumeThread(confirmed.thread.id) { resumeResult ->
                    resumeResult.onSuccess { active ->
                        leaseManager.claimLocal(connectionGeneration(), hostIdentity())
                        turn = active.turn
                        update(ThreadState.Active(active, ThreadLease.LOCAL_PHONE))
                        block(active)
                    }.onFailure { error ->
                        leaseManager.onDisconnected(hasThread = true)
                        turn = confirmed.turn
                        update(ThreadState.ReadOnly(confirmed, ThreadLease.UNKNOWN))
                        fail(error)
                    }
                }
            }.onFailure(::fail)
        }
    }

    private fun appendAgentDelta(itemId: String, delta: String) {
        if (delta.isEmpty()) return
        val current = snapshot() ?: return
        val items = current.items.toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index >= 0 && items[index] is ConversationItem.Message) {
            val old = items[index] as ConversationItem.Message
            items[index] = old.copy(markdown = old.markdown + delta)
        } else {
            items += ConversationItem.Message(itemId, MessageRole.ASSISTANT, delta)
        }
        replaceSnapshot(current.copy(items = items))
    }

    private fun appendCommandDelta(itemId: String, delta: String) {
        if (itemId.isBlank() || delta.isEmpty()) return
        val current = snapshot() ?: return
        val items = current.items.toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index >= 0 && items[index] is ConversationItem.CommandExecution) {
            val old = items[index] as ConversationItem.CommandExecution
            items[index] = old.copy(output = old.output + delta)
            replaceSnapshot(current.copy(items = items))
        }
    }

    private fun upsertItem(item: ConversationItem) {
        val current = snapshot() ?: return
        val items = current.items.toMutableList()
        val directIndex = items.indexOfFirst { it.id == item.id }
        val optimisticIndex = if (item is ConversationItem.Message && item.role == MessageRole.USER) {
            items.indexOfLast {
                it is ConversationItem.Message && it.id.startsWith("local-") &&
                    it.role == MessageRole.USER && it.markdown == item.markdown
            }
        } else {
            -1
        }
        when {
            directIndex >= 0 -> items[directIndex] = item
            optimisticIndex >= 0 -> items[optimisticIndex] = item
            else -> items += item
        }
        replaceSnapshot(current.copy(items = items))
    }

    private fun setTurn(value: TurnState) {
        turn = value
        val current = snapshot()
        if (current != null) replaceSnapshot(current.copy(turn = value)) else listener?.onThreadChanged(state, value)
    }

    private fun replaceSnapshot(value: ThreadSnapshot) {
        state = when (val current = state) {
            ThreadState.NoThread -> ThreadState.ReadOnly(value, leaseManager.current())
            is ThreadState.ReadOnly -> current.copy(snapshot = value, lease = leaseManager.current())
            is ThreadState.Resuming -> current.copy(snapshot = value)
            is ThreadState.Active -> current.copy(snapshot = value, lease = leaseManager.current())
        }
        listener?.onThreadChanged(state, turn)
    }

    private fun snapshot(): ThreadSnapshot? = when (val current = state) {
        ThreadState.NoThread -> null
        is ThreadState.ReadOnly -> current.snapshot
        is ThreadState.Resuming -> current.snapshot
        is ThreadState.Active -> current.snapshot
    }

    private fun update(value: ThreadState) {
        state = value
        listener?.onThreadChanged(value, turn)
    }

    private fun fail(error: Throwable) {
        listener?.onFailure(error.message ?: "Unknown thread error")
    }
}
