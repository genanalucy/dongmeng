package com.verba.interpretation.protocol

import com.verba.interpretation.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID

class AgentSocket(
    private val client: OkHttpClient = OkHttpClient(),
    private val onEvent: (AgentEvent) -> Unit,
    private val onTts: (ByteArray) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val lock = Any()
    private var socket: WebSocket? = null
    private var ready = false
    private var finishing = false
    private val pendingAudio = ArrayDeque<ByteArray>()

    fun start(sourceLanguage: String, targetLanguage: String): Boolean = synchronized(lock) {
        if (socket != null) return false
        ready = false
        finishing = false
        pendingAudio.clear()
        val requestBuilder = Request.Builder().url(BuildConfig.TRANSLATION_WS_URL)
        if (BuildConfig.TRANSLATION_ORIGIN.isNotEmpty()) requestBuilder.header("Origin", BuildConfig.TRANSLATION_ORIGIN)
        val start = StartMessage(UUID.randomUUID().toString(), sourceLanguage, targetLanguage)
        socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(start.toJson())) fail("无法发送 start 消息。")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = try { AgentProtocol.parse(text) } catch (error: ProtocolException) {
                    fail(error.message ?: "Agent 协议错误。")
                    return
                }
                synchronized(lock) {
                    when (event) {
                        AgentEvent.Ready -> {
                            ready = true
                            while (pendingAudio.isNotEmpty()) webSocket.send(ByteString.of(*pendingAudio.removeFirst()))
                            if (finishing) webSocket.send(AgentProtocol.FINISH)
                        }
                        AgentEvent.Finished, is AgentEvent.Error -> {
                            closeLocked(webSocket)
                            webSocket.close(1000, "finished")
                        }
                        is AgentEvent.Subtitle -> Unit
                    }
                }
                onEvent(event)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val accepted = synchronized(lock) { ready && socket === webSocket }
                if (accepted && bytes.size > 0 && bytes.size % 2 == 0) onTts(bytes.toByteArray())
                else fail("TTS PCM16 音频包顺序或长度无效。")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) { closeLocked(webSocket) }
                onFailure(t.message ?: "WebSocket 连接失败。")
            }
        })
        true
    }

    fun sendAudio(packet: ByteArray): Boolean = synchronized(lock) {
        if (packet.size != 2_560 || finishing || socket == null) return false
        if (!ready) {
            if (pendingAudio.size >= 38) return false
            pendingAudio.addLast(packet.copyOf())
            true
        } else {
            socket?.send(ByteString.of(*packet)) == true
        }
    }

    fun finish(): Boolean = synchronized(lock) {
        val current = socket ?: return false
        if (finishing) return false
        finishing = true
        if (ready) current.send(AgentProtocol.FINISH) else true
    }

    fun cancel() = synchronized(lock) {
        socket?.close(1000, "cancelled")
        clearLocked()
    }

    private fun fail(message: String) {
        synchronized(lock) {
            socket?.close(1011, "protocol_error")
            clearLocked()
        }
        onFailure(message)
    }

    private fun closeLocked(webSocket: WebSocket) {
        if (socket === webSocket) clearLocked()
    }

    private fun clearLocked() {
        socket = null
        ready = false
        finishing = false
        pendingAudio.clear()
    }
}
