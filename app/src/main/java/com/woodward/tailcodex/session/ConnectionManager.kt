package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ConnectionConfig
import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.protocol.CodexWireProtocol
import com.woodward.tailcodex.rpc.JsonRpcSession
import com.woodward.tailcodex.rpc.RpcRequestOptions
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ConnectionManager(
    private val rpc: JsonRpcSession,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tailcodex-reconnect").apply { isDaemon = true }
    },
) : JsonRpcSession.Listener {
    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onReady(reconnected: Boolean)
        fun onDisconnected(reason: String)
        fun onNotification(method: String, params: JSONObject)
        fun onServerRequest(requestId: RpcId, method: String, params: JSONObject)
        fun onProtocolIssue(message: String)
    }

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var state: ConnectionState = ConnectionState.Disconnected()

    @Volatile
    private var autoReconnect = false

    private var config: ConnectionConfig? = null
    private var reconnectAttempt = 0
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var hasEverBeenReady = false
    private var generation = 0L

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun connect(config: ConnectionConfig) {
        this.config = config
        autoReconnect = true
        reconnectAttempt = 0
        reconnectFuture?.cancel(false)
        open(config, 0)
    }

    fun reconnectNow() {
        val current = config ?: return
        autoReconnect = true
        reconnectFuture?.cancel(false)
        open(current, reconnectAttempt)
    }

    fun disconnect() {
        autoReconnect = false
        reconnectFuture?.cancel(false)
        rpc.disconnect()
        setState(ConnectionState.Disconnected(staleSnapshot = false))
    }

    fun isReady(): Boolean = state is ConnectionState.Ready
    fun isProtocolReady(): Boolean = state is ConnectionState.Ready || state is ConnectionState.Reconciling
    fun connectionGeneration(): Long = generation

    fun markReconciling(threadId: String) {
        setState(ConnectionState.Reconciling(threadId, reconnectAttempt))
    }

    fun markReconciled() {
        setState(ConnectionState.Ready)
    }

    override fun onTransportConnected() {
        setState(ConnectionState.Initializing(reconnectAttempt))
        rpc.request(
            method = "initialize",
            params = CodexWireProtocol.initializeParams(),
            options = RpcRequestOptions(timeoutMs = 15_000),
        ) { result ->
            result.onSuccess {
                if (!rpc.notify("initialized")) {
                    failInitialization("Failed to send initialized notification")
                    return@onSuccess
                }
                generation += 1
                val reconnected = hasEverBeenReady
                hasEverBeenReady = true
                reconnectAttempt = 0
                setState(ConnectionState.Ready)
                listener?.onReady(reconnected)
            }.onFailure { failInitialization(it.message ?: "Initialization failed") }
        }
    }

    override fun onTransportDisconnected(reason: String) {
        state = ConnectionState.Disconnected(reason, staleSnapshot = true)
        listener?.onDisconnected(reason)
        listener?.onConnectionStateChanged(state)
        scheduleReconnect()
    }

    override fun onNotification(method: String, params: JSONObject) {
        if (!isProtocolReady()) {
            listener?.onProtocolIssue("Ignored notification before initialization: $method")
        } else {
            listener?.onNotification(method, params)
        }
    }

    override fun onServerRequest(requestId: RpcId, method: String, params: JSONObject) {
        if (!isProtocolReady()) {
            rpc.respondError(requestId, -32002, "TailCodex session is not initialized")
            listener?.onProtocolIssue("Rejected server request before initialization: $method")
        } else {
            listener?.onServerRequest(requestId, method, params)
        }
    }

    override fun onProtocolIssue(message: String) {
        listener?.onProtocolIssue(message)
    }

    override fun onRetryScheduled(method: String, attempt: Int, delayMs: Long) {
        listener?.onProtocolIssue("$method overloaded; retry $attempt scheduled in ${delayMs}ms")
    }

    private fun open(config: ConnectionConfig, attempt: Int) {
        setState(ConnectionState.Connecting(attempt))
        rpc.connect(config.endpoint, config.token, this)
    }

    private fun failInitialization(reason: String) {
        listener?.onProtocolIssue(reason)
        rpc.disconnect(reason)
        listener?.onDisconnected(reason)
        setState(ConnectionState.Disconnected(reason, staleSnapshot = true))
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || reconnectFuture?.isDone == false) return
        val current = config ?: return
        reconnectAttempt += 1
        val delayMs = min(30_000L, 1_000L shl min(reconnectAttempt - 1, 5))
        reconnectFuture = scheduler.schedule(
            {
                reconnectFuture = null
                if (autoReconnect) open(current, reconnectAttempt)
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun setState(value: ConnectionState) {
        state = value
        listener?.onConnectionStateChanged(value)
    }
}
