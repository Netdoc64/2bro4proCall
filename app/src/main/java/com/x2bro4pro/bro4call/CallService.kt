package com.x2bro4pro.bro4call

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Foreground Service für eingehende Anrufe
 * Hält WebSocket-Verbindung im Hintergrund aufrecht
 */
class CallService : Service(), SignalingListener {
    
    companion object {
        const val SERVICE_ID = 1001
        const val CHANNEL_ID = "call_service_channel"
        const val INCOMING_CALL_CHANNEL_ID = "incoming_call_channel"
        const val ACTION_START_SERVICE = "START_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_TOKEN = "token"
        
        private const val TAG = "CallService"
        private const val SERVICE_RESTART_DELAY_MS = 3000L
        private const val THREAD_JOIN_TIMEOUT_MS = 2000L
    }
    
    private val binder = CallServiceBinder()
    private var signalingClient: SignalingClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isConnected = false
    private var currentRoomId: String? = null
    private var currentToken: String? = null
    
    // Callback für Activity-Updates
    var onCallReceived: ((String, String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    
    inner class CallServiceBinder : Binder() {
        fun getService(): CallService = this@CallService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannels()
        acquireWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
                val token = intent.getStringExtra(EXTRA_TOKEN)
                
                if (roomId != null && token != null) {
                    currentRoomId = roomId
                    currentToken = token
                    
                    // Nur starten wenn noch nicht läuft
                    if (!isConnected) {
                        startForegroundService()
                        connectWebSocket(roomId, token)
                    } else {
                        Log.d(TAG, "Service already running and connected")
                        // Update mit neuen Credentials falls nötig
                        if (currentRoomId != roomId || currentToken != token) {
                            Log.d(TAG, "Credentials changed, reconnecting...")
                            disconnectWebSocket()
                            connectWebSocket(roomId, token)
                        }
                    }
                } else {
                    Log.e(TAG, "Missing room_id or token")
                    stopSelf()
                }
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundService()
            }
        }
        
        // Service wird nach Crash neu gestartet
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        disconnectWebSocket()
        releaseWakeLock()
        super.onDestroy()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed (app swiped away), restarting service...")
        
        // Restart service wenn App aus Recent Apps entfernt wurde
        val savedRoomId = currentRoomId
        val savedToken = currentToken
        
        if (savedRoomId != null && savedToken != null) {
            val restartIntent = Intent(applicationContext, CallService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra(EXTRA_ROOM_ID, savedRoomId)
                putExtra(EXTRA_TOKEN, savedToken)
            }
            
            // Delay um Boot-Loops zu vermeiden
            // WeakReference um Memory Leak zu vermeiden
            val appContext = applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(restartIntent)
                    } else {
                        appContext.startService(restartIntent)
                    }
                    Log.d(TAG, "Service restarted after task removal")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart service", e)
                }
            }, SERVICE_RESTART_DELAY_MS)
        }
    }
    
    private fun startForegroundService() {
        val notification = createServiceNotification(
            title = "2bro4Call Bereit",
            text = "Warte auf eingehende Anrufe..."
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(SERVICE_ID, notification)
        }
        Log.d(TAG, "Service started in foreground")
    }
    
    private fun stopForegroundService() {
        disconnectWebSocket()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Channel für Service-Status
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Anruf-Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an, dass der Anruf-Service aktiv ist"
                setShowBadge(false)
            }
            
            // Channel für eingehende Anrufe
            val callChannel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                "Eingehende Anrufe",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen für eingehende Anrufe"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
            
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(callChannel)
        }
    }
    
    private fun createServiceNotification(title: String, text: String): Notification {
        val notificationIntent = Intent(this, AppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return Notification.Builder(this, CHANNEL_ID).apply {
            setContentTitle(title)
            setContentText(text)
            setSmallIcon(R.drawable.app_logo)
            setContentIntent(pendingIntent)
            setOngoing(true)
            
            // Stop-Action
            val stopIntent = Intent(this@CallService, CallService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            val stopPendingIntent = PendingIntent.getService(
                this@CallService,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            addAction(Notification.Action.Builder(
                null,
                "Beenden",
                stopPendingIntent
            ).build())
        }.build()
    }
    
    private fun showIncomingCallNotification(sessionId: String, domain: String) {
        val notificationIntent = Intent(this, AppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("incoming_session_id", sessionId)
            putExtra("incoming_domain", domain)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            sessionId.hashCode(),
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = Notification.Builder(this, INCOMING_CALL_CHANNEL_ID).apply {
            setContentTitle("📞 Eingehender Anruf")
            setContentText("Besucher auf $domain")
            setSmallIcon(R.drawable.app_logo)
            setContentIntent(pendingIntent)
            setAutoCancel(true)
            setCategory(Notification.CATEGORY_CALL)
            setPriority(Notification.PRIORITY_HIGH)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setStyle(Notification.CallStyle.forIncomingCall(
                    android.app.Person.Builder().setName("Besucher").build(),
                    pendingIntent,
                    pendingIntent
                ))
            }
        }.build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(sessionId.hashCode(), notification)
        
        Log.d(TAG, "Incoming call notification shown for session: $sessionId")
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "2bro4Call::CallService"
            ).apply {
                acquire() // Unbegrenzt - wird nur bei stopSelf() released
            }
            Log.d(TAG, "Wake lock acquired (indefinite)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
    
    private fun connectWebSocket(roomId: String, token: String) {
        try {
            if (signalingClient == null) {
                signalingClient = SignalingClient(this, "call-server.netdoc64.workers.dev")
            }
            signalingClient?.connect(roomId, token)
            Log.d(TAG, "Connecting WebSocket: $roomId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect WebSocket", e)
            updateServiceNotification("Verbindungsfehler", "Versuche erneut...")
        }
    }
    
    private fun disconnectWebSocket() {
        signalingClient?.disconnect()
        signalingClient = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)
        Log.d(TAG, "WebSocket disconnected")
    }
    
    private fun updateServiceNotification(title: String, text: String) {
        val notification = createServiceNotification(title, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(SERVICE_ID, notification)
    }
    
    // SignalingListener Interface Implementation
    override fun onWebSocketOpen() {
        isConnected = true
        onConnectionStateChanged?.invoke(true)
        updateServiceNotification(
            "2bro4Call Verbunden ✅",
            "Bereit für eingehende Anrufe"
        )
        Log.d(TAG, "WebSocket connected")
    }
    
    override fun onNewSignalReceived(message: JSONObject) {
        val type = message.optString("type", "")
        Log.d(TAG, "Signal received: $type")
        
        when (type) {
            "offer" -> {
                // Eingehender Anruf!
                val sessionId = message.optString("sessionId", "unknown")
                val domain = currentRoomId?.split("__")?.firstOrNull() ?: "unbekannt"
                
                showIncomingCallNotification(sessionId, domain)
                
                // Callback an Activity (falls gebunden)
                onCallReceived?.invoke(sessionId, domain)
                
                Log.d(TAG, "Incoming call from session: $sessionId")
            }
            "system" -> {
                val subtype = message.optString("subtype", "")
                if (subtype == "visitor_joined") {
                    val visitorData = message.optJSONObject("visitor")
                    val sessionId = visitorData?.optString("sessionId", "") ?: ""
                    val domain = visitorData?.optString("domain", "") ?: ""
                    
                    if (sessionId.isNotEmpty()) {
                        showIncomingCallNotification(sessionId, domain)
                        onCallReceived?.invoke(sessionId, domain)
                    }
                }
            }
        }
    }
    
    override fun onWebSocketClosed() {
        isConnected = false
        onConnectionStateChanged?.invoke(false)
        updateServiceNotification(
            "2bro4Call Getrennt ⚠️",
            "Versuche Wiederverbindung..."
        )
        Log.d(TAG, "WebSocket closed")
    }
    
    override fun onError(message: String) {
        updateServiceNotification(
            "Fehler",
            message
        )
        Log.e(TAG, "WebSocket error: $message")
    }
    
    override fun onReconnecting(attempt: Int, delayMs: Int) {
        updateServiceNotification(
            "Wiederverbindung... ($attempt/8)",
            "Nächster Versuch in ${delayMs / 1000}s"
        )
        Log.d(TAG, "Reconnecting attempt $attempt, delay ${delayMs}ms")
    }
    
    override fun onReconnectFailed() {
        updateServiceNotification(
            "Verbindung fehlgeschlagen ❌",
            "Maximale Versuche erreicht"
        )
        Log.e(TAG, "Reconnect failed after max attempts")
    }
    
    // Public methods für Activity-Kommunikation
    fun isServiceConnected(): Boolean = isConnected
    
    fun reconnect() {
        currentRoomId?.let { roomId ->
            currentToken?.let { token ->
                disconnectWebSocket()
                connectWebSocket(roomId, token)
            }
        }
    }
}
