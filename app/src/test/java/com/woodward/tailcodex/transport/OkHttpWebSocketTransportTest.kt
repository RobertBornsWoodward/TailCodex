package com.woodward.tailcodex.transport

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OkHttpWebSocketTransportTest {
    @Test
    fun supersededSocketCannotDisconnectReplacement() {
        val server = MockWebServer()
        val firstOpen = CountDownLatch(1)
        val secondOpen = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { firstOpen.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Thread.sleep(150); webSocket.close(code, reason)
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        server.start()
        val disconnects = AtomicInteger()
        val transport = OkHttpWebSocketTransport(OkHttpClient())
        val listener = object : WebSocketTransport.Listener {
            override fun onOpen() { secondOpen.countDown() }
            override fun onText(text: String) = Unit
            override fun onClosed(reason: String) { disconnects.incrementAndGet() }
        }
        val endpoint = server.url("/").toString().replaceFirst("http://", "ws://")
        transport.connect(endpoint, "token", object : WebSocketTransport.Listener {
            override fun onOpen() { firstOpen.countDown() }
            override fun onText(text: String) = Unit
            override fun onClosed(reason: String) { disconnects.incrementAndGet() }
        })
        assertTrue(firstOpen.await(2, TimeUnit.SECONDS))
        transport.connect(endpoint, "token", listener)
        assertTrue(secondOpen.await(2, TimeUnit.SECONDS))
        Thread.sleep(250)
        assertEquals(0, disconnects.get())
        transport.disconnect()
        Thread.sleep(100)
        server.close()
    }
}
