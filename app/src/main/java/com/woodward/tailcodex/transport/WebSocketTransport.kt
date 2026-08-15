package com.woodward.tailcodex.transport

interface WebSocketTransport {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(reason: String)
    }

    fun connect(endpoint: String, bearerToken: String, listener: Listener)
    fun send(text: String): Boolean
    fun disconnect(reason: String = "Client disconnect")
}
