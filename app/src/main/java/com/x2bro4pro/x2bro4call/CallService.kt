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
 * Hält Agent Notifications WebSocket im Hintergrund aufrecht
 */
class CallService : Service(), AgentNotificationListener {
    
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
    private var agentNotificationsClient: AgentNotificationsClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isConnected = false
    private var currentToken: String? = null
    
    // ANDROID 14 FIX: Track active call state for foreground service type switching
    private var isActiveCall = false
    
    // Callback für Activity-Updates
    var onCallReceived: ((String, String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onWebSocketStateChanged: ((Boolean) -> Unit)? = null  // REDUNDANCY FIX
    
    // Queue-Event Callbacks (für AppActivity UI-Updates)
    var onQueueUpdated: ((String, String, String) -> Unit)? = null  // roomId, domainId, domainName
    var onCallRingingCallback: ((String, String) -> Unit)? = null  // roomId, initiator
    var onCallActiveCallback: ((String) -> Unit)? = null  // roomId
    var onCallEndedCallback: ((String, String) -> Unit)? = null  // roomId, reason
    
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
                val token = intent.getStringExtra(EXTRA_TOKEN)
                
                if (token != null) {
                    currentToken = token
                    
                    // Start Foreground Service
                    startForegroundService()
                    
                    // Connect to Agent Notifications WebSocket (nicht zu einem fixen Room!)
                    if (!isConnected) {
                        connectAgentNotifications(token)
                    }
                } else {
                    Log.e(TAG, "Missing token")
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
        
        // Clear callbacks to prevent memory leaks
        onCallReceived = null
        onConnectionStateChanged = null
        onQueueUpdated = null
        onWebSocketStateChanged = null
        onCallRingingCallback = null
        onCallActiveCallback = null
        onCallEndedCallback = null
        
        disconnectAgentNotifications()
        releaseWakeLock()
        super.onDestroy()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed (app swiped away), restarting service...")
        
        // Restart service in FCM-only mode (no fixed WebSocket room)
        val savedToken = currentToken
        
        if (savedToken != null) {
            val restartIntent = Intent(applicationContext, CallService::class.java).apply {
                action = ACTION_START_SERVICE
                // KEIN EXTRA_ROOM_ID - Agent hat keinen festen Room!
                putExtra(EXTRA_TOKEN, savedToken)
            }
            
            // Delay um Boot-Loops zu vermeiden
            val appContextRef = java.lang.ref.WeakReference(applicationContext)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val restartRunnable = Runnable {
                val ctx = appContextRef.get()
                if (ctx != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ctx.startForegroundService(restartIntent)
                        } else {
                            ctx.startService(restartIntent)
                        }
                        Log.d(TAG, "Service restarted after task removal")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart service", e)
                    }
                }
            }
            handler.postDelayed(restartRunnable, SERVICE_RESTART_DELAY_MS)
        }
    }
    
