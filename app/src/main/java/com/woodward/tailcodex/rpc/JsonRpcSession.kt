package com.woodward.tailcodex.rpc

import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.transport.WebSocketTransport
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

data class RetryPolicy(
    val maximumRetries: Int = 4,
    val maximumTotalBudgetMs: Long = 8_000,
    val baseDelayMs: Long = 250,
    val jitterMinimum: Double = 0.7,
    val jitterMaximum: Double = 1.3,
)

data class RpcRequestOptions(
    val timeoutMs: Long = 20_000,
    val retryPolicy: RetryPolicy = RetryPolicy(),
)

sealed class RpcFailure(message: String) : Exception(message) {
    class Timeout(val method: String) : RpcFailure("RPC timed out: $method")
    class Disconnected(reason: String) : RpcFailure("RPC disconnected: $reason")
    class Cancelled(val method: String) : RpcFailure("RPC locally cancelled: $method")
    class SendFailed(val method: String) : RpcFailure("WebSocket send failed: $method")
    class Protocol(val code: Int, message: String) : RpcFailure("$message ($code)")
}

class RpcCall internal constructor(private val cancelAction: () -> Unit) {
    fun cancel() = cancelAction()
}

class JsonRpcSession(
    private val transport: WebSocketTransport,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tailcodex-rpc").apply { isDaemon = true }
    },
    private val randomDouble: () -> Double = { Random.nextDouble() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : WebSocketTransport.Listener {
    interface Listener {
        fun onTransportConnected()
        fun onTransportDisconnected(reason: String)
        fun onNotification(method: String, params: JSONObject)
        fun onServerRequest(requestId: RpcId, method: String, params: JSONObject)
        fun onProtocolIssue(message: String)
        fun onRetryScheduled(method: String, attempt: Int, delayMs: Long) = Unit
    }

    private data class Operation(
        val operationId: Long,
        val method: String,
        val params: JSONObject,
        val options: RpcRequestOptions,
        val startedAtMs: Long,
        val callback: (Result<JSONObject>) -> Unit,
        val completed: AtomicBoolean = AtomicBoolean(false),
        var retryCount: Int = 0,
        var currentId: String? = null,
        var timeoutFuture: ScheduledFuture<*>? = null,
        var retryFuture: ScheduledFuture<*>? = null,
    )

    private val nextId = AtomicLong(1)
    private val nextOperationId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, Operation>()
    private val active = ConcurrentHashMap<Long, Operation>()
    private val respondedServerRequests = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var connected = false

    fun connect(endpoint: String, bearerToken: String, listener: Listener) {
        if (connected || active.isNotEmpty()) {
            cancelPending(RpcFailure.Disconnected("connection replaced"))
        }
        this.listener = listener
        connected = false
        respondedServerRequests.clear()
        transport.connect(endpoint, bearerToken, this)
    }

    fun disconnect(reason: String = "Client disconnect") {
        connected = false
        cancelPending(RpcFailure.Disconnected(reason))
        transport.disconnect(reason)
    }

    fun request(
        method: String,
        params: JSONObject = JSONObject(),
        options: RpcRequestOptions = RpcRequestOptions(),
        callback: (Result<JSONObject>) -> Unit,
    ): RpcCall {
        val operation = Operation(
            nextOperationId.getAndIncrement(),
            method,
            JSONObject(params.toString()),
            options,
            nowMs(),
            callback,
        )
        active[operation.operationId] = operation
        operation.timeoutFuture = scheduler.schedule(
            { complete(operation, Result.failure(RpcFailure.Timeout(method))) },
            options.timeoutMs,
            TimeUnit.MILLISECONDS,
        )
        sendAttempt(operation)
        return RpcCall { complete(operation, Result.failure(RpcFailure.Cancelled(method))) }
    }

    fun notify(method: String, params: JSONObject = JSONObject()): Boolean = sendEnvelope(
        JSONObject().put("method", method).put("params", params),
    )

    fun respond(requestId: RpcId, result: JSONObject): Boolean = respondOnce(
        requestId,
        JSONObject().put("id", requestId.raw).put("result", result),
    )

    fun respondError(requestId: RpcId, code: Int, message: String): Boolean = respondOnce(
        requestId,
        JSONObject()
            .put("id", requestId.raw)
            .put("error", JSONObject().put("code", code).put("message", message)),
    )

    fun pendingCount(): Int = active.size

    override fun onOpen() {
        connected = true
        listener?.onTransportConnected()
    }

    override fun onText(text: String) {
        val message = runCatching { JSONObject(text) }.getOrElse {
            listener?.onProtocolIssue("Invalid JSON from app-server")
            return
        }
        if (message.has("id") && (message.has("result") || message.has("error"))) {
            handleResponse(message)
            return
        }
        val method = message.optString("method")
        if (message.has("id") && method.isNotBlank()) {
            listener?.onServerRequest(
                RpcId(message.get("id")),
                method,
                message.optJSONObject("params") ?: JSONObject(),
            )
            return
        }
        if (method.isNotBlank()) {
            listener?.onNotification(method, message.optJSONObject("params") ?: JSONObject())
        } else {
            listener?.onProtocolIssue("Unrecognized JSON-RPC message")
        }
    }

    override fun onClosed(reason: String) {
        if (!connected) return
        connected = false
        cancelPending(RpcFailure.Disconnected(reason))
        listener?.onTransportDisconnected(reason)
    }

    private fun sendAttempt(operation: Operation) {
        if (operation.completed.get()) return
        if (!connected) {
            complete(operation, Result.failure(RpcFailure.Disconnected("transport is not connected")))
            return
        }
        val id = nextId.getAndIncrement().toString()
        synchronized(operation) {
            if (operation.completed.get()) return
            operation.currentId = id
            pending[id] = operation
        }
        val envelope = JSONObject()
            .put("id", id.toLong())
            .put("method", operation.method)
            .put("params", operation.params)
        if (!sendEnvelope(envelope)) {
            pending.remove(id, operation)
            complete(operation, Result.failure(RpcFailure.SendFailed(operation.method)))
        }
    }

    private fun handleResponse(message: JSONObject) {
        val id = message.get("id").toString()
        val operation = pending.remove(id)
        if (operation == null) {
            listener?.onProtocolIssue("Unknown or duplicate RPC response id=$id")
            return
        }
        synchronized(operation) {
            if (operation.completed.get() || operation.currentId != id) return
            operation.currentId = null
        }
        val error = message.optJSONObject("error")
        if (error?.optInt("code") == SERVER_OVERLOADED) {
            scheduleRetry(operation)
            return
        }
        if (error != null) {
            complete(
                operation,
                Result.failure(RpcFailure.Protocol(error.optInt("code"), error.optString("message"))),
            )
        } else {
            complete(operation, Result.success(message.optJSONObject("result") ?: JSONObject()))
        }
    }

    private fun scheduleRetry(operation: Operation) {
        if (operation.completed.get()) return
        val policy = operation.options.retryPolicy
        val nextRetry = operation.retryCount + 1
        val rawDelay = policy.baseDelayMs * (1L shl min(operation.retryCount, 20))
        val jitter = policy.jitterMinimum +
            (policy.jitterMaximum - policy.jitterMinimum) * randomDouble().coerceIn(0.0, 1.0)
        val delayMs = (rawDelay * jitter).toLong().coerceAtLeast(1)
        val elapsed = nowMs() - operation.startedAtMs
        if (
            nextRetry > policy.maximumRetries ||
            elapsed + delayMs > policy.maximumTotalBudgetMs ||
            elapsed + delayMs > operation.options.timeoutMs
        ) {
            complete(
                operation,
                Result.failure(RpcFailure.Protocol(SERVER_OVERLOADED, "Server overloaded; retry budget exhausted")),
            )
            return
        }
        operation.retryCount = nextRetry
        listener?.onRetryScheduled(operation.method, nextRetry, delayMs)
        val retryFuture = scheduler.schedule(
            { if (connected) sendAttempt(operation) else complete(operation, Result.failure(RpcFailure.Disconnected("retry cancelled"))) },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        synchronized(operation) {
            if (operation.completed.get()) retryFuture.cancel(false) else operation.retryFuture = retryFuture
        }
    }

    private fun complete(operation: Operation, result: Result<JSONObject>) {
        if (!operation.completed.compareAndSet(false, true)) return
        operation.currentId?.let { pending.remove(it, operation) }
        active.remove(operation.operationId, operation)
        operation.currentId = null
        operation.timeoutFuture?.cancel(false)
        operation.retryFuture?.cancel(false)
        runCatching { operation.callback(result) }.onFailure {
            listener?.onProtocolIssue("RPC callback failed for ${operation.method}: ${it.message}")
        }
    }

    private fun cancelPending(failure: Throwable) {
        active.values.toList().forEach {
            complete(it, Result.failure(failure))
        }
        pending.clear()
    }

    private fun respondOnce(requestId: RpcId, envelope: JSONObject): Boolean {
        val key = requestId.toString()
        if (!respondedServerRequests.add(key)) {
            listener?.onProtocolIssue("Duplicate server-request response suppressed id=$key")
            return false
        }
        if (sendEnvelope(envelope)) return true
        respondedServerRequests.remove(key)
        return false
    }

    private fun sendEnvelope(envelope: JSONObject): Boolean = connected && transport.send(envelope.toString())

    private companion object {
        const val SERVER_OVERLOADED = -32001
    }
}
