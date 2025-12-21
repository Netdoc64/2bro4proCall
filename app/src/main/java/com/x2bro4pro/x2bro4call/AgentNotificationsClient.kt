package com.x2bro4pro.bro4call

import android.util.Log
import okhttp3.*
import org.json.JSONObject

/**
 * WebSocket Client für Agent Notifications
 * Empfängt Realtime-Events über Queue-Changes, Ringing, etc.
 * Separate von SignalingClient (der für Call Rooms zuständig ist)
 */

interface AgentNotificationListener {
    fun onConnected()
    fun onNewCall(roomId: String, domainId: String, domainName: String, timestamp: Long)
    fun onCallRinging(roomId: String, initiator: String, timestamp: Long)
    fun onCallActive(roomId: String, domainId: String, agentId: String?, timestamp: Long)
    fun onCallEnded(roomId: String, domainId: String, reason: String)
    fun onDisconnected()
    fun onError(message: String)
    // ZOMBIE SERVICE FIX: Called when max reconnect attempts exhausted - service should reinitialize
    fun onReconnectFailed()
}

class AgentNotificationsClient(
    private val listener: AgentNotificationListener,
    private val backendHost: String,
    private val token: String
) {
    companion object {
        private const val TAG = "AgentNotifications"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val PING_INTERVAL_MS = 30000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        // HEARTBEAT WATCHDOG: If no pong received within 90s, force reconnect
        private const val PONG_TIMEOUT_MS = 90000L
        
        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                // HEARTBEAT FIX: Removed pingInterval - using manual ping/pong with watchdog
                // OkHttp protocol ping causes EOF if server doesn't respond with protocol pong
                .build()
        }
    }
    
    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var userInitiatedDisconnect = false
    @Volatile
    private var reconnectAttempts = 0
    @Volatile
    private var lastPongReceived = System.currentTimeMillis() // HEARTBEAT WATCHDOG
    
    private val handlerThread = android.os.HandlerThread("AgentNotifications-HandlerThread").apply { start() }
    private val backgroundHandler = android.os.Handler(handlerThread.looper)
    
    private val pingRunnable = object : Runnable {
        override fun run() {
            try {
                if (isConnected) {
                    // HEARTBEAT WATCHDOG: Check if last pong is too old
                    val timeSinceLastPong = System.currentTimeMillis() - lastPongReceived
                    if (timeSinceLastPong > PONG_TIMEOUT_MS) {
                        Log.e(TAG, "🚨 Pong timeout! Last pong was ${timeSinceLastPong}ms ago (max: ${PONG_TIMEOUT_MS}ms)")
                        Log.e(TAG, "Connection appears dead - forcing reconnect")
                        // Force close and reconnect
                        webSocket?.close(1000, "Pong timeout")
                        return // Don't schedule next ping - reconnect will restart heartbeat
                    }
                    
                    val ping = JSONObject().apply {
                        put("type", "ping")
                    }
                    send(ping)
                    Log.d(TAG, "Sent heartbeat ping (last pong: ${timeSinceLastPong}ms ago)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
            }
            // Only reschedule if connected - prevents flood during disconnect
            if (isConnected) {
                backgroundHandler.postDelayed(this, PING_INTERVAL_MS)
            }
        }
    }
    
    fun connect() {
        Log.d(TAG, "Connecting to Agent Notifications...")
        
        userInitiatedDisconnect = false
        
        val scheme = if (backendHost.startsWith("http")) {
            backendHost.replaceFirst(Regex("^https?://"), "")
        } else backendHost
        
        val url = "wss://$scheme/api/agent/notifications?token=$token"
        Log.d(TAG, "WebSocket URL: wss://$scheme/api/agent/notifications")
        
        val request = Request.Builder().url(url).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ Agent Notifications WebSocket opened")
                isConnected = true
                reconnectAttempts = 0
                lastPongReceived = System.currentTimeMillis() // HEARTBEAT WATCHDOG: Reset on connect
                listener.onConnected()
                startHeartbeat()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    
                    Log.d(TAG, "📩 Received: $type")
                    
                    when (type) {
                        "connected" -> {
                            val userId = json.optString("userId", "")
                            val domains = json.optJSONArray("domains")
                            Log.d(TAG, "Connected as userId=$userId with ${domains?.length() ?: 0} domains")
                        }
                        
                        "new_call" -> {
                            // Backend sendet "new_call" wenn Room erstellt wurde (REST API)
                            val roomId = json.optString("room_id", "")
                            val domainId = json.optString("domain_id", "")
                            val domainName = json.optString("domain_name", "")
                            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                            
                            Log.d(TAG, "🆕 New call in queue: $roomId (domain: $domainName)")
                            listener.onNewCall(roomId, domainId, domainName, timestamp)
                        }
                        
                        "visitor_waiting" -> {
                            // ✅ FIX: Backend sendet 'visitor_waiting' wenn Visitor connectet (SILENT)
                            // Gleich behandeln wie new_call (nur Queue-Update, kein Ringtone)
                            val roomId = json.optString("room_id", "")
                            val domainId = json.optString("domain_id", "")
                            val domainName = json.optString("domain_name", domainId) // Fallback
                            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                            
                            Log.d(TAG, "🆕 Visitor waiting: $roomId (domain: $domainName)")
                            listener.onNewCall(roomId, domainId, domainName, timestamp)
                        }
                        
                        "incoming_call" -> {
                            // ✅ FIX: Backend sendet 'incoming_call', NICHT 'call_ringing'
                            val roomId = json.optString("room_id", "")
                            val from = json.optString("from", "unknown")
                            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                            
                            Log.d(TAG, "🔔 Incoming call: $roomId (from: $from)")
                            listener.onCallRinging(roomId, from, timestamp)
                        }
                        
                        "call_ringing" -> {
                            // Legacy support (falls Backend auch call_ringing sendet)
                            val roomId = json.optString("room_id", "")
                            val initiator = json.optString("initiator", "unknown")
                            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                            
                            Log.d(TAG, "🔔 Call ringing: $roomId (initiator: $initiator)")
                            listener.onCallRinging(roomId, initiator, timestamp)
                        }
                        
                        "call_active" -> {
                            val roomId = json.optString("room_id", "")
                            val domainId = json.optString("domain_id", "")
                            val agentId = json.optString("agent_id").takeIf { it.isNotEmpty() }
                            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                            
                            Log.d(TAG, "✅ Call active: $roomId (agent: ${agentId ?: "none"})")
                            listener.onCallActive(roomId, domainId, agentId, timestamp)
                        }
                        
                        "call_ended" -> {
                            val roomId = json.optString("room_id", "")
                            val domainId = json.optString("domain_id", "")
                            val reason = json.optString("reason", "unknown")
                            
                            Log.d(TAG, "⏹️ Call ended: $roomId (reason: $reason)")
                            listener.onCallEnded(roomId, domainId, reason)
                        }
                        
                        "pong" -> {
                            // HEARTBEAT WATCHDOG: Update timestamp on pong received
                            lastPongReceived = System.currentTimeMillis()
                            Log.d(TAG, "Received pong - connection alive")
                        }
                        
                        else -> {
                            Log.w(TAG, "Unknown message type: $type")
                        }
                    }
                } catch (e: Exception) {
                    // FRAGMENTATION FIX: Intelligent logging - don't overflow LogCat
                    val messageSize = text.length
                    val preview = if (messageSize > 400) {
                        "First 200: ${text.take(200)}...\nLast 200: ${text.takeLast(200)}"
                    } else {
                        text
                    }
                    Log.e(TAG, "Failed to parse message (${messageSize} bytes): $preview", e)
                    
                    // Log JSON structure validation
                    val openBraces = text.count { it == '{' }
                    val closeBraces = text.count { it == '}' }
                    if (openBraces != closeBraces) {
                        Log.e(TAG, "🚨 Invalid JSON structure: open={$openBraces}, close={$closeBraces}")
                    }
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "⚠️ WebSocket closing: code=$code, reason=$reason")
                isConnected = false
                listener.onDisconnected()
                
                if (!userInitiatedDisconnect) {
                    scheduleReconnect()
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket failure: ${t.message}", t)
                isConnected = false
                listener.onError("Connection failed: ${t.message}")
                listener.onDisconnected()
                
                if (!userInitiatedDisconnect) {
                    scheduleReconnect()
                }
            }
        })
    }
    
    fun disconnect() {
        Log.d(TAG, "Disconnecting Agent Notifications...")
        userInitiatedDisconnect = true
        isConnected = false
        stopHeartbeat()
        
        backgroundHandler.removeCallbacksAndMessages(null)
        
        try {
            webSocket?.close(1000, "Agent logged out")
        } finally {
            webSocket = null
        }
        
        try {
            handlerThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to quit HandlerThread: ${e.message}")
        }
    }
    
    fun send(message: JSONObject) {
        if (webSocket?.send(message.toString()) == true) {
            Log.d(TAG, "Sent: ${message.optString("type")}")
        } else {
            Log.w(TAG, "Failed to send message: WebSocket not open")
        }
    }
    
    fun isConnected(): Boolean = isConnected
    
    @Synchronized
    private fun startHeartbeat() {
        stopHeartbeat()
        backgroundHandler.postDelayed(pingRunnable, PING_INTERVAL_MS)
    }
    
    @Synchronized
    private fun stopHeartbeat() {
        backgroundHandler.removeCallbacks(pingRunnable)
    }
    
    private fun scheduleReconnect() {
        if (userInitiatedDisconnect) {
            Log.d(TAG, "User-initiated disconnect, not reconnecting")
            return
        }
        
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            // ZOMBIE SERVICE FIX: Notify listener to reinitialize connection
            listener.onReconnectFailed()
            return
        }
        
        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts
        
        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${delay}ms")
        
        backgroundHandler.postDelayed({
            if (!userInitiatedDisconnect && !isConnected) {
                Log.d(TAG, "Attempting reconnect #$reconnectAttempts")
                connect()
            }
        }, delay)
    }
}
