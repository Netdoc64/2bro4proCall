package com.x2bro4pro.bro4call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo

/**
 * BroadcastReceiver für Netzwerkänderungen
 * Triggert CallService Reconnect bei Netzwerkwechsel (WiFi ↔ Mobile)
 */
class NetworkChangeReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NetworkChangeReceiver"
        private var lastNetworkAvailable = false
        private var lastReconnectTime = 0L
        private const val RECONNECT_DEBOUNCE_MS = 10000L // 10 Sekunden für bessere Stabilität
        private var lastTransportType: Int? = null  // Track WiFi vs Mobile
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION ||
            intent.action == "android.net.conn.CONNECTIVITY_CHANGE") {
            
            val isNetworkAvailable = isNetworkAvailable(context)
            
            Log.d(TAG, "Network change detected. Available: $isNetworkAvailable (was: $lastNetworkAvailable)")
            
            // Nur bei Wechsel von offline → online reconnecten
            if (isNetworkAvailable && !lastNetworkAvailable) {
                // Debounce: Nicht öfter als alle 5 Sekunden reconnecten
                val now = System.currentTimeMillis()
                if (now - lastReconnectTime < RECONNECT_DEBOUNCE_MS) {
                    Log.d(TAG, "Reconnect debounced, too soon since last attempt")
                    lastNetworkAvailable = isNetworkAvailable
                    return
                }
                lastReconnectTime = now
                
                Log.d(TAG, "Network restored, triggering CallService reconnect")
                
                try {
                    // Check if user is logged in
                    val authClient = AuthClient(context, "https://call-server.netdoc64.workers.dev")
                    val savedToken = authClient.getToken()
                    
                    if (savedToken != null) {
                        // Restart CallService in FCM-only mode (no WebSocket room)
                        val serviceIntent = Intent(context, CallService::class.java).apply {
                            action = CallService.ACTION_START_SERVICE
                            // KEIN EXTRA_ROOM_ID - Agent hat keinen festen Room!
                            putExtra(CallService.EXTRA_TOKEN, savedToken)
                        }
                        
                        // CRITICAL FIX #9: Android 12+ requires app to be foreground OR use WorkManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            try {
                                // Check if app is in foreground (Android 12+ restriction)
                                val isAppInForeground = isAppInForeground(context)
                                
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isAppInForeground) {
                                    Log.w(TAG, "⚠️ Cannot start foreground service on Android 12+ while app is in background. Skipping reconnect.")
                                    // TODO: Use WorkManager for background service starts (proper solution)
                                } else {
                                    context.startForegroundService(serviceIntent)
                                    Log.d(TAG, "CallService restart triggered after network restore")
                                }
                            } catch (e: IllegalStateException) {
                                // Android 12+ ForegroundServiceStartNotAllowedException
                                Log.e(TAG, "❌ ForegroundServiceStartNotAllowedException caught (Android 12+ background restriction)", e)
                            }
                        } else {
                            context.startService(serviceIntent)
                            Log.d(TAG, "CallService restart triggered after network restore")
                        }
                    } else {
                        Log.d(TAG, "No saved credentials, skipping reconnect")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart service after network change", e)
                }
            }
            
            lastNetworkAvailable = isNetworkAvailable
        }
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
    
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            // Track Transport Type für besseres Reconnect-Handling
            val currentTransportType = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapabilities.TRANSPORT_ETHERNET
                else -> null
            }
            
            // Log nur bei Transport-Wechsel
            if (currentTransportType != lastTransportType && currentTransportType != null) {
                val typeString = when (currentTransportType) {
                    NetworkCapabilities.TRANSPORT_WIFI -> "WiFi"
                    NetworkCapabilities.TRANSPORT_CELLULAR -> "Mobile"
                    NetworkCapabilities.TRANSPORT_ETHERNET -> "Ethernet"
                    else -> "Unknown"
                }
                Log.d(TAG, "Network transport changed to: $typeString")
                lastTransportType = currentTransportType
            }
            
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }
}
