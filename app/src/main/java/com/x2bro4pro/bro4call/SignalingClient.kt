package com.x2bro4pro.bro4call

import android.util.Log
import okhttp3.*
import org.json.JSONObject

// Listener-Interface, um Ereignisse an die AppActivity zurückzusenden
interface SignalingListener {
    fun onWebSocketOpen()
    fun onNewSignalReceived(message: JSONObject)
    fun onWebSocketClosed()
    fun onError(message: String)
    // called when an automatic reconnect is scheduled (attempt number, delay ms)
    fun onReconnecting(attempt: Int, delayMs: Int)
    // called when reconnect gives up (max attempts reached)
    fun onReconnectFailed()
}

class SignalingClient(private val listener: SignalingListener, private val backendHost: String) {
    companion object {
        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }
    
    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    @Volatile
    private var reconnecting = false
    private val MAX_RECONNECT_ATTEMPTS = 8
    private val JITTER_PERCENT = 0.2 // +/- 20%
    private val PING_INTERVAL_MS = 30000L // 30 seconds
    private val pingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pingRunnable = object : Runnable {
        override fun run() {
            try {
                webSocket?.let { ws ->
                    val ping = JSONObject().apply {
                        put("type", "ping")
                        put("timestamp", System.currentTimeMillis())
                    }
                    send(ping)
                    Log.d("SignalingClient", "Sent heartbeat ping")
                }
            } catch (e: Exception) {
                Log.e("SignalingClient", "Heartbeat error: ${e.message}")
            }
            pingHandler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    // store last connection params so reconnect can retry automatically
    @Volatile
    private var lastRoomId: String? = null
    @Volatile
    private var lastToken: String? = null
    @Volatile
    private var userInitiatedDisconnect = false

    fun connect(roomId: String, token: String) {
        connectWithMode(roomId, token, "talk")
    }
    
    fun connectWithMode(roomId: String, token: String, mode: String = "talk") {
        // store requested connection for automatic reconnects
        lastRoomId = roomId
        lastToken = token
        userInitiatedDisconnect = false
        reconnectAttempts = 0  // Reset bei jedem neuen connect()
        reconnecting = false

        val scheme = if (backendHost.startsWith("http")) {
            // strip scheme
            backendHost.replaceFirst(Regex("^https?://"), "")
        } else backendHost
        val fullUrl = "wss://$scheme/call/$roomId?token=$token&mode=$mode"
        val request = Request.Builder().url(fullUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onWebSocketOpen()
                Log.d("SignalingClient", "WebSocket connection opened (mode=$mode).")
                if (mode == "talk") {
                    sendIdentifyPacket()
                } else {
                    sendMonitorIdentifyPacket()
                }
                // reset backoff
                reconnectAttempts = 0
                reconnecting = false
                
                // Start heartbeat/ping thread
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    // Dispatch known message types
                    val type = json.optString("type", "")
                    when (type) {
                        "offer", "answer", "candidate", "system" -> listener.onNewSignalReceived(json)
                        else -> listener.onNewSignalReceived(json)
                    }
                } catch (e: Exception) {
                    Log.e("SignalingClient", "Failed to parse message: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onWebSocketClosed()
                // schedule reconnect only when not a user-initiated close
                if (!userInitiatedDisconnect) scheduleReconnectIfNeeded()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError("Connection failed: ${t.message}")
                if (!userInitiatedDisconnect) scheduleReconnectIfNeeded()
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
        // mark as user requested to avoid reconnect attempts
        userInitiatedDisconnect = true
        stopHeartbeat()
        
        // Cleanup all Handler callbacks to prevent memory leaks
        pingHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        
        try {
            webSocket?.close(1000, "App disconnected")
        } finally {
            webSocket = null
            // clear stored params so reconnect won't happen
            lastRoomId = null
            lastToken = null
        }
    }
    
    private fun startHeartbeat() {
        stopHeartbeat()
        pingHandler.postDelayed(pingRunnable, PING_INTERVAL_MS)
    }
    
    private fun stopHeartbeat() {
        pingHandler.removeCallbacks(pingRunnable)
    }

    private fun sendIdentifyPacket() {
        val identify = JSONObject().apply {
            put("type", "identify")
            put("role", "agent")
            put("agentName", "Agent Max Mustermann (${android.os.Build.MODEL})")
        }
        send(identify)
    }
    
    private fun sendMonitorIdentifyPacket() {
        val identify = JSONObject().apply {
            put("type", "identify")
            put("role", "monitor")
            put("agentName", "Supervisor (${android.os.Build.MODEL})")
        }
        send(identify)
    }

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private fun scheduleReconnectIfNeeded() {
        if (reconnecting) return
        // If we don't have connection params (room/token) or token is blank,
        // don't attempt automatic reconnects. This prevents reconnect loops
        // when the user is not logged in or token has been cleared elsewhere.
        val roomNow = lastRoomId
        val tokenNow = lastToken
        if (roomNow.isNullOrBlank() || tokenNow.isNullOrBlank()) {
            Log.d("SignalingClient", "Not scheduling reconnect: missing room or token (room=$roomNow, tokenBlank=${tokenNow.isNullOrBlank()})")
            listener.onReconnectFailed()
            return
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.d("SignalingClient", "Max reconnect attempts reached: $reconnectAttempts, will retry with longer delay")
            // Statt aufzugeben, längere Pause und dann weiter versuchen
            reconnectAttempts = MAX_RECONNECT_ATTEMPTS - 2 // Reset zu mittlerem Backoff
            listener.onReconnecting(reconnectAttempts, 120000) // 2 Minuten
            
            reconnectHandler.postDelayed({
                val room = lastRoomId
                val token = lastToken
                if (!userInitiatedDisconnect && room != null && token != null) {
                    try {
                        Log.d("SignalingClient", "Attempting long-delay reconnect to $room")
                        connect(room, token)
                    } catch (e: Exception) {
                        Log.e("SignalingClient", "Long-delay reconnect failed: ${e.message}")
                    }
                }
                reconnecting = false
            }, 120000)
            return
        }
        reconnecting = true
        reconnectAttempts++
        var delayMs = calculateBackoffMs(reconnectAttempts)
        // add jitter +/- JITTER_PERCENT
        val jitterRange = (delayMs * JITTER_PERCENT).toInt()
        val jitter = (kotlin.random.Random.Default.nextInt(-jitterRange, jitterRange + 1))
        delayMs = (delayMs + jitter).coerceAtLeast(0)
        Log.d("SignalingClient", "Scheduling reconnect in ${delayMs}ms (attempt $reconnectAttempts, jitter=$jitter)")
        // notify listener about reconnect schedule
        listener.onReconnecting(reconnectAttempts, delayMs)

        reconnectHandler.postDelayed({
            // only reconnect if we have stored params and the user didn't explicitly disconnect
            val room = lastRoomId
            val token = lastToken
            if (!userInitiatedDisconnect && room != null && token != null) {
                try {
                    Log.d("SignalingClient", "Attempting automatic reconnect to $room")
                    connect(room, token)
                } catch (e: Exception) {
                    Log.e("SignalingClient", "Reconnect attempt failed: ${e.message}")
                }
            }
            reconnecting = false
        }, delayMs.toLong())
    }

    private fun calculateBackoffMs(attempts: Int): Int {
        val base = 1000 // 1s
        val max = 60_000 // 60s
        val exp = (base * Math.pow(2.0, (attempts - 1).toDouble())).toInt()
        return Math.min(exp, max)
    }
}
