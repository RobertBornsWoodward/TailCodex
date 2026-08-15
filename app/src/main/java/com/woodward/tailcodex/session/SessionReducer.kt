package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ConnectionConfig
import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.domain.SessionState
import com.woodward.tailcodex.domain.ThreadListState
import com.woodward.tailcodex.domain.ThreadState
import com.woodward.tailcodex.domain.TurnState

sealed interface SessionEvent {
    data class Configured(val config: ConnectionConfig) : SessionEvent
    data class ConnectionChanged(val connection: ConnectionState) : SessionEvent
    data class SocketDisconnected(val reason: String) : SessionEvent
    data object ReconciliationCompleted : SessionEvent
    data class ThreadChanged(val thread: ThreadState, val turn: TurnState) : SessionEvent
    data class TurnChanged(val turn: TurnState) : SessionEvent
    data class ThreadListChanged(val value: ThreadListState) : SessionEvent
    data class RequestsChanged(val requests: List<ServerRequest>) : SessionEvent
    data class SearchChanged(val value: String) : SessionEvent
    data class Failure(val message: String) : SessionEvent
    data class Notice(val message: String?) : SessionEvent
    data class ThreadPinned(val threadId: String, val pinned: Boolean) : SessionEvent
    data object ClearThread : SessionEvent
}

object SessionReducer {
    fun reduce(state: SessionState, event: SessionEvent): SessionState = when (event) {
        is SessionEvent.Configured -> state.copy(config = event.config, error = null)
        is SessionEvent.ConnectionChanged -> state.copy(
            connection = event.connection,
            reconnectAttempt = when (event.connection) {
                is ConnectionState.Connecting -> event.connection.reconnectAttempt
                is ConnectionState.Initializing -> event.connection.reconnectAttempt
                is ConnectionState.Reconciling -> event.connection.reconnectAttempt
                else -> state.reconnectAttempt
            },
            stale = event.connection is ConnectionState.Disconnected && event.connection.staleSnapshot,
            error = if (event.connection is ConnectionState.Ready) null else state.error,
        )
        is SessionEvent.SocketDisconnected -> state.copy(
            connection = ConnectionState.Disconnected(event.reason, staleSnapshot = state.thread !is ThreadState.NoThread),
            stale = state.thread !is ThreadState.NoThread,
            error = event.reason,
        )
        SessionEvent.ReconciliationCompleted -> state.copy(
            connection = ConnectionState.Ready,
            stale = false,
            error = null,
        )
        is SessionEvent.ThreadChanged -> state.copy(thread = event.thread, turn = event.turn)
        is SessionEvent.TurnChanged -> state.copy(turn = event.turn)
        is SessionEvent.ThreadListChanged -> state.copy(threadList = event.value)
        is SessionEvent.RequestsChanged -> state.copy(serverRequests = event.requests)
        is SessionEvent.SearchChanged -> state.copy(search = event.value)
        is SessionEvent.Failure -> state.copy(error = event.message)
        is SessionEvent.Notice -> state.copy(notice = event.message)
        is SessionEvent.ThreadPinned -> state.copy(
            threadList = when (val list = state.threadList) {
                is ThreadListState.Loaded -> list.copy(
                    threads = list.threads.map { if (it.id == event.threadId) it.copy(pinned = event.pinned) else it },
                )
                else -> list
            },
            thread = state.thread.mapSnapshot { snapshot ->
                if (snapshot.thread.id == event.threadId) {
                    snapshot.copy(thread = snapshot.thread.copy(pinned = event.pinned))
                } else snapshot
            },
        )
        SessionEvent.ClearThread -> state.copy(
            thread = ThreadState.NoThread,
            turn = TurnState.Idle,
            serverRequests = emptyList(),
            stale = false,
        )
    }

    private fun ThreadState.mapSnapshot(transform: (com.woodward.tailcodex.domain.ThreadSnapshot) -> com.woodward.tailcodex.domain.ThreadSnapshot): ThreadState =
        when (this) {
            ThreadState.NoThread -> this
            is ThreadState.ReadOnly -> copy(snapshot = transform(snapshot))
            is ThreadState.Resuming -> copy(snapshot = transform(snapshot))
            is ThreadState.Active -> copy(snapshot = transform(snapshot))
        }
}
