package com.x2bro4pro.bro4call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging Service
 * Empfängt Push-Benachrichtigungen auch wenn App geschlossen ist
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {
    
    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "fcm_notifications"
    }
    
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        
        // Token an Backend senden
        sendTokenToBackend(token)
    }
    
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        
        Log.d(TAG, "Message received from: ${message.from}")
        
        // FIX: Process ONLY data payload to prevent duplicate notifications
        if (message.data.isNotEmpty()) {
            Log.d(TAG, "Message data: ${message.data}")
            
            val type = message.data["type"]
            when (type) {
                "incoming_call" -> {
                    val sessionId = message.data["sessionId"] ?: ""
                    val domain = message.data["domain"] ?: ""
                    val callerName = message.data["callerName"] ?: "Unbekannt"
                    
                    handleIncomingCall(sessionId, domain, callerName)
                }
                "call_ended" -> {
                    // Handle call ended notification
                    Log.d(TAG, "Call ended notification received")
                }
                else -> {
                    // Generic notification - only show if no notification payload exists
                    if (message.notification == null) {
                        showNotification("2bro4Call", "Neue Benachrichtigung")
                    }
                }
            }
        }
        
        // FIX: Only process notification payload if data payload was empty
        if (message.data.isEmpty()) {
            message.notification?.let { notification ->
                Log.d(TAG, "Message notification: ${notification.title} - ${notification.body}")
                showNotification(
                    notification.title ?: "2bro4Call",
                    notification.body ?: ""
                )
            }
        }
    }
    
    private fun handleIncomingCall(sessionId: String, domain: String, callerName: String) {
        Log.d(TAG, "Incoming call: session=$sessionId, domain=$domain, caller=$callerName")
        
        // Ensure CallService is running
        startCallServiceIfNeeded()
        
        // Show heads-up notification
        showIncomingCallNotification(sessionId, domain, callerName)
    }
    
    private fun startCallServiceIfNeeded() {
        try {
            val authClient = AuthClient(this, "https://call-server.netdoc64.workers.dev")
            val savedToken = authClient.getToken()
            
            if (savedToken != null) {
                // Start CallService in FCM-only mode (no WebSocket room)
                val serviceIntent = Intent(this, CallService::class.java).apply {
                    action = CallService.ACTION_START_SERVICE
                    // KEIN EXTRA_ROOM_ID - Agent hat keinen festen Room!
                    putExtra(CallService.EXTRA_TOKEN, savedToken)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                
                Log.d(TAG, "CallService started via FCM (no fixed room)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CallService", e)
        }
    }
    
    private fun showIncomingCallNotification(sessionId: String, domain: String, callerName: String) {
        // Ensure HIGH priority channel exists (CallService might not be running)
        ensureIncomingCallChannel()
        
        val intent = Intent(this, AppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("incoming_session_id", sessionId)
            putExtra("incoming_domain", domain)
            putExtra("incoming_caller_name", callerName)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(this, CallService.INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.app_logo)
            .setContentTitle("📞 Eingehender Anruf")
            .setContentText("$callerName von $domain")
            .setPriority(NotificationCompat.PRIORITY_MAX) // MAX instead of HIGH
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            .setFullScreenIntent(pendingIntent, true) // Critical: Full screen for locked devices
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(sessionId.hashCode(), notification)
        
        Log.d(TAG, "FCM Incoming call notification shown (HIGH priority): $sessionId")
    }
    
    private fun showNotification(title: String, body: String) {
        createNotificationChannel()
        
        val intent = Intent(this, AppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Push-Benachrichtigungen",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Allgemeine Benachrichtigungen"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Ensures HIGH priority channel for incoming calls exists
     * Critical: Must be called BEFORE showing call notification
     */
    private fun ensureIncomingCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val callChannel = NotificationChannel(
                CallService.INCOMING_CALL_CHANNEL_ID,
                "Eingehende Anrufe",
                NotificationManager.IMPORTANCE_HIGH // HIGH = with sound
            ).apply {
                description = "Benachrichtigungen für eingehende Anrufe mit Klingelton"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(callChannel)
            
            Log.d(TAG, "HIGH priority INCOMING_CALL_CHANNEL created/ensured")
        }
    }
    
    private fun sendTokenToBackend(token: String) {
        // Store token locally
        val prefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        
        // Note: Token is sent to backend when user logs in (via AppActivity.sendFcmTokenToBackend())
        Log.d(TAG, "FCM Token stored locally: $token")
    }
}
