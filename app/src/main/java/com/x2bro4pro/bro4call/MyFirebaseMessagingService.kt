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
        
        // Check if message contains data payload
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
                    // Generic notification
                    message.notification?.let { notification ->
                        showNotification(
                            notification.title ?: "2bro4Call",
                            notification.body ?: ""
                        )
                    }
                }
            }
        }
        
        // Check if message contains notification payload
        message.notification?.let { notification ->
            Log.d(TAG, "Message notification: ${notification.title} - ${notification.body}")
            showNotification(
                notification.title ?: "2bro4Call",
                notification.body ?: ""
            )
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
                val domains = authClient.getDomains()
                val domain = domains.firstOrNull() ?: "tarba_schlusseldienst"
                val sessionId = java.util.UUID.randomUUID().toString()
                val roomId = "${domain}__${sessionId}"
                
                val serviceIntent = Intent(this, CallService::class.java).apply {
                    action = CallService.ACTION_START_SERVICE
                    putExtra(CallService.EXTRA_ROOM_ID, roomId)
                    putExtra(CallService.EXTRA_TOKEN, savedToken)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                
                Log.d(TAG, "CallService started via FCM")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CallService", e)
        }
    }
    
    private fun showIncomingCallNotification(sessionId: String, domain: String, callerName: String) {
        createNotificationChannel()
        
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
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_logo)
            .setContentTitle("📞 Eingehender Anruf")
            .setContentText("$callerName von $domain")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(sessionId.hashCode(), notification)
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
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen für eingehende Anrufe"
                enableVibration(true)
                enableLights(true)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun sendTokenToBackend(token: String) {
        // Store token locally
        val prefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        
        // TODO: Send to backend via AuthClient or API call
        // This should be done after user login
        Log.d(TAG, "FCM Token stored locally: $token")
    }
}
