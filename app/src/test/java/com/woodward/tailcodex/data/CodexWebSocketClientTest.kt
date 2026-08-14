package com.woodward.tailcodex.data

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CodexWebSocketClientTest {
    @Test
    fun supersededSocketCannotDisconnectReplacement() {
        val firstSocketOpened = CountDownLatch(1)
        val replacementConnected = CountDownLatch(1)
        val disconnects = AtomicInteger(0)
        val protocolErrors = AtomicInteger(0)
        val server = MockWebServer()

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        firstSocketOpened.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Thread.sleep(300)
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        if (request.has("id")) {
                            webSocket.send(
                                JSONObject()
                                    .put("id", request.get("id"))
                                    .put("result", JSONObject())
                                    .toString(),
                            )
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        server.start()

        val client = CodexWebSocketClient(
            object : CodexWebSocketClient.Listener {
                override fun onConnected() {
                    replacementConnected.countDown()
                }

                override fun onDisconnected(reason: String) {
                    disconnects.incrementAndGet()
                }

                override fun onNotification(method: String, params: JSONObject) = Unit
                override fun onApprovalRequested(request: ApprovalRequest) = Unit

                override fun onProtocolError(message: String) {
                    protocolErrors.incrementAndGet()
                }
            },
        )
        val endpoint = server.url("/").toString().replaceFirst("http://", "ws://")
        val config = ConnectionConfig(endpoint = endpoint, token = "test-token", defaultCwd = "/tmp")

        client.connect(config)
        assertTrue(firstSocketOpened.await(2, TimeUnit.SECONDS))
        client.connect(config)
        assertTrue(replacementConnected.await(2, TimeUnit.SECONDS))
        Thread.sleep(500)

        assertEquals(0, disconnects.get())
        assertEquals(0, protocolErrors.get())
        client.close()
        Thread.sleep(100)
        server.close()
    }
}