    private fun startForegroundService() {
        val notification = createServiceNotification(
            title = "2bro4Call Bereit",
            text = "Warte auf eingehende Anrufe..."
        )
        
        // ANDROID 14 FIX: Use appropriate foreground service type based on state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (isActiveCall) {
                // Active call: Use MICROPHONE type (requires RECORD_AUDIO permission)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                // No active call: Use DATA_SYNC for WebSocket/FCM listening
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(SERVICE_ID, notification, serviceType)
            Log.d(TAG, "Service started in foreground (type: ${if (isActiveCall) "MICROPHONE" else "DATA_SYNC"})")
        } else {
            startForeground(SERVICE_ID, notification)
            Log.d(TAG, "Service started in foreground (pre-Android Q)")
        }
    }
    
    // ANDROID 14 FIX: Update foreground service type when call state changes
    private fun updateForegroundServiceType(activeCall: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isActiveCall != activeCall) {
            isActiveCall = activeCall
            
            try {
                val notification = createServiceNotification(
                    title = if (activeCall) "2bro4Call - Im Gespräch" else "2bro4Call Bereit",
                    text = if (activeCall) "Aktiver Anruf läuft" else "Warte auf eingehende Anrufe..."
                )
                
                val serviceType = if (activeCall) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                
                startForeground(SERVICE_ID, notification, serviceType)
                Log.d(TAG, "Foreground service type updated to: ${if (activeCall) "MICROPHONE" else "DATA_SYNC"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update foreground service type: ${e.message}")
            }
        }
    }
    
    /**
     * ANDROID 14 FIX: Public method for AppActivity to request microphone access
     * BEFORE starting WebRTC. Prevents race condition where WebRTC tries to open mic
     * while service is still in DATA_SYNC mode.
     * 
     * Call this from AppActivity before createOffer() or createAnswer().
     */
    fun requestMicrophoneAccess() {
        Log.d(TAG, "📢 AppActivity requesting microphone access for WebRTC")
        updateForegroundServiceType(activeCall = true)
    }
    
    private fun stopForegroundService() {
        disconnectAgentNotifications()
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
    
    private fun showIncomingCallNotification(roomId: String, domain: String) {
        val notificationIntent = Intent(this, AppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("incoming_room_id", roomId)
            putExtra("incoming_domain", domain)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            roomId.hashCode(),
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
        notificationManager.notify(roomId.hashCode(), notification)
        
        Log.d(TAG, "Incoming call notification shown for room: $roomId")
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "2bro4Call::CallService"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 Minuten Timeout
            }
            Log.d(TAG, "Wake lock acquired (10min timeout)")
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
    
    private fun connectAgentNotifications(token: String) {
        try {
            if (agentNotificationsClient == null) {
                // AgentNotificationListener implementiert von CallService - Events werden weitergeleitet
                agentNotificationsClient = AgentNotificationsClient(
                    this,  // this = CallService implements AgentNotificationListener
                    "call-server.netdoc64.workers.dev",
                    token
                )
            }
            agentNotificationsClient?.connect()
            Log.d(TAG, "Connecting to Agent Notifications WebSocket")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect Agent Notifications", e)
            updateServiceNotification("Verbindungsfehler", "Versuche erneut...")
        }
    }
    
    private fun disconnectAgentNotifications() {
        val client = agentNotificationsClient
        if (client != null) {
            client.disconnect()
            agentNotificationsClient = null
        }
        isConnected = false
        onConnectionStateChanged?.invoke(false)
        Log.d(TAG, "Agent Notifications disconnected")
    }
    
    private fun updateServiceNotification(title: String, text: String) {
        val notification = createServiceNotification(title, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(SERVICE_ID, notification)
    }
    
    // AgentNotificationListener Interface Implementation
    override fun onConnected() {
        isConnected = true
        onConnectionStateChanged?.invoke(true)
        // REDUNDANCY FIX: Notify Activity to STOP HTTP polling (WebSocket is active)
        onWebSocketStateChanged?.invoke(true)
        updateServiceNotification(
            "2bro4Call Verbunden ✅",
            "Bereit für eingehende Anrufe"
        )
        Log.d(TAG, "Agent Notifications connected - HTTP polling should stop")
    }
    
    override fun onNewCall(roomId: String, domainId: String, domainName: String, timestamp: Long) {
        Log.d(TAG, "📋 New call in queue: $roomId on $domainName (SILENT - updating queue list)")
        
        // CRITICAL FIX: Trigger Activity queue update via callback
        // This replaces inefficient HTTP polling with live WebSocket events
        onQueueUpdated?.invoke(roomId, domainId, domainName)
        
        // SILENT - no ringtone here! Only when call_ringing event arrives
    }
    
    override fun onCallRinging(roomId: String, initiator: String, timestamp: Long) {
        Log.d(TAG, "🔔 Call ringing: $roomId (initiator: $initiator)")
        
        // ANDROID 14 FIX: Switch to MICROPHONE type when call is ringing (about to use mic)
        updateForegroundServiceType(activeCall = true)
        
        // Extract domain from roomId (format: domain__uuid)
        val domain = roomId.split("__").firstOrNull() ?: "unbekannt"
        
        // Show incoming call notification with ringtone
        showIncomingCallNotification(roomId, domain)
        
        // Callback to Activity (falls gebunden) - DEPRECATED: Use onCallRingingCallback instead
        onCallReceived?.invoke(roomId, domain)
        
        // NEW: Forward to queue callback for UI updates (ringtone, dialog, etc.)
        onCallRingingCallback?.invoke(roomId, initiator)
        
        Log.d(TAG, "Incoming call notification shown for room: $roomId")
    }
    
    override fun onCallActive(roomId: String, domainId: String, agentId: String?, timestamp: Long) {
        Log.d(TAG, "Call active: $roomId (agent: ${agentId ?: "none"})")
        
        // ANDROID 14 FIX: Ensure MICROPHONE type is active during call
        updateForegroundServiceType(activeCall = true)
        
        // Forward to Activity callback for UI updates
        onCallActiveCallback?.invoke(roomId)
    }
    
    override fun onCallEnded(roomId: String, domainId: String, reason: String) {
        Log.d(TAG, "Call ended: $roomId (reason: $reason)")
        
        // ANDROID 14 FIX: Switch back to DATA_SYNC type when call ends
        updateForegroundServiceType(activeCall = false)
        
        // Forward to Activity callback for UI updates (remove from queue, cleanup)
        onCallEndedCallback?.invoke(roomId, reason)
    }
    
    override fun onDisconnected() {
        isConnected = false
        onConnectionStateChanged?.invoke(false)
        // REDUNDANCY FIX: Notify Activity to START HTTP polling (WebSocket fallback)
        onWebSocketStateChanged?.invoke(false)
        updateServiceNotification(
            "2bro4Call Getrennt ⚠️",
            "Versuche Wiederverbindung..."
        )
        Log.d(TAG, "Agent Notifications disconnected - HTTP polling should start as fallback")
    }
    
    override fun onError(message: String) {
        updateServiceNotification(
            "Fehler",
            message
        )
        Log.e(TAG, "Agent Notifications error: $message")
    }
    
    override fun onReconnectFailed() {
        // ZOMBIE SERVICE FIX: All reconnect attempts failed - reinitialize after long delay
        Log.e(TAG, "🚨 All reconnect attempts failed! Scheduling full reinitialization in 2 minutes...")
        
        updateServiceNotification(
            "2bro4Call - Verbindung verloren ❌",
            "Versuche in 2 Minuten erneut..."
        )
        
        // Disconnect current (dead) connection
        disconnectAgentNotifications()
        
        // Schedule reinitialization with exponential backoff (2 minutes)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val token = currentToken
            if (token != null && !isConnected) {
                Log.d(TAG, "🔄 Reinitializing Agent Notifications connection...")
                connectAgentNotifications(token)
            } else {
                Log.w(TAG, "Cannot reinitialize: token=${token != null}, isConnected=$isConnected")
            }
        }, 120000) // 2 minutes = 120000ms
    }
    
    // Public methods für Activity-Kommunikation
    fun isServiceConnected(): Boolean = isConnected
    
    fun reconnect() {
        currentToken?.let { token ->
            disconnectAgentNotifications()
            connectAgentNotifications(token)
        }
    }
}
