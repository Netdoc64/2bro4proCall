package com.x2bro4pro.bro4call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver für Auto-Start nach Gerät-Neustart
 * Startet CallService automatisch wenn Token vorhanden ist
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, checking for saved credentials")
            
            try {
                // Check if user is logged in
                val authClient = AuthClient(context, "https://call-server.netdoc64.workers.dev")
                val savedToken = authClient.getToken()
                
                if (savedToken == null) {
                    Log.d(TAG, "No valid token found, skipping auto-start")
                    return
                }
                
                if (savedToken != null) {
                    // Try to load saved room ID first to maintain context
                    var roomId = authClient.getRoomId()
                    
                    if (roomId == null) {
                        // Fallback: generate new room ID if none exists
                        val domains = authClient.getDomains()
                        val domain = domains.firstOrNull() ?: "tarba_schlusseldienst"
                        val sessionId = java.util.UUID.randomUUID().toString()
                        roomId = "${domain}__${sessionId}"
                        authClient.saveRoomId(roomId)
                    }
                    
                    // Start CallService
                    val serviceIntent = Intent(context, CallService::class.java).apply {
                        action = CallService.ACTION_START_SERVICE
                        putExtra(CallService.EXTRA_ROOM_ID, roomId)
                        putExtra(CallService.EXTRA_TOKEN, savedToken)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    
                    Log.d(TAG, "CallService started after boot with room: $roomId")
                } else {
                    Log.d(TAG, "No saved credentials, skipping service start")
                }
                } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for service start after boot", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Service cannot be started in current state", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error starting service after boot", e)
            }
        }
    }
}