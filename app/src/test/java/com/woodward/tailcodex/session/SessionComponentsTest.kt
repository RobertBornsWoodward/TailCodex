package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ApprovalDecision
import com.woodward.tailcodex.domain.ConversationItem
import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.domain.TailcodexThread
import com.woodward.tailcodex.domain.ThreadLease
import com.woodward.tailcodex.domain.ThreadSnapshot
import com.woodward.tailcodex.domain.ThreadState
import com.woodward.tailcodex.domain.TurnState
import com.woodward.tailcodex.domain.ReviewTarget
import com.woodward.tailcodex.domain.ImageAttachment
import com.woodward.tailcodex.domain.UserInputQuestion
import com.woodward.tailcodex.protocol.ThreadPage
import com.woodward.tailcodex.repository.CodexRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionComponentsTest {
    @Test
    fun historicalReadStaysReadOnlyAndFirstWriteRevalidatesThenResumes() {
        val repository = FakeRepository(snapshot("idle"))
        val lease = LeaseManager()
        val session = ThreadSession(repository, lease, { true }, { 7 })
        var current: ThreadState = ThreadState.NoThread
        session.setListener(threadListener { current = it })

        session.openReadOnly(repository.value.thread)
        assertTrue(current is ThreadState.ReadOnly)
        assertEquals(0, repository.resumeCalls)

        session.send("hello")
        assertEquals(2, repository.readCalls)
        assertEquals(1, repository.resumeCalls)
        assertEquals(1, repository.startTurnCalls)
        assertTrue(current is ThreadState.Active)
        assertEquals(ThreadLease.LOCAL_PHONE, (current as ThreadState.Active).lease)
        assertFalse(lease.isValidLocal(7, "another-host"))
    }

    @Test
    fun activeServerStatusNeverSilentlyBecomesWriter() {
        val repository = FakeRepository(snapshot("active"))
        val session = ThreadSession(repository, LeaseManager(), { true }, { 1 })
        val errors = mutableListOf<String>()
        session.setListener(object : ThreadSession.Listener {
            override fun onThreadChanged(state: ThreadState, turn: TurnState) = Unit
            override fun onFailure(message: String) { errors += message }
        })
        session.openReadOnly(repository.value.thread)
        session.send("must not write")

        assertEquals(0, repository.resumeCalls)
        assertEquals(0, repository.startTurnCalls)
        assertTrue(errors.single().contains("another client"))
    }

    @Test
    fun turnInterruptIsAnExplicitRepositoryOperation() {
        val running = snapshot("idle").copy(turn = TurnState.Running("turn-1"))
        val repository = FakeRepository(running)
        val lease = LeaseManager().apply { claimLocal(2) }
        val session = ThreadSession(repository, lease, { true }, { 2 })
        session.setListener(threadListener {})
        session.startThread("/tmp")
        session.handleNotification("turn/started", JSONObject("""{"threadId":"t","turn":{"id":"turn-1"}}"""))
        session.interrupt()
        assertEquals(1, repository.interruptCalls)
    }

    @Test
    fun interruptWithoutActiveLocalLeaseIsRejected() {
        val repository = FakeRepository(snapshot("active"))
        val session = ThreadSession(repository, LeaseManager(), { true }, { 2 })
        val errors = mutableListOf<String>()
        session.setListener(object : ThreadSession.Listener {
            override fun onThreadChanged(state: ThreadState, turn: TurnState) = Unit
            override fun onFailure(message: String) { errors += message }
        })
        session.openReadOnly(repository.value.thread)
        session.interrupt()

        assertEquals(0, repository.interruptCalls)
        assertTrue(errors.single().contains("another client"))
    }

    @Test
    fun reconnectOfLocallyOwnedRunningThreadCanRevalidateAndResume() {
        val repository = FakeRepository(snapshot("idle"))
        val lease = LeaseManager()
        var generation = 4L
        val session = ThreadSession(repository, lease, { true }, { generation })
        var current: ThreadState = ThreadState.NoThread
        session.setListener(threadListener { current = it })
        session.startThread("/tmp")
        session.handleNotification("turn/started", JSONObject("""{"threadId":"t","turn":{"id":"turn-1"}}"""))
        session.onDisconnected()
        generation = 5L
        repository.value = snapshot("active").copy(turn = TurnState.Running("turn-1"))
        session.reconcile { assertTrue(it.isSuccess) }

        assertEquals(ThreadLease.UNKNOWN, (current as ThreadState.ReadOnly).lease)
        session.send("continue")

        assertEquals(1, repository.resumeCalls)
        assertEquals(1, repository.steerCalls)
        assertEquals(ThreadLease.LOCAL_PHONE, (current as ThreadState.Active).lease)
    }

    @Test
    fun genericThreadMutationRevalidatesAndResumesBeforeRunning() {
        val repository = FakeRepository(snapshot("idle"))
        val session = ThreadSession(repository, LeaseManager(), { true }, { 5 })
        session.setListener(threadListener {})
        session.openReadOnly(repository.value.thread)
        var invoked = 0

        session.withWritableThread { invoked++ }

        assertEquals(2, repository.readCalls)
        assertEquals(1, repository.resumeCalls)
        assertEquals(1, invoked)
    }

    @Test
    fun reconciliationReplacesTheSnapshotWithServerTruth() {
        val repository = FakeRepository(snapshot("idle"))
        val session = ThreadSession(repository, LeaseManager(), { true }, { 4 })
        var current: ThreadState = ThreadState.NoThread
        session.setListener(threadListener { current = it })
        session.openReadOnly(repository.value.thread)
        repository.value = repository.value.copy(thread = repository.value.thread.copy(title = "server title"))
        var result: Result<Unit>? = null
        session.reconcile { result = it }

        assertTrue(requireNotNull(result).isSuccess)
        assertEquals("server title", (current as ThreadState.ReadOnly).snapshot.thread.title)
        assertEquals(2, repository.readCalls)
    }

    @Test
    fun unknownAndDynamicRequestsAreExplicitlyRejectedWhileApprovalIsQueued() {
        val repository = FakeRepository(snapshot("idle"))
        val manager = ServerRequestManager(repository, connectionGeneration = { 3 })
        val unsupported = mutableListOf<ServerRequest>()
        manager.setListener(object : ServerRequestManager.Listener {
            override fun onRequestsChanged(requests: List<ServerRequest>) = Unit
            override fun onUnsupportedRequest(request: ServerRequest) { unsupported += request }
            override fun onFailure(message: String) = Unit
        })
        manager.handle(ServerRequest.Unknown(RpcId(1), "future", "{}"))
        manager.handle(ServerRequest.DynamicToolCall(RpcId(2), null, null, "x", "{}"))
        manager.handle(ServerRequest.FileApproval(RpcId(3), "t", "r", "i", null, null))

        assertEquals(2, repository.rejectCalls)
        assertEquals(2, unsupported.size)
        assertEquals(1, manager.current().size)
    }

    @Test
    fun approvalUserInputAndMcpAreAnsweredAndRemoved() {
        val repository = FakeRepository(snapshot("idle"))
        val manager = ServerRequestManager(repository, connectionGeneration = { 8 })
        manager.setListener(object : ServerRequestManager.Listener {
            override fun onRequestsChanged(requests: List<ServerRequest>) = Unit
            override fun onUnsupportedRequest(request: ServerRequest) = Unit
            override fun onFailure(message: String) = error(message)
        })
        val approval = ServerRequest.FileApproval(RpcId(1), "t", "r", "i", null, null)
        manager.handle(approval)
        manager.resolveApproval(approval, ApprovalDecision.ACCEPT)
        val input = ServerRequest.UserInput(
            RpcId(2), "t", "r", "i",
            listOf(UserInputQuestion("q", "H", "Q", emptyList(), false, false)), true,
        )
        manager.handle(input)
        manager.answerUserInput(input, mapOf("q" to listOf("answer")))
        val mcp = ServerRequest.McpElicitation(RpcId(3), "t", "r", "server", "url", "open", null, "https://example.test")
        manager.handle(mcp)
        manager.answerMcp(mcp, "decline", null)

        assertEquals(1, repository.approvalResponses)
        assertEquals(1, repository.inputResponses)
        assertEquals(1, repository.mcpResponses)
        assertTrue(manager.current().isEmpty())
    }

    @Test
    fun serverResponseRequiresReadyWritableCurrentContext() {
        val repository = FakeRepository(snapshot("idle"))
        var ready = false
        var writable = true
        val errors = mutableListOf<String>()
        val manager = ServerRequestManager(
            repository,
            connectionGeneration = { 8 },
            connectionReady = { ready },
            writableContext = { writable },
        )
        manager.setListener(object : ServerRequestManager.Listener {
            override fun onRequestsChanged(requests: List<ServerRequest>) = Unit
            override fun onUnsupportedRequest(request: ServerRequest) = Unit
            override fun onFailure(message: String) { errors += message }
        })
        val approval = ServerRequest.FileApproval(RpcId(1), "t", "r", "i", null, null)
        manager.handle(approval)
        manager.resolveApproval(approval, ApprovalDecision.ACCEPT)
        ready = true
        writable = false
        manager.resolveApproval(approval, ApprovalDecision.ACCEPT)

        assertEquals(0, repository.approvalResponses)
        assertEquals(2, errors.size)
        assertEquals(1, manager.current().size)
    }

    private fun threadListener(change: (ThreadState) -> Unit) = object : ThreadSession.Listener {
        override fun onThreadChanged(state: ThreadState, turn: TurnState) = change(state)
        override fun onFailure(message: String) = Unit
    }

    private fun snapshot(status: String) = ThreadSnapshot(
        TailcodexThread("t", "title", "", "/tmp", 0, status),
        emptyList<ConversationItem>(),
        if (status == "active") TurnState.Running("server-turn") else TurnState.Idle,
    )

    private class FakeRepository(var value: ThreadSnapshot) : CodexRepository {
        var readCalls = 0
        var resumeCalls = 0
        var startTurnCalls = 0
        var interruptCalls = 0
        var steerCalls = 0
        var rejectCalls = 0
        var approvalResponses = 0
        var inputResponses = 0
        var mcpResponses = 0
        override fun listThreads(search: String, cursor: String?, callback: (Result<ThreadPage>) -> Unit) =
            callback(Result.success(ThreadPage(listOf(value.thread), null)))
        override fun readThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit) {
            readCalls++; callback(Result.success(value))
        }
        override fun resumeThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit) {
            resumeCalls++; value = value.copy(thread = value.thread.copy(status = "idle")); callback(Result.success(value))
        }
        override fun startThread(cwd: String, callback: (Result<ThreadSnapshot>) -> Unit) = callback(Result.success(value))
        override fun startTurn(threadId: String, text: String, images: List<ImageAttachment>, callback: (Result<String?>) -> Unit) {
            startTurnCalls++; callback(Result.success("turn-new"))
        }
        override fun steerTurn(threadId: String, turnId: String, text: String, images: List<ImageAttachment>, callback: (Result<String?>) -> Unit) =
            callback(Result.success(turnId)).also { steerCalls++ }
        override fun interruptTurn(threadId: String, turnId: String, callback: (Result<Unit>) -> Unit) {
            interruptCalls++; callback(Result.success(Unit))
        }
        override fun forkThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit) = callback(Result.success(value))
        override fun archiveThread(threadId: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
        override fun startReview(threadId: String, target: ReviewTarget, callback: (Result<String?>) -> Unit) =
            callback(Result.success("review"))
        override fun respondApproval(request: ServerRequest, decision: ApprovalDecision): Boolean { approvalResponses++; return true }
        override fun respondUserInput(request: ServerRequest.UserInput, answers: Map<String, List<String>>): Boolean { inputResponses++; return true }
        override fun respondMcp(request: ServerRequest.McpElicitation, action: String, content: JSONObject?): Boolean { mcpResponses++; return true }
        override fun rejectUnsupported(request: ServerRequest): Boolean { rejectCalls++; return true }
    }
}
