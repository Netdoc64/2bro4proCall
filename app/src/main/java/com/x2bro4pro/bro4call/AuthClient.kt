package com.x2bro4pro.bro4call

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class AuthClient(
    private val context: Context, 
    private val backendBaseUrl: String,
    private val errorReporter: ErrorReporter? = null // FIX: ErrorReporter
) {
    companion object {
        private const val MAX_RETRIES = 3
        
        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
        
        private const val PREF_FILE = "secure_prefs"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ROLES = "user_roles"
        private const val KEY_PERMISSIONS = "user_permissions"
        private const val KEY_DOMAINS = "allowed_domains"
        private const val KEY_ROOM_ID = "active_room_id"
    }
    
    private val baseUrl = backendBaseUrl
    private val prefs: SharedPreferences by lazy { createEncryptedPrefs() }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREF_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    interface LoginCallback {
        fun onSuccess(token: String, userId: String, displayName: String?, domains: List<String>)
        fun onFailure(message: String)
    }

    interface RegisterCallback {
        fun onSuccess(token: String, userId: String, displayName: String?, domains: List<String>)
        fun onFailure(message: String)
    }

    fun register(displayName: String?, email: String, password: String, cb: RegisterCallback) {
        val url = "$baseUrl/api/register"
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
            if (!displayName.isNullOrBlank()) put("displayName", displayName)
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb.onFailure("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        // Backend-Fehlermeldung extrahieren
                        val errorMsg = try {
                            val errorBody = it.body?.string() ?: ""
                            val errorJson = JSONObject(errorBody)
                            errorJson.optString("error", "Unbekannter Fehler")
                        } catch (e: Exception) {
                            "HTTP ${it.code}"
                        }
                        
                        // Spezifische Fehlermeldungen basierend auf Status-Code
                        val message = when (it.code) {
                            409 -> "❌ $errorMsg\n\nBitte verwende eine andere Email-Adresse."
                            else -> "Fehler (${it.code}): $errorMsg"
                        }
                        cb.onFailure(message)
                        return
                    }
                    val text = it.body?.string() ?: ""
                    try {
                        val responseJson = JSONObject(text) // Renamed to avoid shadowing outer 'json'
                        
                        // Check if registration needs approval (no token returned)
                        val message = responseJson.optString("message", "")
                        if (message.isNotEmpty() && !responseJson.has("token")) {
                            cb.onFailure("✅ $message")
                            return
                        }
                        
                        // Parse new API v2.2 response structure
                        val token = responseJson.optString("token", "")
                        val userObj = responseJson.optJSONObject("user")
                        if (token.isBlank() || userObj == null) {
                            cb.onFailure("Invalid response: missing token or user")
                            return
                        }
                        
                        val userId = userObj.optString("id", "")
                        val userEmail = userObj.optString("email", "") // Renamed to avoid shadowing parameter 'email'
                        val userDisplayName = userObj.optString("displayName").takeIf { it.isNotEmpty() } // Renamed to avoid shadowing parameter 'displayName'
                        val domainsArray = userObj.optJSONArray("allowedDomains")
                        val rolesArray = userObj.optJSONArray("roles")
                        val permissionsArray = userObj.optJSONArray("permissions")
                        
                        val domains = mutableListOf<String>()
                        if (domainsArray != null) {
                            for (i in 0 until domainsArray.length()) {
                                domains.add(domainsArray.optString(i))
                            }
                        }
                        
                        // Save all data
                        saveToken(token)
                        saveUserId(userId)
                        saveEmail(userEmail)
                        saveDisplayName(userDisplayName)
                        saveDomains(domains)
                        saveRoles(rolesArray)
                        savePermissions(permissionsArray)
                        
                        cb.onSuccess(token, userId, userDisplayName, domains)
                    } catch (e: Exception) {
                        cb.onFailure("Invalid response: ${e.message}")
                    }
                }
            }
        })
    }

    fun login(email: String, password: String, cb: LoginCallback) {
        val url = "$baseUrl/api/login"
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb.onFailure("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        // Backend-Fehlermeldung extrahieren
                        val errorMsg = try {
                            val errorBody = it.body?.string() ?: ""
                            val errorJson = JSONObject(errorBody)
                            errorJson.optString("error", "Unbekannter Fehler")
                        } catch (e: Exception) {
                            "HTTP ${it.code}"
                        }
                        
                        // Spezifische Fehlermeldungen basierend auf Status-Code
                        val message = when (it.code) {
                            401 -> "❌ $errorMsg"
                            403 -> "⏳ $errorMsg\n\nBitte warte auf die Freischaltung durch einen Administrator."
                            else -> "Fehler (${it.code}): $errorMsg"
                        }
                        cb.onFailure(message)
                        return
                    }
                    val text = it.body?.string() ?: ""
                    try {
                        val responseJson = JSONObject(text)
                        
                        // Parse new API v2.2 response structure
                        val token = responseJson.optString("token", "")
                        val userObj = responseJson.optJSONObject("user")
                        if (token.isBlank() || userObj == null) {
                            cb.onFailure("Login succeeded but invalid response")
                            return
                        }
                        
                        val userId = userObj.optString("id", "")
                        val userEmail = userObj.optString("email", "")
                        val userDisplayName = userObj.optString("displayName").takeIf { it.isNotEmpty() }
                        val domainsArray = userObj.optJSONArray("allowedDomains")
                        val rolesArray = userObj.optJSONArray("roles")
                        val permissionsArray = userObj.optJSONArray("permissions")
                        
                        val domains = mutableListOf<String>()
                        if (domainsArray != null) {
                            for (i in 0 until domainsArray.length()) {
                                domains.add(domainsArray.optString(i))
                            }
                        }
                        
                        // Save all data
                        saveToken(token)
                        saveUserId(userId)
                        saveEmail(userEmail)
                        saveDisplayName(userDisplayName)
                        saveDomains(domains)
                        saveRoles(rolesArray)
                        savePermissions(permissionsArray)
                        
                        cb.onSuccess(token, userId, userDisplayName, domains)
                    } catch (e: Exception) {
                        cb.onFailure("Invalid response: ${e.message}")
                    }
                }
            }
        })
    }

    private fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }
    
    private fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }
    
    private fun saveEmail(email: String) {
        prefs.edit().putString(KEY_USER_EMAIL, email).apply()
    }
    
    private fun saveDisplayName(displayName: String?) {
        if (displayName != null) {
            prefs.edit().putString(KEY_DISPLAY_NAME, displayName).apply()
        } else {
            prefs.edit().remove(KEY_DISPLAY_NAME).apply()
        }
    }

    private fun saveDomains(domains: List<String>) {
        prefs.edit().putString(KEY_DOMAINS, JSONObject().put("domains", domains).toString()).apply()
    }
    
    private fun saveRoles(rolesArray: org.json.JSONArray?) {
        if (rolesArray != null) {
            prefs.edit().putString(KEY_ROLES, rolesArray.toString()).apply()
        } else {
            prefs.edit().remove(KEY_ROLES).apply()
        }
    }
    
    private fun savePermissions(permissionsArray: org.json.JSONArray?) {
        if (permissionsArray != null) {
            prefs.edit().putString(KEY_PERMISSIONS, permissionsArray.toString()).apply()
        } else {
            prefs.edit().remove(KEY_PERMISSIONS).apply()
        }
    }

    fun getToken(): String? {
        val token = prefs.getString(KEY_TOKEN, null)
        return if (token != null && !isTokenExpired(token)) {
            token
        } else {
            if (token != null) {
                Log.w("AuthClient", "Token expired, clearing")
                clearToken()
            }
            null
        }
    }
    
    private fun isTokenExpired(token: String): Boolean {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return true
            
            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
            val json = JSONObject(payload)
            val exp = json.optLong("exp", 0)
            
            if (exp == 0L) return false // Kein Expiry-Claim
            
            val now = System.currentTimeMillis() / 1000
            exp < now
        } catch (e: Exception) {
            Log.e("AuthClient", "Failed to parse JWT: ${e.message}")
            true // Bei Parsing-Error als expired behandeln
        }
    }
    
    fun clearToken() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_ROLES)
            .remove(KEY_PERMISSIONS)
            .remove(KEY_DOMAINS)
            .apply()
    }

    fun getDomains(): List<String> {
        val text = prefs.getString(KEY_DOMAINS, null) ?: return emptyList()
        return try {
            val json = JSONObject(text)
            val arr = json.optJSONArray("domains") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            out
        } catch (e: Exception) {
            Log.e("AuthClient", "getDomains parse failed: ${e.message}")
            emptyList()
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
    
    // FCM Token Management
    interface FcmTokenCallback {
        fun onSuccess()
        fun onFailure(message: String)
    }
    
   // AuthClient.kt

fun sendFcmToken(fcmToken: String, cb: FcmTokenCallback) {
    // 1. Initialer Check
    val token = getToken()
    if (token.isNullOrBlank()) {
        cb.onFailure("Not logged in")
        return
    }

    // --- KORREKTUREN STARTEN HIER ---
    
    // KORREKTUR 1: URL an den korrekten Backend-Endpunkt anpassen.
    // Alter URL: val url = "$baseUrl/api/fcm-token"
    val url = "$baseUrl/api/register_device" 
    
    // KORREKTUR 2: JSON Payload an die Backend-Erwartungen anpassen (Snake Case und zusätzliche Felder).
    // Informationen von Android Build verwenden.
    val json = JSONObject().apply {
        // Backend erwartet fcm_token (snake_case)
        put("fcm_token", fcmToken) 
        // Füge erforderliche Geräteinformationen hinzu, um den DB-Eintrag zu vervollständigen
        put("device_type", "android")
        put("device_name", android.os.Build.MODEL)
        
        val appVersion = try {
            // HOLT die tatsächliche App-Version von Android (Zugriff auf Context erforderlich)
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }
        put("app_version", appVersion)
    }

    // --- KORREKTUREN ENDEN HIER ---

    val mediaType = "application/json; charset=utf-8".toMediaType()
    val body = json.toString().toRequestBody(mediaType)
    
    val req = Request.Builder()
        .url(url)
        .post(body)
        .header("Authorization", "Bearer $token")
        .build()
        
    client.newCall(req).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("AuthClient", "FCM token send failed: ${e.message}")
            cb.onFailure("Network error: ${e.message}")
        }
        
        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!it.isSuccessful) {
                    val errorMsg = try {
                        val errorBody = it.body?.string() ?: ""
                        val errorJson = JSONObject(errorBody)
                        
                        // KORREKTUR 3: Reportet den spezifischen Device-Fehler des Backends (z.B. 400 Bad Request)
                        errorJson.optString("error", "Unknown error")
                    } catch (e: Exception) {
                        "HTTP ${it.code}"
                    }
                    Log.e("AuthClient", "FCM token send failed: $errorMsg")
                    cb.onFailure(errorMsg)
                    return
                }
                Log.d("AuthClient", "FCM token sent successfully")
                cb.onSuccess()
            }
        }
    })
}
    
    fun saveRoomId(roomId: String) {
        prefs.edit().putString(KEY_ROOM_ID, roomId).apply()
    }
    
    fun getRoomId(): String? {
        return prefs.getString(KEY_ROOM_ID, null)
    }
    
    // Getter für neue User-Felder
    fun getUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }
    
    fun getEmail(): String? {
        return prefs.getString(KEY_USER_EMAIL, null)
    }
    
    fun getDisplayName(): String? {
        return prefs.getString(KEY_DISPLAY_NAME, null)
    }
    
    fun getRoles(): List<Map<String, Any>> {
        val rolesJson = prefs.getString(KEY_ROLES, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(rolesJson)
            val roles = mutableListOf<Map<String, Any>>()
            for (i in 0 until arr.length()) {
                val roleObj = arr.optJSONObject(i)
                if (roleObj != null) {
                    roles.add(mapOf(
                        "id" to roleObj.optString("id", ""),
                        "name" to roleObj.optString("name", ""),
                        "level" to roleObj.optInt("level", 0)
                    ))
                }
            }
            roles
        } catch (e: Exception) {
            Log.e("AuthClient", "getRoles parse failed: ${e.message}")
            emptyList()
        }
    }
    
    fun getPermissions(): List<String> {
        val permsJson = prefs.getString(KEY_PERMISSIONS, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(permsJson)
            val perms = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                perms.add(arr.optString(i))
            }
            perms
        } catch (e: Exception) {
            Log.e("AuthClient", "getPermissions parse failed: ${e.message}")
            emptyList()
        }
    }
    
    fun hasPermission(permission: String): Boolean {
        val perms = getPermissions()
        return perms.contains("*") || perms.contains(permission)
    }
}
