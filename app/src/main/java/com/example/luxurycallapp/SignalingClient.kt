package com.example.luxurycallapp

import android.util.Log
import okhttp3.*
import org.json.JSONObject

// Listener-Interface, um Ereignisse an die AppActivity zurückzusenden
interface SignalingListener {
    fun onWebSocketOpen()
    fun onNewSignalReceived(message: JSONObject)
    fun onWebSocketClosed()
    fun onError(message: String)
}

class SignalingClient(private val listener: SignalingListener) {
    // ... (Code unverändert, aber es unterstützt jetzt die Business-ID aus der AppActivity)
    
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    // ACHTUNG: Hier muss deine tatsächliche Cloudflare Worker WSS URL rein
    private val BASE_WSS_URL = "wss://dein-worker.dein-name.workers.dev/call/"

    fun connect(businessId: String) {
        val fullUrl = "${BASE_WSS_URL}${businessId}?role=agent"
        val request = Request.Builder().url(fullUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onWebSocketOpen()
                Log.d("SignalingClient", "WebSocket connection opened.")
                sendIdentifyPacket() 
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    // Weiterleitung der JSON-Nachricht an die Activity
                    listener.onNewSignalReceived(json)
                } catch (e: Exception) {
                    Log.e("SignalingClient", "Failed to parse message: $text", e)
                }
            }
            // ... (onClosing, onFailure wie zuvor)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onWebSocketClosed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError("Connection failed: ${t.message}")
            }
        })
    }
    
    fun send(message: JSONObject) {
        if (webSocket?.send(message.toString()) == true) {
            Log.d("SignalingClient", "Sent: ${message.getString("type")}")
        } else {
            Log.w("SignalingClient", "Failed to send message: WebSocket not open.")
        }
    }
    
    fun disconnect() {
        webSocket?.close(1000, "App disconnected")
        webSocket = null
    }

    private fun sendIdentifyPacket() {
        val identify = JSONObject().apply {
            put("type", "identify")
            put("role", "agent")
            put("agentName", "Agent Max Mustermann (${android.os.Build.MODEL})")
        }
        send(identify)
    }
}
