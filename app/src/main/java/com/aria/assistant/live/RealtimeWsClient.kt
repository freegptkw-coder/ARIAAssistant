package com.aria.assistant.live

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class RealtimeWsClient(
    private val onBinaryAudioChunk: (ByteArray) -> Unit,
    private val onTextEvent: (String) -> Unit,
    private val onClosed: (String) -> Unit
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    fun connect(wsUrl: String, bearerToken: String) {
        if (wsUrl.isBlank()) {
            onClosed("ws_url_missing")
            return
        }

        val request = Request.Builder()
            .url(wsUrl)
            .apply {
                if (bearerToken.isNotBlank()) {
                    addHeader("Authorization", "Bearer $bearerToken")
                }
            }
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onTextEvent("ws_open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onTextEvent(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinaryAudioChunk(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed("ws_failure:${t.message ?: "unknown"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed("ws_closed:$code:$reason")
            }
        })
    }

    fun sendAudioPcm(chunk: ByteArray) {
        socket?.send(ByteString.of(*chunk))
    }

    fun sendText(text: String) {
        socket?.send(text)
    }

    fun close() {
        socket?.close(1000, "normal_close")
        socket = null
        client.dispatcher.executorService.shutdown()
    }
}
