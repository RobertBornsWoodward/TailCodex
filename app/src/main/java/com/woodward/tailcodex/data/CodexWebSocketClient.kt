package com.woodward.tailcodex.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CodexWebSocketClient(
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onNotification(method: String, params: JSONObject)
        fun onApprovalRequested(request: ApprovalRequest)
        fun onProtocolError(message: String)
    }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val nextId = AtomicLong(1)
    private val connectionGeneration = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, (Result<JSONObject>) -> Unit>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var initialized = false

    fun connect(config: ConnectionConfig) {
        close()
        val generation = connectionGeneration.incrementAndGet()
        initialized = false
        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.token}")
            .build()
        socket = httpClient.newWebSocket(request, SocketListener(generation))
    }

    fun close() {
        connectionGeneration.incrementAndGet()
        initialized = false
        // Requests owned by a superseded socket must not surface as errors on the replacement
        // connection. Unexpected current-socket failures are reported by onFailure/onClosed.
        pending.clear()
        socket?.close(1000, "Client disconnect")
        socket = null
    }

    fun listThreads(search: String, callback: (Result<List<ThreadSummary>>) -> Unit) {
        send(CodexProtocol.threadList(nextId(), search), callback = { result ->
            callback(result.map(CodexProtocol::parseThreads))
        })
    }

    fun resumeThread(
        threadId: String,
        callback: (Result<Pair<ThreadSummary, List<ChatEntry>>>) -> Unit,
    ) {
        send(CodexProtocol.threadResume(nextId(), threadId), callback = { result ->
            callback(result.map(CodexProtocol::parseThreadPayload))
        })
    }

    fun startThread(
        cwd: String,
        callback: (Result<Pair<ThreadSummary, List<ChatEntry>>>) -> Unit,
    ) {
        send(CodexProtocol.threadStart(nextId(), cwd), callback = { result ->
            callback(result.map(CodexProtocol::parseThreadPayload))
        })
    }

    fun startTurn(threadId: String, text: String, callback: (Result<JSONObject>) -> Unit) {
        send(CodexProtocol.turnStart(nextId(), threadId, text), callback)
    }

    fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        text: String,
        callback: (Result<JSONObject>) -> Unit,
    ) {
        send(CodexProtocol.turnSteer(nextId(), threadId, expectedTurnId, text), callback)
    }

    fun interruptTurn(
        threadId: String,
        turnId: String,
        callback: (Result<JSONObject>) -> Unit = {},
    ) {
        send(CodexProtocol.turnInterrupt(nextId(), threadId, turnId), callback)
    }

    fun resolveApproval(request: ApprovalRequest, decision: String) {
        val response = JSONObject().put("id", request.rpcId)
        if (request.kind == ApprovalKind.PERMISSIONS) {
            if (decision == "accept" && request.rawPermissions != null) {
                response.put(
                    "result",
                    JSONObject()
                        .put("permissions", JSONObject(request.rawPermissions))
                        .put("scope", "turn"),
                )
            } else {
                response.put(
                    "error",
                    JSONObject().put("code", -32000).put("message", "User declined permission request"),
                )
            }
        } else {
            response.put("result", JSONObject().put("decision", decision))
        }
        sendRaw(response)
    }

    private fun nextId(): Long = nextId.getAndIncrement()

    private fun send(
        message: JSONObject,
        callback: (Result<JSONObject>) -> Unit,
        allowBeforeInitialization: Boolean = false,
    ) {
        if (!allowBeforeInitialization && !initialized) {
            callback(Result.failure(IllegalStateException("App server is not initialized")))
            return
        }
        val id = message.get("id").toString()
        pending[id] = callback
        if (!sendRaw(message)) {
            pending.remove(id)
            callback(Result.failure(IllegalStateException("WebSocket send failed")))
        }
    }

    private fun sendRaw(message: JSONObject): Boolean = socket?.send(message.toString()) == true

    private inner class SocketListener(
        private val generation: Long,
    ) : WebSocketListener() {
        private fun isCurrentConnection(): Boolean = generation == connectionGeneration.get()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrentConnection()) {
                webSocket.close(1000, "Superseded connection")
                return
            }
            val id = nextId()
            send(CodexProtocol.initialize(id), { result ->
                result.onSuccess {
                    initialized = true
                    sendRaw(CodexProtocol.notification("initialized"))
                    listener.onConnected()
                }.onFailure { listener.onProtocolError(it.message ?: "Initialization failed") }
            }, allowBeforeInitialization = true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentConnection()) return
            val message = runCatching { JSONObject(text) }.getOrElse {
                listener.onProtocolError("Invalid JSON from app-server")
                return
            }
            if (message.has("id") && (message.has("result") || message.has("error"))) {
                val callback = pending.remove(message.get("id").toString())
                if (callback != null) {
                    val error = message.optJSONObject("error")
                    if (error != null) {
                        callback(Result.failure(ProtocolException(error.optInt("code"), error.optString("message"))))
                    } else {
                        callback(Result.success(message.optJSONObject("result") ?: JSONObject()))
                    }
                    return
                }
            }

            CodexProtocol.approvalFrom(message)?.let {
                listener.onApprovalRequested(it)
                return
            }

            val method = message.optString("method")
            if (method.isNotBlank()) {
                listener.onNotification(method, message.optJSONObject("params") ?: JSONObject())
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrentConnection()) return
            initialized = false
            listener.onDisconnected(reason.ifBlank { "Connection closed" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrentConnection()) return
            initialized = false
            val reason = buildString {
                append(t.message ?: "WebSocket failure")
                response?.let { append(" (HTTP ${it.code})") }
            }
            listener.onDisconnected(reason)
        }
    }
}

class ProtocolException(val code: Int, message: String) : Exception("$message ($code)")
