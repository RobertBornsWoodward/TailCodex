package com.woodward.tailcodex.rpc

import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.transport.WebSocketTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JsonRpcSessionTest {
    @Test
    fun correlatesOnceAndReportsDuplicateAndUnknownResponses() {
        val transport = FakeTransport()
        val issues = mutableListOf<String>()
        val rpc = JsonRpcSession(transport)
        rpc.connect("wss://test", "token", listener(issues))
        val callbacks = AtomicInteger()
        rpc.request("thread/list") { callbacks.incrementAndGet() }
        val id = JSONObject(transport.sent.single()).get("id")
        val response = JSONObject().put("id", id).put("result", JSONObject()).toString()

        rpc.onText(response)
        rpc.onText(response)
        rpc.onText(JSONObject().put("id", 999).put("result", JSONObject()).toString())

        assertEquals(1, callbacks.get())
        assertEquals(0, rpc.pendingCount())
        assertEquals(2, issues.size)
    }

    @Test
    fun serverRequestIdReceivesAtMostOneSuccessfulResponse() {
        val transport = FakeTransport()
        val issues = mutableListOf<String>()
        val rpc = JsonRpcSession(transport)
        rpc.connect("wss://test", "token", listener(issues))
        val requestId = RpcId(77)

        assertTrue(rpc.respond(requestId, JSONObject().put("decision", "accept")))
        assertTrue(!rpc.respondError(requestId, -32601, "duplicate"))

        assertEquals(1, transport.sent.size)
        assertTrue(issues.single().contains("suppressed"))
    }

    @Test
    fun timeoutAndLocalCancellationBothCompleteWithoutTurnInterrupt() {
        val transport = FakeTransport()
        val rpc = JsonRpcSession(transport)
        rpc.connect("wss://test", "token", listener())
        val timeout = CountDownLatch(1)
        var timeoutError: Throwable? = null
        rpc.request("thread/read", options = RpcRequestOptions(timeoutMs = 30)) {
            timeoutError = it.exceptionOrNull(); timeout.countDown()
        }
        assertTrue(timeout.await(1, TimeUnit.SECONDS))
        assertTrue(timeoutError is RpcFailure.Timeout)

        var cancelError: Throwable? = null
        val call = rpc.request("thread/list") { cancelError = it.exceptionOrNull() }
        call.cancel()
        assertTrue(cancelError is RpcFailure.Cancelled)
        assertTrue(transport.sent.none { JSONObject(it).optString("method") == "turn/interrupt" })
    }

    @Test
    fun overloadedRequestRetriesWithinBudget() {
        val transport = FakeTransport()
        val rpc = JsonRpcSession(transport, randomDouble = { 0.0 })
        rpc.connect("wss://test", "token", listener())
        val done = CountDownLatch(1)
        rpc.request(
            "thread/list",
            options = RpcRequestOptions(
                timeoutMs = 1_000,
                retryPolicy = RetryPolicy(2, 500, 1, 1.0, 1.0),
            ),
        ) { done.countDown() }
        val firstId = JSONObject(transport.sent.last()).get("id")
        rpc.onText(JSONObject().put("id", firstId).put("error", JSONObject().put("code", -32001).put("message", "busy")).toString())
        while (transport.sent.size < 2) Thread.sleep(2)
        val secondId = JSONObject(transport.sent.last()).get("id")
        rpc.onText(JSONObject().put("id", secondId).put("result", JSONObject()).toString())

        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun disconnectImmediatelyCancelsAnOperationWaitingForRetry() {
        val transport = FakeTransport()
        val rpc = JsonRpcSession(transport, randomDouble = { 0.0 })
        rpc.connect("wss://test", "token", listener())
        var failure: Throwable? = null
        rpc.request(
            "thread/list",
            options = RpcRequestOptions(
                timeoutMs = 5_000,
                retryPolicy = RetryPolicy(3, 4_000, 1_000, 1.0, 1.0),
            ),
        ) { failure = it.exceptionOrNull() }
        val id = JSONObject(transport.sent.last()).get("id")
        rpc.onText(JSONObject().put("id", id).put("error", JSONObject().put("code", -32001).put("message", "busy")).toString())
        assertEquals(1, rpc.pendingCount())
        rpc.disconnect("lost")
        assertTrue(failure is RpcFailure.Disconnected)
        assertEquals(0, rpc.pendingCount())
    }

    @Test
    fun userCancellationStopsAnOperationWaitingForRetry() {
        val transport = FakeTransport()
        val rpc = JsonRpcSession(transport, randomDouble = { 0.0 })
        rpc.connect("wss://test", "token", listener())
        var failure: Throwable? = null
        val call = rpc.request(
            "thread/list",
            options = RpcRequestOptions(
                timeoutMs = 5_000,
                retryPolicy = RetryPolicy(3, 4_000, 1_000, 1.0, 1.0),
            ),
        ) { failure = it.exceptionOrNull() }
        val id = JSONObject(transport.sent.last()).get("id")
        rpc.onText(JSONObject().put("id", id).put("error", JSONObject().put("code", -32001).put("message", "busy")).toString())

        call.cancel()

        assertTrue(failure is RpcFailure.Cancelled)
        assertEquals(0, rpc.pendingCount())
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun overloadStopsWhenTotalRetryBudgetWouldBeExceeded() {
        val transport = FakeTransport()
        val rpc = JsonRpcSession(transport, randomDouble = { 0.0 })
        rpc.connect("wss://test", "token", listener())
        var failure: Throwable? = null
        rpc.request(
            "thread/list",
            options = RpcRequestOptions(
                timeoutMs = 5_000,
                retryPolicy = RetryPolicy(10, 100, 250, 1.0, 1.0),
            ),
        ) { failure = it.exceptionOrNull() }
        val id = JSONObject(transport.sent.last()).get("id")

        rpc.onText(JSONObject().put("id", id).put("error", JSONObject().put("code", -32001).put("message", "busy")).toString())

        assertTrue(failure is RpcFailure.Protocol)
        assertEquals(0, rpc.pendingCount())
        assertEquals(1, transport.sent.size)
    }

    private fun listener(issues: MutableList<String> = mutableListOf()) = object : JsonRpcSession.Listener {
        override fun onTransportConnected() = Unit
        override fun onTransportDisconnected(reason: String) = Unit
        override fun onNotification(method: String, params: JSONObject) = Unit
        override fun onServerRequest(requestId: RpcId, method: String, params: JSONObject) = Unit
        override fun onProtocolIssue(message: String) { issues += message }
    }

    private class FakeTransport : WebSocketTransport {
        val sent = mutableListOf<String>()
        private var listener: WebSocketTransport.Listener? = null
        override fun connect(endpoint: String, bearerToken: String, listener: WebSocketTransport.Listener) {
            this.listener = listener
            listener.onOpen()
        }
        override fun disconnect(reason: String) = Unit
        override fun send(text: String): Boolean { sent += text; return true }
    }
}
