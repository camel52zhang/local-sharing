package com.localsharing.app.network

import com.localsharing.app.model.IncomingTransfer
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed class WsEvent {
    object Open : WsEvent()
    data class Incoming(val transfer: IncomingTransfer) : WsEvent()
    data class Failure(val msg: String) : WsEvent()
    object Closed : WsEvent()
}

class ShareWebSocket(
    private val url: String,
    private val onEvent: (WsEvent) -> Unit,
) {
    private var ws: WebSocket? = null

    fun connect() {
        val req = Request.Builder().url(url).build()
        ws = ShareApi.client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onEvent(WsEvent.Open)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parse(text)?.let { onEvent(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onEvent(WsEvent.Failure(t.message ?: "websocket error"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onEvent(WsEvent.Closed)
                }
            },
        )
    }

    fun sendAck(transferId: String) {
        val msg = JSONObject().put("type", "transfer-ack").put("transferId", transferId).toString()
        ws?.send(msg)
    }

    fun close() {
        ws?.close(1000, "bye")
        ws = null
    }

    private fun parse(text: String): WsEvent? {
        return try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "incoming" -> {
                    val t = obj.getJSONObject("transfer")
                    WsEvent.Incoming(
                        IncomingTransfer(
                            id = t.optString("id"),
                            name = t.optString("name", "file"),
                            size = t.optLong("size", 0),
                            kind = t.optString("kind", "file"),
                        ),
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
