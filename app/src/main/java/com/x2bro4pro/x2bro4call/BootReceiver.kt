package com.x2bro4pro.bro4call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo

/**
 * BroadcastReceiver für Auto-Start nach Gerät-Neustart
 * Startet CallService automatisch wenn Token vorhanden ist
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    private fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        
        val packageName = context.packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                appProcess.processName == packageName) {
                return true
            }
        }
        return false
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
                
                // Start CallService NUR für FCM Push Notifications (kein WebSocket Room)
                // Agent connectet erst bei Chat/Anruf zu Visitor's Room
                val serviceIntent = Intent(context, CallService::class.java).apply {
                    action = CallService.ACTION_START_SERVICE
                    // KEIN EXTRA_ROOM_ID - Agent hat keinen festen Room!
                    putExtra(CallService.EXTRA_TOKEN, savedToken)
                }
                
                // CRITICAL FIX #9: Android 12+ requires app to be foreground OR use WorkManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        // Note: After boot, app is usually NOT in foreground, so this will likely fail on Android 12+
                        // Proper solution: Use WorkManager with expedited work request
                        val isAppInForeground = isAppInForeground(context)
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isAppInForeground) {
                            Log.w(TAG, "⚠️ Cannot start foreground service on Android 12+ while app is in background (boot). Use WorkManager instead.")
                            // TODO: Implement WorkManager for boot-time service start
                        } else {
                            context.startForegroundService(serviceIntent)
                            Log.d(TAG, "CallService started after boot (FCM only, no fixed room)")
                        }
                    } catch (e: IllegalStateException) {
                        // Android 12+ ForegroundServiceStartNotAllowedException
                        Log.e(TAG, "❌ ForegroundServiceStartNotAllowedException caught (Android 12+ boot restriction)", e)
                    }
                } else {
                    context.startService(serviceIntent)
                    Log.d(TAG, "CallService started after boot (FCM only, no fixed room)")
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