package com.woodward.tailcodex.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OkHttpWebSocketTransport(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : WebSocketTransport {
    private val generation = AtomicLong(0)

    @Volatile
    private var socket: WebSocket? = null

    override fun connect(endpoint: String, bearerToken: String, listener: WebSocketTransport.Listener) {
        disconnect("Superseded connection")
        val currentGeneration = generation.incrementAndGet()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $bearerToken")
            .build()
        socket = httpClient.newWebSocket(request, SocketListener(currentGeneration, listener))
    }

    override fun send(text: String): Boolean = socket?.send(text) == true

    override fun disconnect(reason: String) {
        generation.incrementAndGet()
        socket?.close(1000, reason)
        socket = null
    }

    private inner class SocketListener(
        private val socketGeneration: Long,
        private val listener: WebSocketTransport.Listener,
    ) : WebSocketListener() {
        private fun isCurrent(): Boolean = socketGeneration == generation.get()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                webSocket.close(1000, "Superseded connection")
                return
            }
            listener.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isCurrent()) listener.onText(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent()) listener.onClosed(reason.ifBlank { "Connection closed" })
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            if (!isCurrent()) return
            val reason = buildString {
                append(error.message ?: "WebSocket failure")
                response?.let { append(" (HTTP ${it.code})") }
            }
            listener.onClosed(reason)
        }
    }
}
