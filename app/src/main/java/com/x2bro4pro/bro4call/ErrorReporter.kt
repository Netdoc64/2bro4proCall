package com.x2bro4pro.bro4call

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Error Reporting Client für 2bro4Call
 * API v2.3 - Sendet Fehler an /api/errors/report
 * 
 * Unterstützt:
 * - Crash Reports (fatal)
 * - Network Errors
 * - WebRTC Errors
 * - Permission Errors
 * - UI Errors
 * 
 * Features:
 * - Anonymous reporting (kein Auth nötig)
 * - Optional: User-Verknüpfung mit JWT Token
 * - Automatic device info collection
 * - Stack trace capture (max 10k chars)
 */
class ErrorReporter(
    private val context: Context,
    private val backendBaseUrl: String,
    private val appVersion: String
) {
    
    companion object {
        private const val TAG = "ErrorReporter"
        private const val ENDPOINT = "/api/errors/report"
        private const val MAX_MESSAGE_LENGTH = 2000
        private const val MAX_STACKTRACE_LENGTH = 10000
        
        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
    
    /**
     * Error Types gemäß API v2.3
     */
    enum class ErrorType(val value: String) {
        CRASH("crash"),
        NETWORK("network"),
        WEBRTC("webrtc"),
        PERMISSION("permission"),
        UI("ui"),
        OTHER("other")
    }
    
    /**
     * Severity Levels gemäß API v2.3
     */
    enum class Severity(val value: String) {
        FATAL("fatal"),
        ERROR("error"),
        WARNING("warning"),
        INFO("info")
    }
    
    /**
     * Report an error to backend
     * 
     * @param errorType Type of error (crash, network, webrtc, permission, ui, other)
     * @param errorMessage Human-readable error description
     * @param throwable Optional exception for stack trace
     * @param severity Severity level (fatal, error, warning, info)
     * @param context Optional context info (screen, action, call_id, domain_id)
     * @param authToken Optional JWT token to link error to user
     */
    fun reportError(
        errorType: ErrorType,
        errorMessage: String,
        throwable: Throwable? = null,
        severity: Severity = Severity.ERROR,
        context: Map<String, String>? = null,
        authToken: String? = null
    ) {
        try {
            val url = "$backendBaseUrl$ENDPOINT"
            
            // Build JSON payload
            val json = JSONObject().apply {
                put("app_version", appVersion)
                put("platform", "android")
                put("device_info", getDeviceInfo())
                put("error_type", errorType.value)
                put("error_message", errorMessage.take(MAX_MESSAGE_LENGTH))
                put("severity", severity.value)
                
                // Stack trace (wenn vorhanden)
                throwable?.let {
                    val stackTrace = getStackTrace(it).take(MAX_STACKTRACE_LENGTH)
                    put("stack_trace", stackTrace)
                }
                
                // Context info (optional)
                context?.let {
                    val contextObj = JSONObject()
                    it.forEach { (key, value) -> contextObj.put(key, value) }
                    put("context", contextObj)
                }
            }
            
            // Log fatal errors immediately
            if (severity == Severity.FATAL) {
                Log.e(TAG, "🔴 FATAL ERROR: $errorMessage")
                throwable?.printStackTrace()
            }
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(body)
            
            // Add auth token if provided (links error to user)
            authToken?.let {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }
            
            val request = requestBuilder.build()
            
            // Asynchronous call
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Failed to report error to backend: ${e.message}")
                    // Fehler beim Reporting wird nur geloggt, nicht erneut reportet (Loop-Vermeidung)
                }
                
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            val responseBody = it.body?.string()
                            val reportId = try {
                                JSONObject(responseBody ?: "{}").optString("reportId", "unknown")
                            } catch (e: Exception) {
                                "unknown"
                            }
                            Log.d(TAG, "✅ Error reported successfully: $reportId")
                        } else {
                            Log.w(TAG, "Backend rejected error report: ${it.code}")
                        }
                    }
                }
            })
            
        } catch (e: Exception) {
            // Fehler beim Erstellen des Reports - nur loggen
            Log.e(TAG, "Failed to create error report", e)
        }
    }
    
    /**
     * Report a crash (convenience method)
     */
    fun reportCrash(
        message: String,
        throwable: Throwable,
        context: Map<String, String>? = null,
        authToken: String? = null
    ) {
        reportError(
            errorType = ErrorType.CRASH,
            errorMessage = message,
            throwable = throwable,
            severity = Severity.FATAL,
            context = context,
            authToken = authToken
        )
    }
    
    /**
     * Report a network error (convenience method)
     */
    fun reportNetworkError(
        message: String,
        throwable: Throwable? = null,
        context: Map<String, String>? = null,
        authToken: String? = null
    ) {
        reportError(
            errorType = ErrorType.NETWORK,
            errorMessage = message,
            throwable = throwable,
            severity = Severity.ERROR,
            context = context,
            authToken = authToken
        )
    }
    
    /**
     * Report a WebRTC error (convenience method)
     */
    fun reportWebRTCError(
        message: String,
        throwable: Throwable? = null,
        context: Map<String, String>? = null,
        authToken: String? = null
    ) {
        reportError(
            errorType = ErrorType.WEBRTC,
            errorMessage = message,
            throwable = throwable,
            severity = Severity.ERROR,
            context = context,
            authToken = authToken
        )
    }
    
    /**
     * Report a permission error (convenience method)
     */
    fun reportPermissionError(
        message: String,
        context: Map<String, String>? = null,
        authToken: String? = null
    ) {
        reportError(
            errorType = ErrorType.PERMISSION,
            errorMessage = message,
            throwable = null,
            severity = Severity.WARNING,
            context = context,
            authToken = authToken
        )
    }
    
    /**
     * Collect device information
     */
    private fun getDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("os_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("screen_size", getScreenSize())
            put("device_id", Build.DEVICE)
            put("brand", Build.BRAND)
            put("product", Build.PRODUCT)
        }
    }
    
    /**
     * Get screen size as string (e.g., "1080x1920")
     */
    private fun getScreenSize(): String {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Extract stack trace from throwable
     */
    private fun getStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }
}
