package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.ConversationItem
import com.woodward.tailcodex.domain.SessionState
import com.woodward.tailcodex.domain.TailcodexThread
import com.woodward.tailcodex.domain.ThreadLease
import com.woodward.tailcodex.domain.ThreadSnapshot
import com.woodward.tailcodex.domain.ThreadState
import com.woodward.tailcodex.domain.TurnState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    @Test
    fun disconnectMarksSnapshotStaleUntilReconciliation() {
        val snapshot = ThreadSnapshot(
            TailcodexThread("t", "title", "", "/tmp", 0, "idle"),
            emptyList<ConversationItem>(),
            TurnState.Idle,
        )
        val active = SessionState(thread = ThreadState.ReadOnly(snapshot, ThreadLease.NONE))
        val disconnected = SessionReducer.reduce(active, SessionEvent.SocketDisconnected("lost"))
        assertTrue(disconnected.stale)
        assertTrue((disconnected.connection as ConnectionState.Disconnected).staleSnapshot)

        val reconciled = SessionReducer.reduce(disconnected, SessionEvent.ReconciliationCompleted)
        assertFalse(reconciled.stale)
        assertTrue(reconciled.connection is ConnectionState.Ready)
    }
}
