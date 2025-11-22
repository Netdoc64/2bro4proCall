package com.x2bro4pro.bro4call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

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
                    val savedRoomId = authClient.getRoomId()
                    
                    if (savedToken != null && savedRoomId != null) {
                        // Restart CallService to reconnect WebSocket
                        val serviceIntent = Intent(context, CallService::class.java).apply {
                            action = CallService.ACTION_START_SERVICE
                            putExtra(CallService.EXTRA_ROOM_ID, savedRoomId)
                            putExtra(CallService.EXTRA_TOKEN, savedToken)
                        }
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        
                        Log.d(TAG, "CallService restart triggered after network restore")
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
