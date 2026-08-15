package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ConnectionConfig
import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.rpc.JsonRpcSession
import com.woodward.tailcodex.transport.WebSocketTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectionManagerTest {
    @Test
    fun initializesBeforeReadyAndReconnectsWithNewGeneration() {
        val transport = FakeTransport()
        val rpc = JsonRpcSession(transport)
        val manager = ConnectionManager(rpc)
        val ready = mutableListOf<Boolean>()
        val notifications = mutableListOf<String>()
        val issues = mutableListOf<String>()
        val secondConnection = CountDownLatch(1)
        transport.onConnect = { if (transport.connectCount == 2) secondConnection.countDown() }
        manager.setListener(object : ConnectionManager.Listener {
            override fun onConnectionStateChanged(state: ConnectionState) = Unit
            override fun onReady(reconnected: Boolean) { ready += reconnected }
            override fun onDisconnected(reason: String) = Unit
            override fun onNotification(method: String, params: JSONObject) { notifications += method }
            override fun onServerRequest(requestId: RpcId, method: String, params: JSONObject) = Unit
            override fun onProtocolIssue(message: String) { issues += message }
        })

        manager.connect(ConnectionConfig("wss://test", "token", "/tmp"))
        assertEquals("initialize", JSONObject(transport.sent.single()).getString("method"))
        rpc.onText(JSONObject().put("method", "turn/started").put("params", JSONObject()).toString())
        assertTrue(notifications.isEmpty())
        assertTrue(issues.any { it.contains("before initialization") })
        rpc.onText(JSONObject().put("id", 99).put("method", "future/request").put("params", JSONObject()).toString())
        val earlyError = JSONObject(transport.sent.last())
        assertEquals(99, earlyError.getInt("id"))
        assertEquals(-32002, earlyError.getJSONObject("error").getInt("code"))
        respondToLast(transport, rpc)
        assertTrue(manager.isReady())
        assertEquals("initialized", JSONObject(transport.sent.last()).getString("method"))
        assertEquals(listOf(false), ready)
        assertEquals(1, manager.connectionGeneration())

        transport.emitClose("network lost")
        assertTrue(secondConnection.await(2, TimeUnit.SECONDS))
        respondToLast(transport, rpc)
        assertEquals(listOf(false, true), ready)
        assertEquals(2, manager.connectionGeneration())
        manager.disconnect()
    }

    private fun respondToLast(transport: FakeTransport, rpc: JsonRpcSession) {
        val initialize = transport.sent.asReversed().first { JSONObject(it).optString("method") == "initialize" }
        rpc.onText(JSONObject().put("id", JSONObject(initialize).get("id")).put("result", JSONObject()).toString())
    }

    private class FakeTransport : WebSocketTransport {
        val sent = mutableListOf<String>()
        var connectCount = 0
        var onConnect: () -> Unit = {}
        private var listener: WebSocketTransport.Listener? = null
        override fun connect(endpoint: String, bearerToken: String, listener: WebSocketTransport.Listener) {
            this.listener = listener
            connectCount++
            listener.onOpen()
            onConnect()
        }
        override fun disconnect(reason: String) = Unit
        override fun send(text: String): Boolean { sent += text; return true }
        fun emitClose(reason: String) = listener?.onClosed(reason)
    }
}
