package com.aria.assistant.live

import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val onClosed: (String) -> Unit,
    private val onAuthRefreshRequested: (() -> String?)? = null
) {

    private var client: OkHttpClient = buildClient(null, null)
    private var socket: WebSocket? = null

    private var lastUrl: String = ""
    private var lastToken: String = ""
    private var lastCertPin: String = ""
    private var refreshAttempted = false

    fun connect(wsUrl: String, bearerToken: String, certPin: String = "") {
        connectInternal(wsUrl, bearerToken, certPin, resetRefreshState = true)
    }

    private fun connectInternal(
        wsUrl: String,
        bearerToken: String,
        certPin: String,
        resetRefreshState: Boolean
    ) {
        if (wsUrl.isBlank()) {
            onClosed("ws_url_missing")
            return
        }

        lastUrl = wsUrl
        lastToken = bearerToken
        lastCertPin = certPin
        if (resetRefreshState) refreshAttempted = false

        runCatching { socket?.close(1000, "reconnect") }
        runCatching { client.dispatcher.executorService.shutdown() }
        client = buildClient(wsUrl, certPin)

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
                val code = response?.code
                val message = t.message.orEmpty()
                val kind = t::class.java.simpleName

                if ((code == 401 || message.contains("401")) && tryRefreshAuth()) return

                onClosed("ws_failure:${code ?: -1}:$kind:${message.ifBlank { "unknown" }}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 1008 && reason.contains("auth", ignoreCase = true) && tryRefreshAuth()) return
                onClosed("ws_closed:$code:$reason")
            }
        })
    }

    fun sendAudioPcm(chunk: ByteArray): Boolean {
        return runCatching {
            socket?.send(ByteString.of(*chunk)) == true
        }.getOrDefault(false)
    }

    fun sendText(text: String): Boolean {
        return runCatching {
            socket?.send(text) == true
        }.getOrDefault(false)
    }

    fun close() {
        socket?.close(1000, "normal_close")
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    private fun buildClient(wsUrl: String?, certPin: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)

        val host = wsUrl?.toHttpUrlOrNull()?.host
        val pin = certPin?.trim().orEmpty()
        if (host != null && pin.startsWith("sha256/")) {
            builder.certificatePinner(
                CertificatePinner.Builder()
                    .add(host, pin)
                    .build()
            )
        }

        return builder.build()
    }

    private fun tryRefreshAuth(): Boolean {
        if (refreshAttempted) return false
        val refresher = onAuthRefreshRequested ?: return false

        val newToken = refresher.invoke().orEmpty()
        if (newToken.isBlank()) return false

        refreshAttempted = true
        onTextEvent("ws_auth_refreshed")
        connectInternal(lastUrl, newToken, lastCertPin, resetRefreshState = false)
        return true
    }
}
