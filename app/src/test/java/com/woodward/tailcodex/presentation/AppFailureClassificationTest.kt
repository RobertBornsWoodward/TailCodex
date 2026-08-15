package com.woodward.tailcodex.presentation

import com.woodward.tailcodex.hostcontrol.protocol.HostAgentException
import com.woodward.tailcodex.domain.CodexSessionFailure
import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.SessionState
import com.woodward.tailcodex.domain.TailcodexThread
import com.woodward.tailcodex.domain.ThreadLease
import com.woodward.tailcodex.domain.ThreadSnapshot
import com.woodward.tailcodex.domain.ThreadState
import com.woodward.tailcodex.domain.TurnState
import com.woodward.tailcodex.hostcontrol.protocol.CodexOwnership
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceSnapshot
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceState
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentConnectionState
import com.woodward.tailcodex.hostcontrol.protocol.HostControlState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class AppFailureClassificationTest {
    @Test
    fun hostLifecycleFailuresRemainDistinct() {
        assertEquals(
            AppFailureKind.HOST_UNAVAILABLE,
            classifyAppFailure(IOException("offline"), FailureStage.HOST),
        )
        assertEquals(
            AppFailureKind.HOST_UNAVAILABLE,
            classifyAppFailure(HostAgentException("HTTP_503", "gateway unavailable", 503), FailureStage.HOST),
        )
        assertEquals(
            AppFailureKind.CODEX_PORT_CONFLICT,
            classifyAppFailure(HostAgentException("CODEX_PORT_CONFLICT", "occupied"), FailureStage.HOST),
        )
        assertEquals(
            AppFailureKind.CODEX_START_FAILED,
            classifyAppFailure(HostAgentException("CODEX_READY_TIMEOUT", "not ready"), FailureStage.HOST),
        )
        assertEquals(
            AppFailureKind.HOST_AUTHENTICATION_FAILED,
            classifyAppFailure(HostAgentException("UNAUTHORIZED", "revoked", 401), FailureStage.HOST),
        )
    }

    @Test
    fun codexTransportAndRpcTimeoutRemainDistinct() {
        assertEquals(
            AppFailureKind.TRANSPORT_LOST,
            classifyAppFailure(CodexSessionFailure.TransportLost("network changed"), FailureStage.CODEX_ENTRY),
        )
        assertEquals(
            AppFailureKind.RPC_TIMEOUT,
            classifyAppFailure(CodexSessionFailure.RpcTimeout("thread/start"), FailureStage.CODEX_ENTRY),
        )
    }

    @Test
    fun hostReadyDoesNotImplyCodexReadyWithoutLocalReadyEvidence() {
        val codex = readySession()
        val hostStopped = HostControlState(
            connection = HostAgentConnectionState.Ready("0.1.0"),
            service = service(CodexServiceState.STOPPED, ready = false),
        )
        val hostReady = hostStopped.copy(service = service(CodexServiceState.LOCAL_READY, ready = true))

        assertEquals(CodexClientPhase.CODEX_RPC_READY, composeAppState(hostStopped, codex, null).codexClient)
        assertEquals(CodexClientPhase.CODEX_READY, composeAppState(hostReady, codex, null).codexClient)
    }

    @Test
    fun directWssFallbackCanBeReadyWhileHostPlaneIsOffline() {
        val hostOffline = HostControlState(connection = HostAgentConnectionState.Disconnected)
        val composed = composeAppState(hostOffline, readySession(), AppFailure(AppFailureKind.HOST_UNAVAILABLE, "offline"))

        assertEquals(HostPlanePhase.HOST_OFFLINE, composed.host)
        assertEquals(CodexClientPhase.CODEX_READY, composed.codexClient)
        assertEquals(AppFailureKind.HOST_UNAVAILABLE, composed.failure?.kind)
    }

    @Test
    fun onlyExplicitMissingThreadErrorsMayFallBackToThreadStart() {
        assertEquals(
            true,
            CodexSessionFailure.Protocol(-32602, "thread not found").isMissingCodexThread(),
        )
        assertEquals(
            false,
            CodexSessionFailure.Protocol(-32001, "server overloaded").isMissingCodexThread(),
        )
        assertEquals(
            false,
            CodexSessionFailure.RpcTimeout("thread/read").isMissingCodexThread(),
        )
    }

    private fun readySession(): SessionState {
        val thread = TailcodexThread("thread-1", "Thread", "", "/tmp", 0, "idle")
        return SessionState(
            connection = ConnectionState.Ready,
            thread = ThreadState.ReadOnly(
                ThreadSnapshot(thread, emptyList(), TurnState.Idle),
                ThreadLease.NONE,
            ),
        )
    }

    private fun service(state: CodexServiceState, ready: Boolean) = CodexServiceSnapshot(
        ownership = CodexOwnership.MANAGED_SYSTEMD,
        state = state,
        portOpen = ready,
        ready = ready,
        detail = null,
    )
}
