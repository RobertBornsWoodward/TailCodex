package com.woodward.tailcodex.infrastructure

import com.woodward.tailcodex.domain.ConnectionConfig
import com.woodward.tailcodex.protocol.CodexApi
import com.woodward.tailcodex.repository.DefaultCodexRepository
import com.woodward.tailcodex.rpc.JsonRpcSession
import com.woodward.tailcodex.session.ConnectionManager
import com.woodward.tailcodex.session.LeaseManager
import com.woodward.tailcodex.session.ServerRequestManager
import com.woodward.tailcodex.session.SessionCoordinator
import com.woodward.tailcodex.session.ThreadSession
import com.woodward.tailcodex.transport.OkHttpWebSocketTransport

object TailCodexRuntimeFactory {
    fun create(
        initialConfig: ConnectionConfig,
        hostIdentity: () -> String,
        isPinned: (String) -> Boolean,
    ): SessionCoordinator {
        val rpc = JsonRpcSession(OkHttpWebSocketTransport())
        val connection = ConnectionManager(rpc)
        val repository = DefaultCodexRepository(CodexApi(rpc, connection::isProtocolReady))
        val thread = ThreadSession(
            repository = repository,
            leaseManager = LeaseManager(),
            connectionReady = connection::isReady,
            connectionGeneration = connection::connectionGeneration,
            hostIdentity = hostIdentity,
        )
        val requests = ServerRequestManager(
            repository,
            connection::connectionGeneration,
            hostIdentity,
            connection::isReady,
            thread::canRespond,
        )
        return SessionCoordinator(
            initialConfig,
            connection,
            repository,
            thread,
            requests,
            isPinned,
        )
    }
}
