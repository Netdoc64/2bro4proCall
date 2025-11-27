package com.x2bro4pro.bro4call

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ProgressBar
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.tasks.OnCompleteListener

// Data Class für einen aktiven Web-Besucher
data class Visitor(
    val sessionId: String,
    val domain: String,
    val callerName: String,
    val logoUrl: String?,
    val roomId: String? = null, // Room ID vom Backend (für Queue-based Calls)
    val timestamp: Long = System.currentTimeMillis()
)

// Data Class für aktive Calls (Supervisor Monitoring)
data class ActiveCall(
    val sessionId: String,
    val domainId: String,
    val agentId: String?,
    val startTime: Long,
    val status: String // "waiting", "active", "ended"
)

// Die AppActivity implementiert das SignalingListener Interface (aus SignalingClient.kt)
class AppActivity : AppCompatActivity(), SignalingListener {

    // UI Elemente (Angenommen, sie wurden in activity_app_layout.xml hinzugefügt)
    private lateinit var statusTextView: TextView
    private lateinit var liveVisitorsRecyclerView: RecyclerView
    private lateinit var connectButton: Button
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var adminButton: Button
    private lateinit var supervisorButton: Button
    private lateinit var activeCallLayout: View 
    private lateinit var callEndButton: Button 
    private lateinit var activeCallInfo: TextView
    private lateinit var chatInput: EditText
    private lateinit var chatSendButton: Button
    private lateinit var chatMessagesView: TextView
    private lateinit var connectionQualityView: TextView
    private lateinit var visitorCountBadge: TextView
    private lateinit var menuButton: Button
    private lateinit var userInfoBadge: TextView
    // NOTE: visitorDataTextView ist das alte Element, das wir hier nicht mehr explizit nutzen.

    // Bug #19 fix: Track popup window for cleanup
    private var currentPopupWindow: android.widget.PopupWindow? = null

    // Daten und Clients
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRtcClient: PeerConnectionClient
    private var currentRoom: String? = null
    private var currentToken: String? = null
    private var currentRole: String? = null
    private val liveVisitors = mutableListOf<Visitor>()
    private lateinit var visitorAdapter: VisitorAdapter
    
    // HTTP Call tracking for cleanup
    private val activeCalls = mutableListOf<okhttp3.Call>()
    
    // Queue Polling System
    private var queuePollingHandler: android.os.Handler? = null
    private var queuePollingRunnable: Runnable? = null
    private val QUEUE_POLL_INTERVAL_MS = 5000L // 5 Sekunden
    
    // Supervisor Monitoring
    private var isMonitoring = false
    private var monitoringRoomId: String? = null
    private var monitoringClient: SignalingClient? = null

    private var activeCallSessionId: String? = null
    private var isWebRTCActive: Boolean = false  // Track if WebRTC is running
    private var isOutgoingCall: Boolean = false  // Track if THIS agent initiated the call
    // NOTE: Diese Domain-ID muss mit der ID im Backend übereinstimmen!
    private val DOMAIN_ID = "tarba_schlusseldienst"  // Beispiel-Domain
    // Backend host (ohne scheme), aus Spezifikation
    private val BACKEND_HOST = "call-server.netdoc64.workers.dev"
    
    // Input-Sanitization für XSS-Prevention
    private fun sanitizeInput(input: String): String {
        return input.replace(Regex("[<>&\"']"), "")
            .trim()
            .take(200) // Max 200 Zeichen
    }
    
    // Für dynamische CallRoom-IDs: DOMAIN_ID__SESSION_ID
    private fun generateCallRoomId(domainId: String = DOMAIN_ID): String {
        val sessionId = java.util.UUID.randomUUID().toString()
        return "${domainId}__${sessionId}"
    }
    private lateinit var authClient: AuthClient
    private lateinit var errorReporter: ErrorReporter
    
    // Agent Notifications WebSocket Client
    private var notificationsClient: AgentNotificationsClient? = null
    private var currentUserId: String? = null
    
    // CallService Integration
    private var callService: CallService? = null
    private var isServiceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val serviceBinder = binder as CallService.CallServiceBinder
            callService = serviceBinder.getService()
            isServiceBound = true
            
            // Setup callbacks
            callService?.onCallReceived = { roomId, domain ->
                runOnUiThread {
                    Log.d("AppActivity", "🔔 Call ringing callback: room=$roomId, domain=$domain")
                    
                    // Trigger handleCallRinging (plays ringtone + shows dialog)
                    val message = JSONObject().apply {
                        put("type", "call_ringing")
                        put("room_id", roomId)
                        put("initiator", "visitor")
                    }
                    handleCallRinging(message)
                }
            }
            
            callService?.onConnectionStateChanged = { connected ->
                runOnUiThread {
                    updateConnectionUI(connected)
                }
            }
            
            Log.d("AppActivity", "CallService bound")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            callService = null
            isServiceBound = false
            Log.d("AppActivity", "CallService unbound")
        }
    }

    // --- WebRTC Setup ---
    companion object {
        @Volatile
        private var webRtcInitialized = false
        private val webRtcLock = Any()
        
        // Singleton OkHttpClient for all HTTP requests
        private val httpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
    
    // Helper: Safe UI update that checks lifecycle state
    private fun safeRunOnUiThread(action: () -> Unit) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread(action)
        }
    }
    
    private fun initializeWebRTC() {
        // Singleton Pattern - initialisiere nur einmal pro App-Lebensdauer
        synchronized(webRtcLock) {
            if (!webRtcInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions()
                )
                webRtcInitialized = true
            }
        }
        
        // Factory mit Audio Device Module und Constraints - auch in synchronized
        val factory = synchronized(webRtcLock) {
            val options = PeerConnectionFactory.Options()
            PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()
        }
        
        webRtcClient = PeerConnectionClient(factory, this)
    }

    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize ErrorReporter
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        errorReporter = ErrorReporter(
            context = applicationContext,
            backendBaseUrl = "https://$BACKEND_HOST",
            appVersion = appVersion
        )
        
        // Global crash handler: write to file AND report to backend
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Write to local file (für User-Debugging)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = "Timestamp: ${java.time.Instant.now()}\nThread: ${thread.name}\n" + sw.toString()
                val f = File(filesDir, "last_crash.log")
                f.writeText(text)
                
                // Report to backend (mit Auth-Token falls vorhanden)
                val token = try {
                    authClient.getToken()
                } catch (e: Exception) {
                    null
                }
                errorReporter.reportCrash(
                    message = "App crashed: ${throwable.message ?: "Unknown error"}",
                    throwable = throwable,
                    context = mapOf(
                        "thread" to thread.name,
                        "screen" to "AppActivity"
                    ),
                    authToken = token
                )
            } catch (e: Exception) {
                // ignore
            }
            // pass to original handler (may kill process)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        setContentView(R.layout.activity_app_layout_glass) 
        
        // 1. UI-Referenzen initialisieren (Muss mit XML IDs übereinstimmen)
        statusTextView = findViewById(R.id.status_text_view)
        connectButton = findViewById(R.id.connect_button)
        // Start disabled until clients are initialized and permission granted
        connectButton.isEnabled = false
        loginButton = findViewById(R.id.login_button)
        registerButton = findViewById(R.id.register_button)
        adminButton = findViewById(R.id.admin_button)
        supervisorButton = findViewById(R.id.supervisor_button)
        liveVisitorsRecyclerView = findViewById(R.id.live_visitors_recycler) 
        activeCallLayout = findViewById(R.id.active_call_layout)
        callEndButton = findViewById(R.id.call_end_button)
        activeCallInfo = findViewById(R.id.active_call_info)
        chatInput = findViewById(R.id.chat_input)
        chatSendButton = findViewById(R.id.chat_send_button)
        chatMessagesView = findViewById(R.id.chat_messages_view)
        connectionQualityView = findViewById(R.id.connection_quality_view)
        visitorCountBadge = findViewById(R.id.visitor_count_badge)
        menuButton = findViewById(R.id.menu_button)
        userInfoBadge = findViewById(R.id.user_info_badge)
        
        // Menu Button Listener
        menuButton.setOnClickListener { showMenuPopup() }
        
        // Enter-Taste zum Senden aktivieren
        chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendChatMessage()
                true
            } else false
        }

        // Auth-Buttons ausblenden (werden durch Header-Menu ersetzt)
        loginButton.visibility = View.GONE
        registerButton.visibility = View.GONE
        adminButton.visibility = View.GONE
        supervisorButton.visibility = View.GONE
        connectButton.visibility = View.GONE
        
        // 2. Adapter und RecyclerView
        visitorAdapter = VisitorAdapter(liveVisitors, this::generateOffer, this::enterChatRoom, this)
        liveVisitorsRecyclerView.layoutManager = LinearLayoutManager(this)
        liveVisitorsRecyclerView.adapter = visitorAdapter
        
        // 3. Event Listener
        callEndButton.setOnClickListener { endCall() }
        chatSendButton.setOnClickListener { sendChatMessage() }
        connectButton.setOnClickListener { performManualReconnect() }  // Set once here
        
        // 4. Ensure audio permission before initializing WebRTC and network clients
        ensureAudioPermissionThenInit()

        // If a crash log exists from previous run, show it to the user so they can copy/send it
        showCrashIfExists()
        
        // Check battery optimization on startup
        checkBatteryOptimization()
    }

    private fun showCrashIfExists() {
        try {
            val f = File(filesDir, "last_crash.log")
            if (f.exists()) {
                val text = f.readText()
                AlertDialog.Builder(this)
                    .setTitle("Letzter Crash-Log gefunden")
                    .setMessage(text.take(4000))
                    .setPositiveButton("Kopieren") { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("crash_log", text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Log kopiert", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Löschen") { _, _ ->
                        f.delete()
                        Toast.makeText(this, "Log gelöscht", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Schließen", null)
                    .show()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    // Request code for audio permission
    private val REQ_RECORD_AUDIO = 1001
    private val REQ_POST_NOTIFICATIONS = 1002

    private fun ensureAudioPermissionThenInit() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startClientsAndAutoConnect()
        } else {
            // Request runtime permission
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        }
    }

    // Called after permission dialog
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Audio permission granted", Toast.LENGTH_SHORT).show()
                requestNotificationPermission()
                startClientsAndAutoConnect()
            } else {
                Toast.makeText(this, "Audio permission is required for calls", Toast.LENGTH_LONG).show()
                // Disable call/connect UI to prevent errors
                findViewById<Button>(R.id.connect_button).isEnabled = false
                
                // Report permission denial
                errorReporter.reportPermissionError(
                    message = "RECORD_AUDIO permission denied by user",
                    context = mapOf(
                        "permission" to "RECORD_AUDIO",
                        "screen" to "AppActivity"
                    ),
                    authToken = null // User not logged in yet
                )
            }
        } else if (requestCode == REQ_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Benachrichtigungen aktiviert", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Benachrichtigungen deaktiviert. Sie werden keine Anruf-Benachrichtigungen erhalten.", Toast.LENGTH_LONG).show()
                
                // Report notification permission denial (warning level)
                errorReporter.reportPermissionError(
                    message = "POST_NOTIFICATIONS permission denied - user won't receive call alerts",
                    context = mapOf(
                        "permission" to "POST_NOTIFICATIONS",
                        "screen" to "AppActivity",
                        "impact" to "missed_calls"
                    ),
                    authToken = null
                )
            }
        }
    }
    
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
            }
        }
    }

    // Move existing client initialization into its own method so we can call it after permission granted
    private fun startClientsAndAutoConnect() {
        initializeWebRTC()
        authClient = AuthClient(this, "https://$BACKEND_HOST")
        signalingClient = SignalingClient(this, BACKEND_HOST, errorReporter)
        // Auto-connect if token exists
        val savedToken = authClient.getToken()
        Log.d("AppActivity", "🔍 [DEBUG] Auto-Login Check: token=${if (savedToken != null) "EXISTS" else "NULL"}")
        
        if (savedToken != null) {
            val displayName = authClient.getDisplayName()
            currentToken = savedToken
            // Safer role extraction with fallback
           currentRole = authClient.getRoles().firstOrNull()?.let { roleMap ->
    (roleMap["name"] as? String) ?: "Unknown"  // ✅ Safe cast with fallback
}
            
            Log.d("AppActivity", "🔍 [DEBUG] Auto-Login: displayName=$displayName, role=$currentRole")
            
            statusTextView.text = "Status: ✅ Auto-Login${if (displayName != null) " - $displayName" else ""}"
            updateRoleBasedUI(currentRole)
            
            // Agent startet Queue-Polling statt festen Room
            startQueuePolling()
            
            // Service starten bei Auto-Login (nur FCM, kein fester Room)
            startCallServiceForFcm(savedToken)
        } else {
            // prompt login
            statusTextView.text = "Status: Bitte anmelden"
            performLoginUI()
        }
        // forward local ICE candidates to signaling worker
        webRtcClient.onIceCandidateCallback = { candidate ->
            val candidateJson = JSONObject().apply {
                put("type", "ice")
                put("data", JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                })
                put("targetSessionId", activeCallSessionId ?: JSONObject.NULL)
            }
            if (!signalingClient.send(candidateJson)) {
                Log.e("AppActivity", "Failed to send ICE candidate - WebSocket not connected")
            }
        }

        // enable connect button now that clients are ready
        connectButton.isEnabled = true
        showVisitorsTab()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Stop queue polling (Bug #17)
        stopQueuePolling()
        
        // Stop ringtone and release MediaPlayer (Bug #18)
        stopRingtone()
        
        // Release notification player
        notificationPlayer?.release()
        notificationPlayer = null
        
        // Dismiss popup if showing (Bug #19)
        currentPopupWindow?.dismiss()
        currentPopupWindow = null
        
        // Clear service callbacks to prevent memory leaks
        callService?.onCallReceived = null
        callService?.onConnectionStateChanged = null
        
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        
        // Cleanup monitoring client to prevent memory leak
        monitoringClient?.disconnect()
        monitoringClient = null
        
        // Cleanup notifications client (Bug Fix: WebSocket)
        notificationsClient?.disconnect()
        notificationsClient = null
        
        // Cancel all active HTTP calls to prevent callbacks after destroy
        activeCalls.forEach { it.cancel() }
        activeCalls.clear()
        
        signalingClient.disconnect()
        webRtcClient.close()
    }

    private fun validateAndAutoConnect(token: String) {
        currentToken = token
        val userId = authClient.getUserId()
        val displayName = authClient.getDisplayName()
        val domains = authClient.getDomains()
        // Safer role extraction with fallback
        currentRole = authClient.getRoles().firstOrNull()?.let { roleMap ->
    (roleMap["name"] as? String) ?: "Unknown"  // ✅ Safe cast with fallback
}
        
        // Log successful auto-connect for analytics
        if (userId != null) {
            Log.i("AppActivity", "Auto-connect successful for user: $userId (${displayName ?: "no name"})")
        }
        
        statusTextView.text = "Status: Auto-Login erfolgreich${if (displayName != null) " - $displayName" else ""}"
        
        // Update user info badge with ID for debugging
        if (userId != null && displayName != null) {
            userInfoBadge.text = "$displayName\n(ID: ${userId.take(8)}...)"
        } else if (displayName != null) {
            userInfoBadge.text = displayName
        }
        
        // Zeige Admin/Supervisor Buttons basierend auf Rolle
        updateRoleBasedUI(currentRole)
        
        // Domain-Auswahl und Verbindung
        if (domains.isNotEmpty()) {
            // Agent: Starte Queue-Polling statt festen Room
            startQueuePolling()
        }
        
        // CallService starten für Hintergrund-Anrufe (nur FCM, kein fester Room)
        startCallServiceForFcm(token)
    }
    
    private fun performLoginUI() {
        val emailInput = EditText(this).apply { 
            hint = "Email"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        
        val rememberCheckbox = android.widget.CheckBox(this).apply {
            text = "Angemeldet bleiben"
            isChecked = true
            setPadding(8, 16, 8, 8)
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(emailInput)
            addView(passInput)
            addView(rememberCheckbox)
        }
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("🔐 Agent Login")
            .setView(layout)
            .setPositiveButton("Login", null) // Set to null initially
            .setNeutralButton("Registrieren") { dlg, _ ->
                dlg.dismiss()
                performRegisterUI()
            }
            .setNegativeButton("Abbrechen", null)
            .create()
        
        dialog.setOnShowListener {
            val loginBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            
            // Handle Enter key in password field
            passInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    loginBtn.performClick()
                    true
                } else false
            }
            
            loginBtn.setOnClickListener {
                val email = sanitizeInput(emailInput.text.toString())
                val pass = passInput.text.toString()
                
                // Validierung
                if (email.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, "❌ Bitte Email und Passwort eingeben", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(this, "❌ Ungültige Email-Adresse", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // Disable button and show loading
                loginBtn.isEnabled = false
                loginBtn.text = "⏳ Login läuft..."
                statusTextView.text = "Status: Authentifiziere..."
                
                authClient.login(email, pass, object : AuthClient.LoginCallback {
                    override fun onSuccess(token: String, userId: String, displayName: String?, domains: List<String>) {
                        runOnUiThread {
                            dialog.dismiss()
                            
                            statusTextView.text = "Status: ✅ Login erfolgreich${if (displayName != null) " - $displayName" else ""}"
                            currentToken = token
                            currentUserId = userId
                            // Safer role extraction with fallback
                            currentRole = authClient.getRoles().firstOrNull()?.let { roleMap ->
    (roleMap["name"] as? String) ?: "Unknown"  // ✅ Safe cast with fallback
}
                            
                            Toast.makeText(this@AppActivity, "✅ Angemeldet${if (rememberCheckbox.isChecked) " - gespeichert" else ""}", Toast.LENGTH_SHORT).show()
                            
                            updateRoleBasedUI(currentRole)
                            
                            // Agent erstellt KEINEN Room - nur Queue-Polling starten
                            statusTextView.text = "Status: ✅ Eingeloggt - warte auf Anrufe..."
                            startQueuePolling()
                            
                            // CRITICAL FIX: Initialize Realtime WebSocket Notifications
                            initializeNotifications(token)
                            
                            // CallService starten (nur für FCM Push Notifications, KEIN WebSocket Room)
                            // Agent connectet erst bei Chat/Anruf zu Visitor's Room
                            startCallServiceForFcm(token)
                        }
                    }

                    override fun onFailure(message: String) {
                        runOnUiThread {
                            // Re-enable button
                            loginBtn.isEnabled = true
                            loginBtn.text = "Login"
                            
                            statusTextView.text = "Status: ❌ Login fehlgeschlagen"
                            
                            Toast.makeText(this@AppActivity, "❌ $message", Toast.LENGTH_LONG).show()
                            
                            // Bei kritischem Fehler Dialog schließen
                            if (message.contains("nicht freigegeben") || message.contains("blocked")) {
                                dialog.dismiss()
                                AlertDialog.Builder(this@AppActivity)
                                    .setTitle("⚠️ Login Fehler")
                                    .setMessage(message)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                })
            }
        }
        
        dialog.show()
    }

    private fun performRegisterUI() {
            val nameInput = EditText(this).apply { 
                hint = "Name (optional)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            val emailInput = EditText(this).apply { 
                hint = "Email"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            val passInput = EditText(this).apply {
                hint = "Password (min. 6 Zeichen)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 0)
                addView(nameInput)
                addView(emailInput)
                addView(passInput)
            }
            
            val dialog = AlertDialog.Builder(this)
                .setTitle("✨ Agent Registrierung")
                .setView(layout)
                .setPositiveButton("Registrieren", null)
                .setNegativeButton("Abbrechen", null)
                .create()
            
            dialog.setOnShowListener {
                val registerBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                
                // Handle Enter key in password field
                passInput.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        registerBtn.performClick()
                        true
                    } else false
                }
                
                registerBtn.setOnClickListener {
                    val name = sanitizeInput(nameInput.text.toString()).takeIf { it.isNotBlank() }
                    val email = sanitizeInput(emailInput.text.toString())
                    val pass = passInput.text.toString()
                    
                    // Validierung
                    if (email.isBlank() || pass.isBlank()) {
                        Toast.makeText(this, "❌ Bitte Email und Passwort eingeben", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        Toast.makeText(this, "❌ Ungültige Email-Adresse", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    if (pass.length < 6) {
                        Toast.makeText(this, "❌ Passwort muss mindestens 6 Zeichen haben", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    // Disable button and show loading
                    registerBtn.isEnabled = false
                    registerBtn.text = "⏳ Registriere..."
                    statusTextView.text = "Status: Registriere..."
                    
                    authClient.register(name, email, pass, object : AuthClient.RegisterCallback {
                        override fun onSuccess(token: String, userId: String, displayName: String?, domains: List<String>) {
                            runOnUiThread {
                                dialog.dismiss()
                                
                                statusTextView.text = "Status: ✅ Registrierung erfolgreich${if (displayName != null) " - $displayName" else ""}"
                                currentToken = token
                                // Safer role extraction with fallback
                                currentRole = authClient.getRoles().firstOrNull()?.let { roleMap ->
    (roleMap["name"] as? String) ?: "Unknown"  // ✅ Safe cast with fallback
}
                                updateRoleBasedUI(currentRole)
                                
                                Toast.makeText(this@AppActivity, "✅ Account erstellt und angemeldet", Toast.LENGTH_SHORT).show()
                                
                                // Agent: Queue-Polling starten statt Room erstellen
                                startQueuePolling()
                                startCallServiceForFcm(token)
                            }
                        }

                        override fun onFailure(message: String) {
                            runOnUiThread {
                                // Re-enable button
                                registerBtn.isEnabled = true
                                registerBtn.text = "Registrieren"
                                
                                // Bei Erfolg (✅) zeige andere Meldung
                                val isSuccess = message.startsWith("✅")
                                statusTextView.text = if (isSuccess) "Status: ✅ Registrierung erfolgreich" else "Status: ❌ Registrierung fehlgeschlagen"
                                
                                if (isSuccess) {
                                    dialog.dismiss()
                                    AlertDialog.Builder(this@AppActivity)
                                        .setTitle("✅ Registrierung erfolgreich")
                                        .setMessage(message)
                                        .setPositiveButton("Login") { _, _ ->
                                            performLoginUI()
                                        }
                                        .show()
                                } else {
                                    Toast.makeText(this@AppActivity, "❌ $message", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    })
                }
            }
            
            dialog.show()
        }

    // DEPRECATED: Agent erstellt keinen festen Room mehr
    private fun showDomainSelectionAndConnect(domains: List<String>, token: String) {
        Log.w("AppActivity", "showDomainSelectionAndConnect is deprecated - Agent uses queue-polling")
        Log.w("AppActivity", "Legacy call attempted with ${domains.size} domains, token length: ${token.length}")
        
        // Log domains for debugging migration issues
        if (domains.isNotEmpty()) {
            Log.d("AppActivity", "Available domains: ${domains.joinToString(", ")}")
        }
        
        // Agent: Queue-Polling wurde bereits gestartet
        statusTextView.text = "Status: ✅ Angemeldet - warte auf Anrufe"
    }

    // --- UI Management ---
    private fun showVisitorsTab() {
        liveVisitorsRecyclerView.visibility = View.VISIBLE
        activeCallLayout.visibility = View.GONE
        // Note: Connection UI wird durch WebSocket callbacks gemanaged (onWebSocketOpen, etc.)
    }
    
    private fun showActiveCallTab(visitor: Visitor) {
        liveVisitorsRecyclerView.visibility = View.GONE
        activeCallLayout.visibility = View.VISIBLE
        updateConnectionUI(isConnected = true)
        activeCallInfo.text = "Im Gespräch mit ${visitor.callerName} von ${visitor.domain}"
        
        // CRITICAL FIX: Update button text based on WebRTC state
        if (isWebRTCActive) {
            callEndButton.text = "🔴 AUFLEGEN"
        } else {
            callEndButton.text = "← ZURÜCK"
        }
    }

    // --- Signaling Listener Implementierung ---

    override fun onWebSocketOpen() {
        safeRunOnUiThread { 
            statusTextView.text = "Status: ✅ Verbunden"
            updateConnectionUI(isConnected = true)
            connectionQualityView.visibility = View.VISIBLE
            updateConnectionQuality("excellent")
        }
    }

    override fun onReconnecting(attempt: Int, delayMs: Int) {
        safeRunOnUiThread {
            statusTextView.text = "Status: Verbindung verloren — reconnect Versuch $attempt in ${delayMs}ms"
            val pb = findViewById<ProgressBar>(R.id.reconnect_progress)
            pb.visibility = View.VISIBLE
            updateConnectionUI(isConnected = false)
        }
    }

    override fun onReconnectFailed() {
        safeRunOnUiThread {
            statusTextView.text = "Status: Verbindung konnte nicht wiederhergestellt werden"
            val pb = findViewById<ProgressBar>(R.id.reconnect_progress)
            pb.visibility = View.GONE
            updateConnectionUI(isConnected = false)
            Toast.makeText(this, "Automatischer Reconnect fehlgeschlagen", Toast.LENGTH_LONG).show()
        }
    }

    override fun onWebSocketClosed() {
        safeRunOnUiThread { 
            statusTextView.text = "Status: Getrennt (Versuche Reconnect...)"
            updateConnectionUI(isConnected = false)
        }
    }

    override fun onError(message: String) {
        safeRunOnUiThread {
            Toast.makeText(this, "WS Error: $message", Toast.LENGTH_LONG).show()
            val pb = findViewById<ProgressBar>(R.id.reconnect_progress)
            pb.visibility = View.GONE
            connectButton.isEnabled = true
        }
    }

    override fun onNewSignalReceived(message: JSONObject) {
        safeRunOnUiThread {
            val type = message.optString("type", "")
            if (type.isEmpty()) {
                Log.w("AppActivity", "Received message without type: $message")
                return@safeRunOnUiThread
            }
            
            when (type) {
                "identify" -> handleNewVisitor(message) 
                "system" -> handleSystemMessage(message)
                "offer" -> handleIncomingOffer(message) 
                "answer" -> {
                    // Support both new 'data' format and old 'sdp' format
                    val sdpData = message.optJSONObject("data") ?: message.optJSONObject("sdp")
                    if (sdpData != null) {
                        webRtcClient.handleAnswer(message) // Pass full message, handleAnswer will parse
                    } else {
                        Log.e("AppActivity", "Answer without data/sdp object")
                    }
                }
                "ice", "candidate" -> {
                    // Support both 'ice' (new) and 'candidate' (deprecated) types
                    val candidateData = message.optJSONObject("data") ?: message.optJSONObject("candidate")
                    if (candidateData != null) {
                        webRtcClient.handleIceCandidate(message) // Pass full message, handleIceCandidate will parse
                    } else {
                        Log.e("AppActivity", "ICE candidate without data/candidate object")
                    }
                }
                "chat" -> handleChatMessage(message)
                "call_ringing" -> handleCallRinging(message)
                else -> Log.w("AppActivity", "Unknown message type: $type")
            }
        }
    }
    
    private fun handleSystemMessage(message: JSONObject) {
        if (message.optString("action") == "peer_left" && message.optString("role") == "visitor") {
            val sessionId = message.optString("sessionId") 
            handleVisitorLeft(message)
            if (activeCallSessionId == sessionId) endCall() 
        }
    }

    // --- Besucherlisten Logik ---

    private fun handleNewVisitor(message: JSONObject) {
        val sessionId = message.optString("sessionId", "") 
        if (sessionId.isEmpty() || liveVisitors.any { it.sessionId == sessionId }) return

        val newVisitor = Visitor(
            sessionId = sessionId,
            domain = message.optString("domain", "N/A"),
            callerName = "Besucher von ${message.optString("domain", "N/A")}",
            logoUrl = message.optString("profileImage"),
            roomId = message.optString("roomId", sessionId) // roomId vom Backend oder sessionId als Fallback
        )
        liveVisitors.add(newVisitor)
        visitorAdapter.notifyItemInserted(liveVisitors.size - 1)
        statusTextView.text = "Status: ${liveVisitors.size} Live-Besucher"
        visitorCountBadge.text = liveVisitors.size.toString()
    }

    private fun handleVisitorLeft(message: JSONObject) {
        val sessionId = message.optString("sessionId", "")
        val index = liveVisitors.indexOfFirst { it.sessionId == sessionId }
        if (index != -1) {
            liveVisitors.removeAt(index)
            visitorAdapter.notifyItemRemoved(index)
            statusTextView.text = "Status: ${liveVisitors.size} Live-Besucher"
            visitorCountBadge.text = liveVisitors.size.toString()
        }
    }

    // --- Anruf Logik ---
    
    // Agent betritt Chat-Room (nur WebSocket, KEIN WebRTC)
    private fun enterChatRoom(visitor: Visitor) {
        // CRITICAL FIX: Check if already in call
        if (activeCallSessionId != null) {
            AlertDialog.Builder(this)
                .setTitle("Bereits im Gespräch")
                .setMessage("Möchten Sie das aktuelle Gespräch beenden?")
                .setPositiveButton("Ja") { _, _ ->
                    endCall()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        enterChatRoom(visitor)
                    }, 500)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        
        // Erst WebSocket verbinden, KEIN WebRTC
        val roomId = visitor.roomId ?: visitor.sessionId
        val token = currentToken ?: run {
            Toast.makeText(this, "Fehler: Kein Token", Toast.LENGTH_SHORT).show()
            return
        }
        
        // State changes AFTER validation
        activeCallSessionId = visitor.sessionId
        isWebRTCActive = false  // CRITICAL: Chat only, no WebRTC yet
        isOutgoingCall = false
        
        // Show UI with chat but WITHOUT call controls
        liveVisitorsRecyclerView.visibility = View.GONE
        activeCallLayout.visibility = View.VISIBLE
        updateConnectionUI(isConnected = true)
        activeCallInfo.text = "💬 Chat mit ${visitor.callerName} - Klicke Anrufen für Audio"
        
        // CRITICAL FIX: Change button to "Back" instead of "End Call" in chat-only mode
        callEndButton.text = "← ZURÜCK"
        
        Log.d("AppActivity", "Entering chat room: $roomId (NO WebRTC)")
        currentRoom = roomId
        signalingClient.connect(roomId, token)
        
        Toast.makeText(this, "💬 Chat aktiv - Nur Text, kein Audio", Toast.LENGTH_SHORT).show()
    }
    
    // Handle call_ringing event from Notificator WebSocket
    private fun handleCallRinging(message: JSONObject) {
        val roomId = message.optString("room_id", "")
        val initiator = message.optString("initiator", "unknown")
        
        Log.d("AppActivity", "Call ringing: room=$roomId, initiator=$initiator")
        
        safeRunOnUiThread {
            // Play ringtone
            playRingtone()
            
            // Update status
            statusTextView.text = "Status: 📞 Eingehender Anruf..."
            
            // Find visitor in list
            val visitor = liveVisitors.find { it.roomId == roomId || it.sessionId == roomId }
            
            if (visitor == null) {
                // CRITICAL FIX: Visitor not in list - might be from FCM or queue not yet loaded
                Log.w("AppActivity", "Visitor not found in list for room: $roomId")
                stopRingtone()
                
                // Create placeholder visitor for the call
                val placeholderVisitor = Visitor(
                    sessionId = roomId,
                    domain = roomId.split("__").firstOrNull() ?: "Unbekannt",
                    callerName = "Eingehender Anruf",
                    logoUrl = null,
                    roomId = roomId
                )
                
                // Add to list
                liveVisitors.add(0, placeholderVisitor)
                visitorAdapter.notifyItemInserted(0)
                
                // Show dialog with placeholder
                showIncomingCallDialog(placeholderVisitor, roomId)
                return@safeRunOnUiThread
            }
            
            // Show incoming call dialog
            showIncomingCallDialog(visitor, roomId)
        }
    }
    
    private fun showIncomingCallDialog(visitor: Visitor, roomId: String) {
        val callerName = visitor.callerName
        
        playRingtone()
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("📞 Eingehender Anruf")
            .setMessage("$callerName möchte Sie anrufen")
            .setPositiveButton("Annehmen") { _, _ ->
                stopRingtone()
                acceptIncomingCall(visitor)
            }
            .setNegativeButton("Ablehnen") { _, _ ->
                stopRingtone()
                // Send decline signal
                val declineMsg = JSONObject().apply {
                    put("type", "call_declined")
                    put("roomId", roomId)
                }
                if (!signalingClient.send(declineMsg)) {
                    Log.w("AppActivity", "Failed to send call_declined - WebSocket not connected")
                }
            }
            .setCancelable(false)
            .create()
        
        // FIX: Always stop ringtone when dialog is dismissed (e.g., back button, external close)
        dialog.setOnDismissListener {
            stopRingtone()
        }
        
        dialog.show()
    }
    
    // Agent akzeptiert eingehenden Anruf (bereitet WebRTC vor)
    private fun acceptIncomingCall(visitor: Visitor) {
        // CRITICAL FIX: Check if already in call
        if (activeCallSessionId != null && activeCallSessionId != visitor.sessionId) {
            AlertDialog.Builder(this)
                .setTitle("Bereits im Gespräch")
                .setMessage("Möchten Sie das aktuelle Gespräch beenden?")
                .setPositiveButton("Ja") { _, _ ->
                    endCall()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        acceptIncomingCall(visitor)
                    }, 500)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        
        // Zum Room verbinden und auf WebRTC Offer warten
        val roomId = visitor.roomId ?: visitor.sessionId
        val token = currentToken ?: run {
            Toast.makeText(this, "Fehler: Kein Token", Toast.LENGTH_SHORT).show()
            return
        }
        
        // State changes AFTER validation
        activeCallSessionId = visitor.sessionId
        isWebRTCActive = true   // WebRTC will be active
        isOutgoingCall = false  // This is INCOMING call
        showActiveCallTab(visitor)
        
        Log.d("AppActivity", "Accepting incoming call from room: $roomId")
        currentRoom = roomId
        signalingClient.connect(roomId, token)
        
        activeCallInfo.text = "Eingehender Anruf von ${visitor.callerName}..."
        Toast.makeText(this, "📞 Warte auf Verbindung...", Toast.LENGTH_SHORT).show()
        
        // WebRTC wird automatisch durch handleIncomingOffer() initialisiert
    }
    
    private var mediaPlayer: android.media.MediaPlayer? = null
    
    private fun playRingtone() {
        try {
            stopRingtone() // Stop existing ringtone
            
            // Use default ringtone
            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(this@AppActivity, ringtoneUri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            
            Log.d("AppActivity", "Ringtone started")
        } catch (e: Exception) {
            Log.e("AppActivity", "Failed to play ringtone: ${e.message}")
        }
    }
    
    private fun stopRingtone() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
            Log.d("AppActivity", "Ringtone stopped")
        } catch (e: Exception) {
            Log.e("AppActivity", "Failed to stop ringtone: ${e.message}")
        }
    }
    
    // Agent verbindet zu Visitor's Room und initiiert Anruf
    private fun generateOffer(visitor: Visitor) {
        // CRITICAL FIX: Check if already in call
        if (activeCallSessionId != null && activeCallSessionId != visitor.sessionId) {
            AlertDialog.Builder(this)
                .setTitle("Bereits im Gespräch")
                .setMessage("Möchten Sie das aktuelle Gespräch beenden?")
                .setPositiveButton("Ja") { _, _ ->
                    endCall()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        generateOffer(visitor)
                    }, 500)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        
        // Erst zum Visitor's Room verbinden
        val roomId = visitor.roomId ?: visitor.sessionId
        val token = currentToken ?: run {
            Toast.makeText(this, "Fehler: Kein Token", Toast.LENGTH_SHORT).show()
            return
        }
        
        // State changes AFTER validation
        activeCallSessionId = visitor.sessionId
        isWebRTCActive = true   // CRITICAL: WebRTC will be started
        isOutgoingCall = true   // CRITICAL: THIS agent is calling OUT
        showActiveCallTab(visitor)
        
        Log.d("AppActivity", "Connecting to visitor room: $roomId (OUTGOING CALL)")
        currentRoom = roomId
        signalingClient.connect(roomId, token)
        
        // CRITICAL FIX: Do NOT send call_initiate for queue-based calls!
        // call_initiate is only for:
        //   1. Visitor -> Agent (visitor starts call)
        //   2. Agent -> External (agent creates NEW room via /agent/initiate_call API)
        // 
        // For queue calls: Agent joins existing visitor room and sends offer directly.
        // Backend detects agent join and updates status automatically.
        
        // WebRTC Offer erstellen und senden
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            webRtcClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                webRtcClient.peerConnection?.setLocalDescription(this, sdp)
                val offer = JSONObject().apply {
                    put("type", "offer")
                    put("data", JSONObject().apply {
                        put("type", sdp.type.canonicalForm())
                        put("sdp", sdp.description)
                    })
                    put("targetSessionId", visitor.sessionId)
                }
                if (signalingClient.send(offer)) {
                    activeCallInfo.text = "Verbinde mit ${visitor.callerName}..."
                    Log.d("AppActivity", "WebRTC Offer sent to room $roomId")
                } else {
                    Log.e("AppActivity", "Failed to send offer - WebSocket not connected")
                    safeRunOnUiThread {
                        Toast.makeText(this@AppActivity, "Fehler: Nicht verbunden", Toast.LENGTH_SHORT).show()
                        endCall()
                    }
                }
            }
            override fun onCreateFailure(s: String) { 
                Log.e("WebRTC", "Offer failed: $s")
                Toast.makeText(this@AppActivity, "Anruf fehlgeschlagen: $s", Toast.LENGTH_SHORT).show()
            }
            override fun onSetFailure(s: String) { Log.e("WebRTC", "SetLocalDesc failed: $s") }
            override fun onSetSuccess() {}
        })
        }, 300) // Delay to ensure WebSocket connection is stable
    }
    
    // Besucher ruft Agent an (eingehender Anruf)
    private fun handleIncomingOffer(message: JSONObject) {
        val sdpData = message.optJSONObject("data") ?: message.optJSONObject("sdp") // Backward compatibility
        if (sdpData == null) {
            Log.e("AppActivity", "Invalid offer: missing data/sdp")
            return
        }
        
        val callerSessionId = message.optString("sessionId")
        
        // CRITICAL FIX: If THIS agent initiated the call (outgoing), 
        // we sent the offer and should NOT process incoming offers
        if (isOutgoingCall && activeCallSessionId == callerSessionId) {
            Log.d("AppActivity", "Ignoring offer echo - this is OUR outgoing call to $callerSessionId")
            return
        }
        
        // CRITICAL FIX: Check if already in call with different visitor
        if (activeCallSessionId != null && activeCallSessionId != callerSessionId) {
            Log.w("AppActivity", "Already in call with $activeCallSessionId, rejecting offer from $callerSessionId")
            // Send busy signal
            val busyMsg = JSONObject().apply {
                put("type", "busy")
                put("targetSessionId", callerSessionId)
            }
            signalingClient.send(busyMsg)
            return
        }
        
        // This is a TRUE incoming call from visitor
        statusTextView.text = "Status: Eingehender Anruf!"
        
        val caller = liveVisitors.find { it.sessionId == callerSessionId } 
            ?: Visitor(
                sessionId = callerSessionId,
                domain = "N/A",
                callerName = "Web Visitor",
                logoUrl = null,
                roomId = callerSessionId  // Use sessionId as roomId fallback
            )
        
        activeCallSessionId = callerSessionId
        isWebRTCActive = true   // WebRTC will be active
        isOutgoingCall = false  // This is INCOMING call
        showActiveCallTab(caller)
        
        // CRITICAL FIX: Check if PeerConnection exists
        if (webRtcClient.peerConnection == null) {
            Log.e("AppActivity", "PeerConnection is null, cannot accept offer")
            safeRunOnUiThread {
                Toast.makeText(this, "WebRTC nicht initialisiert", Toast.LENGTH_SHORT).show()
                endCall()
            }
            return
        }
        
        // ✅ FIX: Send call_accept BEFORE setting remote description (Backend race condition fix)
        val acceptMsg = JSONObject().apply {
            put("type", "call_accept")
        }
        if (!signalingClient.send(acceptMsg)) {
            Log.e("AppActivity", "Failed to send call_accept - WebSocket not connected")
            safeRunOnUiThread {
                Toast.makeText(this, "Verbindungsfehler", Toast.LENGTH_SHORT).show()
                endCall()
            }
            return
        }
        Log.d("AppActivity", "✅ Sent call_accept to backend")
        
        val offerDesc = SessionDescription(
            SessionDescription.Type.OFFER,
            sdpData.getString("sdp")
        )
        webRtcClient.peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {
                Log.d("AppActivity", "Remote offer set successfully")
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {
                // CRITICAL FIX: Handle setRemoteDescription errors
                Log.e("AppActivity", "Failed to set remote description: $error")
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "WebRTC Fehler: $error", Toast.LENGTH_LONG).show()
                    endCall()
                }
            }
        }, offerDesc)
        
        webRtcClient.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answerSdp: SessionDescription) {
                webRtcClient.peerConnection?.setLocalDescription(this, answerSdp)
                
                val answer = JSONObject().apply {
                    put("type", "answer")
                    put("data", JSONObject().apply { 
                        put("type", answerSdp.type.canonicalForm())
                        put("sdp", answerSdp.description)
                    })
                    put("targetSessionId", callerSessionId) 
                }
                if (signalingClient.send(answer)) {
                    activeCallInfo.text = "Verbunden, im Gespräch"
                } else {
                    Log.e("AppActivity", "Failed to send answer - WebSocket not connected")
                    safeRunOnUiThread {
                        Toast.makeText(this@AppActivity, "Fehler beim Verbinden", Toast.LENGTH_SHORT).show()
                        endCall()
                    }
                }
            }
            override fun onCreateFailure(s: String) {}
            override fun onSetFailure(s: String) {}
            override fun onSetSuccess() {}
        })
    }

    private fun endCall() {
        val wasWebRTCActive = isWebRTCActive  // FIX: Capture state before reset
        
        activeCallSessionId = null
        isWebRTCActive = false      // CRITICAL: Reset WebRTC state
        isOutgoingCall = false      // CRITICAL: Reset call direction
        chatMessagesView.text = ""  // Chat-Verlauf löschen
        
        // FIX: Only close WebRTC resources if they were actually active
        if (wasWebRTCActive) {
            // Close WebRTC connection
            webRtcClient.close()
            
            // WICHTIG: Signalisiere dem Worker, dass der Anruf beendet ist (nur wenn connected)
            try {
                signalingClient.send(JSONObject().put("type", "hangup"))
            } catch (e: Exception) {
                Log.w("AppActivity", "Failed to send hangup (WebSocket not connected): ${e.message}")
            }
            
            // FIX: Disconnect Signaling WebSocket ONLY for WebRTC calls (not chat-only)
            // This prevents breaking the background queue polling
            signalingClient.disconnect()
        } else {
            // Chat-only mode: just return to visitors tab without disconnecting signaling
            Log.d("AppActivity", "Ending chat-only session (no WebRTC cleanup needed)")
        }
        
        showVisitorsTab()
    }
    
    // --- Chat-Funktionen ---
    
    private fun sendChatMessage() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty() || activeCallSessionId == null) return
        
        val chatMsg = JSONObject().apply {
            put("type", "chat")
            put("text", text)
            put("targetSessionId", activeCallSessionId)
        }
        
        if (signalingClient.send(chatMsg)) {
            // Eigene Nachricht im UI anzeigen
            appendChatMessage("Agent", text)
            chatInput.text.clear()
        } else {
            Toast.makeText(this, "Nachricht konnte nicht gesendet werden", Toast.LENGTH_SHORT).show()
            Log.e("AppActivity", "Failed to send chat message - WebSocket not connected")
        }
    }
    
    private fun handleChatMessage(message: JSONObject) {
        val sender = message.optString("senderRole", "Besucher")
        val text = message.optString("text", "")
        appendChatMessage(sender, text)
    }
    
    private fun appendChatMessage(sender: String, text: String) {
        val currentText = chatMessagesView.text.toString()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val newMessage = "[$timestamp] $sender: $text\n"
        
        // Limit to last 100 messages to prevent memory leak
        val lines = currentText.lines()
        val limitedText = if (lines.size > 100) {
            lines.takeLast(99).joinToString("\n") + "\n"
        } else {
            currentText
        }
        
        chatMessagesView.text = limitedText + newMessage
        
        // Auto-scroll zu neuester Nachricht
        chatMessagesView.post {
            chatMessagesView.parent?.let { parent ->
                (parent as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }
    
    // --- Menu Popup ---
    
    private fun showMenuPopup() {
        // Dismiss previous popup if showing (Bug #19)
        currentPopupWindow?.dismiss()
        
        val popupView = layoutInflater.inflate(R.layout.menu_popup_glass, null)
        val popupWindow = android.widget.PopupWindow(
            popupView,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        
        // Track popup for cleanup
        currentPopupWindow = popupWindow
        
        // Menu Items
        val menuUserSection = popupView.findViewById<View>(R.id.menu_user_section)
        val menuUserName = popupView.findViewById<TextView>(R.id.menu_user_name)
        val menuUserRole = popupView.findViewById<TextView>(R.id.menu_user_role)
        val menuDivider1 = popupView.findViewById<View>(R.id.menu_divider_1)
        val menuDivider2 = popupView.findViewById<View>(R.id.menu_divider_2)
        val menuAdmin = popupView.findViewById<Button>(R.id.menu_admin)
        val menuSupervisor = popupView.findViewById<Button>(R.id.menu_supervisor)
        val menuReconnect = popupView.findViewById<Button>(R.id.menu_reconnect)
        val menuLogout = popupView.findViewById<Button>(R.id.menu_logout)
        val menuLogin = popupView.findViewById<Button>(R.id.menu_login)
        val menuRegister = popupView.findViewById<Button>(R.id.menu_register)
        
        // Configure menu based on login state
        if (currentToken != null) {
            // Logged in
            menuUserSection.visibility = View.VISIBLE
            menuDivider1.visibility = View.VISIBLE
            menuDivider2.visibility = View.VISIBLE
            menuLogout.visibility = View.VISIBLE
            menuLogin.visibility = View.GONE
            menuRegister.visibility = View.GONE
            
            // User info
            menuUserName.text = authClient.getDisplayName() ?: authClient.getEmail() ?: "Agent"
            menuUserRole.text = currentRole?.replaceFirstChar { it.uppercase() } ?: "Agent"
            
            // Role-based items
            when (currentRole) {
                "superadmin" -> {
                    menuAdmin.visibility = View.VISIBLE
                    menuSupervisor.visibility = View.VISIBLE
                }
                "supervisor" -> {
                    menuAdmin.visibility = View.GONE
                    menuSupervisor.visibility = View.VISIBLE
                }
                else -> {
                    menuAdmin.visibility = View.GONE
                    menuSupervisor.visibility = View.GONE
                }
            }
            
            // Reconnect button
            menuReconnect.visibility = View.VISIBLE
            
        } else {
            // Not logged in
            menuUserSection.visibility = View.GONE
            menuDivider1.visibility = View.GONE
            menuDivider2.visibility = View.GONE
            menuAdmin.visibility = View.GONE
            menuSupervisor.visibility = View.GONE
            menuReconnect.visibility = View.GONE
            menuLogout.visibility = View.GONE
            menuLogin.visibility = View.VISIBLE
            menuRegister.visibility = View.VISIBLE
        }
        
        // Click listeners
        menuAdmin.setOnClickListener {
            popupWindow.dismiss()
            openAdminPanel()
        }
        
        menuSupervisor.setOnClickListener {
            popupWindow.dismiss()
            openSupervisorPanel()
        }
        
        menuReconnect.setOnClickListener {
            popupWindow.dismiss()
            performManualReconnect()
        }
        
        menuLogout.setOnClickListener {
            popupWindow.dismiss()
            performLogout()
        }
        
        menuLogin.setOnClickListener {
            popupWindow.dismiss()
            performLoginUI()
        }
        
        menuRegister.setOnClickListener {
            popupWindow.dismiss()
            performRegisterUI()
        }
        
        // Show popup anchored to menu button
        popupWindow.elevation = 10f
        popupWindow.showAsDropDown(menuButton, -200, 0)
    }
    
    // --- Admin/Supervisor Funktionen ---
    
    private fun updateRoleBasedUI(role: String?) {
        // Update User Badge in Header
        if (currentToken != null) {
            val displayName = authClient.getDisplayName()
            val email = authClient.getEmail()
            
            // Show role indicator in badge if available
            val initials = when {
                displayName != null && displayName.length >= 2 -> displayName.substring(0, 2).uppercase()
                email != null && email.length >= 2 -> email.substring(0, 2).uppercase()
                else -> "AG"
            }
            
            // Add role indicator (for Admin/Supervisor)
            val roleIndicator = when {
                role?.contains("Admin", ignoreCase = true) == true -> " 👑"
                role?.contains("Supervisor", ignoreCase = true) == true -> " 👁"
                else -> ""
            }
            
            userInfoBadge.text = "$initials$roleIndicator"
            userInfoBadge.visibility = View.VISIBLE
            
            // Log role for debugging
            Log.d("AppActivity", "UI updated for role: ${role ?: "none"}")
        } else {
            userInfoBadge.visibility = View.GONE
        }
    }
    
    private fun updateAuthUI(isLoggedIn: Boolean) {
        // Header Badge wird durch updateRoleBasedUI() gemanaged
        // Alte Button-Logik ist jetzt im Menu-Popup
        
        // Log auth state change for analytics
        Log.d("AppActivity", "Auth UI updated: isLoggedIn=$isLoggedIn, role=$currentRole")
        
        // Update UI based on login state
        if (isLoggedIn) {
            updateRoleBasedUI(currentRole)
            menuButton.visibility = View.VISIBLE
        } else {
            userInfoBadge.visibility = View.GONE
            menuButton.visibility = View.VISIBLE // Menu shows login option
        }
    }
    
    private fun performManualReconnect() {
        if (!::authClient.isInitialized) {
            Toast.makeText(this, "Bitte warte auf Berechtigungen / Initialisierung", Toast.LENGTH_SHORT).show()
            performLoginUI()
            return
        }
        val token = currentToken ?: authClient.getToken()
        if (token != null) {
            statusTextView.text = "Status: Neustarte Queue-Polling..."
            liveVisitors.clear()
            visitorAdapter.notifyDataSetChanged()
            
            // Agent: Restart queue-polling statt Room-Connect
            stopQueuePolling()
            startQueuePolling()
            
            currentToken = token
            findViewById<ProgressBar>(R.id.reconnect_progress).visibility = View.VISIBLE
            connectButton.visibility = View.GONE
            
            Toast.makeText(this, "🔄 Queue-Polling neugestartet", Toast.LENGTH_SHORT).show()
        } else {
            performLoginUI()
        }
    }
    
    private fun updateConnectionUI(isConnected: Boolean) {
        if (isConnected) {
            connectButton.visibility = View.GONE
            findViewById<ProgressBar>(R.id.reconnect_progress).visibility = View.GONE
        } else {
            // Nur anzeigen wenn eingeloggt
            if (currentToken != null) {
                connectButton.visibility = View.VISIBLE
                connectButton.text = "🔄 Reconnect"
                connectButton.isEnabled = true
                // OnClickListener already set in onCreate() - don't re-set to avoid memory leaks
            }
        }
    }
    
    // Start CallService NUR für FCM Push Notifications (kein WebSocket Room)
    private fun startCallServiceForFcm(token: String) {
        try {
            val serviceIntent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_START_SERVICE
                // KEIN EXTRA_ROOM_ID - Agent hat keinen festen Room!
                putExtra(CallService.EXTRA_TOKEN, token)
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Service binden für Kommunikation
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            
            Log.d("AppActivity", "CallService started (FCM only, no WebSocket room)")
            Toast.makeText(this, "✅ Anruf-Service aktiv (FCM)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("AppActivity", "Failed to start CallService", e)
            Toast.makeText(this, "Fehler beim Starten des Services: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Legacy function (nur noch für alte Code-Pfade falls nötig)
    private fun startCallService(roomId: String, token: String) {
        Log.w("AppActivity", "startCallService(roomId) is deprecated - Agent should not have fixed room")
        Log.w("AppActivity", "Attempted to start service with room: $roomId (ignoring, using FCM-based service instead)")
        startCallServiceForFcm(token)
    }
    
    private fun stopCallService() {
        try {
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }
            
            val serviceIntent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_STOP_SERVICE
            }
            startService(serviceIntent)
            
            Log.d("AppActivity", "CallService stopped")
        } catch (e: Exception) {
            Log.e("AppActivity", "Failed to stop CallService", e)
        }
    }
    
    // FCM Token Management
    private fun sendFcmTokenToBackend() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("AppActivity", "FCM token fetch failed", task.exception)
                return@OnCompleteListener
            }
            
            val fcmToken = task.result
            Log.d("AppActivity", "FCM Token: $fcmToken")
            
            // An Backend senden
            authClient.sendFcmToken(fcmToken, object : AuthClient.FcmTokenCallback {
                override fun onSuccess() {
                    Log.d("AppActivity", "FCM token sent to backend successfully")
                }
                
                override fun onFailure(message: String) {
                    Log.w("AppActivity", "Failed to send FCM token: $message")
                }
            })
        })
    }
    
    private fun checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Batterie-Optimierung deaktivieren")
                    .setMessage("Für zuverlässige Anrufbenachrichtigungen im Hintergrund muss die Batterie-Optimierung für diese App deaktiviert werden.\n\nOhne diese Einstellung können Sie möglicherweise keine Anrufe empfangen, wenn die App im Hintergrund läuft.")
                    .setPositiveButton("Einstellungen öffnen") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("AppActivity", "Failed to open battery settings", e)
                            Toast.makeText(this, "Bitte deaktivieren Sie die Batterie-Optimierung manuell in den Einstellungen", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Später") { dialog, _ ->
                        dialog.dismiss()
                        Toast.makeText(this, "⚠️ Anrufempfang im Hintergrund möglicherweise eingeschränkt", Toast.LENGTH_LONG).show()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }
    
    private fun updateConnectionQuality(quality: String) {
        runOnUiThread {
            when (quality) {
                "excellent" -> {
                    connectionQualityView.text = "📶"
                    connectionQualityView.setTextColor(resources.getColor(R.color.accent_green, null))
                }
                "good" -> {
                    connectionQualityView.text = "📶"
                    connectionQualityView.setTextColor(resources.getColor(R.color.soft_cyan, null))
                }
                "poor" -> {
                    connectionQualityView.text = "📶"
                    connectionQualityView.setTextColor(android.graphics.Color.parseColor("#FFA500"))
                }
                "bad" -> {
                    connectionQualityView.text = "📶"
                    connectionQualityView.setTextColor(android.graphics.Color.parseColor("#FF0000"))
                }
                else -> {
                    connectionQualityView.visibility = View.GONE
                }
            }
        }
    }
    
    private fun performLogout() {
        AlertDialog.Builder(this)
            .setTitle("🚪 Abmelden")
            .setMessage("Möchten Sie sich wirklich abmelden?")
            .setPositiveButton("Abmelden") { _, _ ->
                // Stop queue polling
                stopQueuePolling()
                
                // FIX: Close WebRTC resources
                webRtcClient.close()
                
                // Disconnect WebSocket
                signalingClient.disconnect()
                
                // Stop CallService
                stopCallService()
                
                // Clear auth data
                authClient.clearToken()
                
                // Reset UI
                currentToken = null
                currentRole = null
                currentRoom = null
                activeCallSessionId = null
                val oldSize = liveVisitors.size
                liveVisitors.clear()
                if (oldSize > 0) {
                    visitorAdapter.notifyItemRangeRemoved(0, oldSize)
                }
                
                // Hide role-based buttons (alte Buttons im Layout falls noch vorhanden)
                adminButton.visibility = View.GONE
                supervisorButton.visibility = View.GONE
                activeCallLayout.visibility = View.GONE
                
                // Hide User Badge
                userInfoBadge.visibility = View.GONE
                
                // Reset Auth UI
                updateAuthUI(isLoggedIn = false)
                
                statusTextView.text = "Status: Abgemeldet"
                Toast.makeText(this, "✅ Erfolgreich abgemeldet", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    // --- Queue Polling System ---
    
    private fun startQueuePolling() {
        Log.d("AppActivity", "Starting queue polling...")
        stopQueuePolling() // Stop existing polling if any
        
        queuePollingHandler = android.os.Handler(android.os.Looper.getMainLooper())
        queuePollingRunnable = object : Runnable {
            override fun run() {
                fetchQueuedCalls()
                queuePollingHandler?.postDelayed(this, QUEUE_POLL_INTERVAL_MS)
            }
        }
        queuePollingHandler?.post(queuePollingRunnable!!)
    }
    
    private fun stopQueuePolling() {
        queuePollingHandler?.removeCallbacksAndMessages(null)
        queuePollingHandler = null
        queuePollingRunnable = null
        Log.d("AppActivity", "Queue polling stopped")
    }
    
    private fun fetchQueuedCalls() {
        val token = currentToken ?: return
        
        val url = "https://$BACKEND_HOST/api/agent/queues"
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                Log.e("AppActivity", "Queue fetch failed: ${e.message}")
                // Don't show error to user - silent background polling
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                response.use {
                    if (!it.isSuccessful) {
                        Log.e("AppActivity", "Queue fetch error: ${it.code}")
                        return
                    }
                    
                    try {
                        val jsonData = org.json.JSONObject(it.body?.string() ?: "{}")
                        val queues = jsonData.optJSONArray("queues") ?: org.json.JSONArray()
                        val total = jsonData.optInt("total", 0)
                        
                        // Log total count from backend for analytics/debugging
                        Log.d("AppActivity", "Queue API returned: total=$total, queues.length=${queues.length()}")
                        
                        safeRunOnUiThread {
                            // CRITICAL FIX: Merge statt replace - aktiven Call behalten
                            val activeCall = liveVisitors.find { v -> v.sessionId == activeCallSessionId }
                            val newVisitors = mutableListOf<Visitor>()
                            
                            // 1. Keep active call at top if exists
                            if (activeCall != null) {
                                newVisitors.add(activeCall)
                                Log.d("AppActivity", "Keeping active call in list: ${activeCall.sessionId}")
                            }
                            
                            // 2. Add queued calls (exclude active to avoid duplicates)
                            val domainCounts = mutableMapOf<String, Int>()
                            for (i in 0 until queues.length()) {
                                val callObj = queues.getJSONObject(i)
                                val callId = callObj.optString("session_id", "") // FIXED: was "id"
                                val domainName = callObj.optString("domain_name", "Unbekannt")
                                val domainId = callObj.optString("domain_id", "")
                                val status = callObj.optString("status", "queued")
                                
                                // Track domain distribution for analytics
                                domainCounts[domainId] = (domainCounts[domainId] ?: 0) + 1
                                
                                // Skip if this is the active call (already added)
                                if (callId == activeCallSessionId) {
                                    continue
                                }
                                
                                // Nur queued oder ringing Calls anzeigen
                                if (status == "queued" || status == "ringing") {
                                    newVisitors.add(Visitor(
                                        sessionId = callId,
                                        domain = domainName,
                                        callerName = "Wartender Besucher - $domainName",
                                        logoUrl = null,
                                        roomId = callId
                                    ))
                                }
                            }
                            
                            // Log domain distribution for agent awareness
                            if (domainCounts.isNotEmpty()) {
                                Log.d("AppActivity", "Queue by domain: ${domainCounts.entries.joinToString { "${it.key}=${it.value}" }}")
                            }
                            
                            // 3. Update list
                            val oldSize = liveVisitors.size
                            liveVisitors.clear()
                            liveVisitors.addAll(newVisitors)
                            
                            if (oldSize != newVisitors.size) {
                                visitorAdapter.notifyDataSetChanged()
                            }
                            
                            // Show both local and backend total if different (domain filtering case)
                            if (total > liveVisitors.size) {
                                visitorCountBadge.text = "${liveVisitors.size}/$total"
                                Log.d("AppActivity", "Showing ${liveVisitors.size} of $total total calls (filtered by domain)")
                            } else {
                                visitorCountBadge.text = liveVisitors.size.toString()
                            }
                            
                            if (liveVisitors.isEmpty()) {
                                statusTextView.text = "Status: ✅ Verbunden - keine wartenden Anrufe"
                            } else {
                                statusTextView.text = "Status: ✅ ${liveVisitors.size} wartende(r) Anruf(e)"
                            }
                            
                            Log.d("AppActivity", "Queue updated: ${liveVisitors.size} waiting calls (active: $activeCallSessionId)")
                        }
                    } catch (e: Exception) {
                        Log.e("AppActivity", "Queue parse error: ${e.message}")
                    }
                }
            }
        })
    }
    
    // ============================================================================
    // CRITICAL FIX: Realtime WebSocket Notifications
    // ============================================================================
    
    private fun initializeNotifications(token: String) {
        Log.d("AppActivity", "Initializing Agent Notifications WebSocket...")
        
        // Disconnect existing connection
        notificationsClient?.disconnect()
        
        notificationsClient = AgentNotificationsClient(
            listener = object : AgentNotificationListener {
                override fun onConnected() {
                    safeRunOnUiThread {
                        statusTextView.text = "Status: 🟢 Realtime verbunden"
                        Log.d("AppActivity", "✅ Agent Notifications WebSocket connected")
                    }
                }
                
                override fun onNewCall(roomId: String, domainId: String, domainName: String, timestamp: Long) {
                    Log.d("AppActivity", "🆕 New call notification: $roomId ($domainName)")
                    
                    safeRunOnUiThread {
                        // Check if call already exists (race condition with REST poll)
                        val exists = liveVisitors.any { it.sessionId == roomId }
                        if (!exists) {
                            // Insert at TOP of queue
                            liveVisitors.add(0, Visitor(
                                sessionId = roomId,
                                domain = domainName,
                                callerName = "Neuer Anruf - $domainName",
                                logoUrl = null,
                                roomId = roomId,
                                timestamp = timestamp
                            ))
                            visitorAdapter.notifyItemInserted(0)
                            
                            // Update badge
                            visitorCountBadge.text = liveVisitors.size.toString()
                            statusTextView.text = "Status: ✅ ${liveVisitors.size} wartende(r) Anruf(e)"
                            
                            // Play notification sound
                            playNotificationSound()
                            
                            Log.d("AppActivity", "Added new call to queue: $roomId")
                        } else {
                            Log.d("AppActivity", "Call $roomId already in queue (from REST poll)")
                        }
                    }
                }
                
                override fun onCallRinging(roomId: String, initiator: String, timestamp: Long) {
                    Log.d("AppActivity", "🔔 Call ringing: $roomId (initiator: $initiator)")
                    
                    safeRunOnUiThread {
                        // Update status in list if exists
                        val index = liveVisitors.indexOfFirst { it.sessionId == roomId }
                        if (index >= 0) {
                            visitorAdapter.notifyItemChanged(index)
                        }
                        
                        // ✅ FIX: Play ringtone for incoming calls (from visitor OR agent)
                        // Backend sendet "from" (nicht "initiator")
                        if (initiator == "visitor" || initiator == "agent") {
                            playRingtone()
                            
                            // Show incoming call dialog
                            val visitor = liveVisitors.find { it.sessionId == roomId }
                            if (visitor != null) {
                                showIncomingCallDialog(visitor, roomId)
                            } else {
                                Log.w("AppActivity", "Visitor not found for ringing call: $roomId")
                            }
                        }
                    }
                }
                
                override fun onCallActive(roomId: String, domainId: String, agentId: String?, timestamp: Long) {
                    Log.d("AppActivity", "✅ Call active: $roomId (agent: ${agentId ?: "unknown"})")
                    
                    safeRunOnUiThread {
                        // If claimed by ANOTHER agent: remove from queue
                        if (agentId != null && agentId != currentUserId) {
                            val index = liveVisitors.indexOfFirst { it.sessionId == roomId }
                            if (index >= 0) {
                                liveVisitors.removeAt(index)
                                visitorAdapter.notifyItemRemoved(index)
                                visitorCountBadge.text = liveVisitors.size.toString()
                                
                                Toast.makeText(
                                    this@AppActivity,
                                    "Call wurde von anderem Agent angenommen",
                                    Toast.LENGTH_SHORT
                                ).show()
                                
                                Log.d("AppActivity", "Call $roomId claimed by other agent: $agentId")
                            }
                        }
                        // If claimed by THIS agent: keep in list with active indicator
                    }
                }
                
                override fun onCallEnded(roomId: String, domainId: String, reason: String) {
                    Log.d("AppActivity", "⏹️ Call ended: $roomId (reason: $reason)")
                    
                    safeRunOnUiThread {
                        // Remove from queue list
                        val index = liveVisitors.indexOfFirst { it.sessionId == roomId }
                        if (index >= 0) {
                            liveVisitors.removeAt(index)
                            visitorAdapter.notifyItemRemoved(index)
                            visitorCountBadge.text = liveVisitors.size.toString()
                            
                            if (liveVisitors.isEmpty()) {
                                statusTextView.text = "Status: ✅ Verbunden - keine wartenden Anrufe"
                            } else {
                                statusTextView.text = "Status: ✅ ${liveVisitors.size} wartende(r) Anruf(e)"
                            }
                        }
                        
                        // If it was the active call
                        if (roomId == activeCallSessionId) {
                            stopRingtone()
                            
                            val reasonText = when(reason) {
                                "completed" -> "Call beendet"
                                "missed" -> "Anruf verpasst"
                                "cancelled" -> "Anruf abgebrochen"
                                "timeout" -> "Zeitüberschreitung"
                                "visitor_timeout" -> "Visitor hat nicht reagiert"
                                "abandoned" -> "Visitor hat abgebrochen"
                                else -> "Call beendet: $reason"
                            }
                            
                            Toast.makeText(this@AppActivity, reasonText, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
                override fun onDisconnected() {
                    safeRunOnUiThread {
                        statusTextView.text = "Status: 🔴 Realtime getrennt"
                        Log.w("AppActivity", "⚠️ Agent Notifications WebSocket disconnected")
                    }
                }
                
                override fun onError(message: String) {
                    Log.e("AppActivity", "❌ Agent Notifications error: $message")
                }
            },
            backendHost = BACKEND_HOST,
            token = token
        )
        
        notificationsClient?.connect()
    }
    
    // Notification sounds
    private var notificationPlayer: android.media.MediaPlayer? = null
    
    private fun playNotificationSound() {
        try {
            notificationPlayer?.release()
            notificationPlayer = android.media.MediaPlayer.create(
                this,
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            )
            notificationPlayer?.start()
        } catch (e: Exception) {
            Log.e("AppActivity", "Failed to play notification sound", e)
        }
    }
    
    private fun openAdminPanel() {
        if (currentRole != "superadmin") {
            Toast.makeText(this, "Keine Berechtigung", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Admin-Panel Intent (später eigene Activity)
        AlertDialog.Builder(this)
            .setTitle("🔧 SuperAdmin Panel")
            .setMessage("Admin-Funktionen:\n\n" +
                "• User verwalten\n" +
                "• Rollen verwalten (NEW)\n" +
                "• Domains verwalten\n" +
                "• Call-Warteschlange\n\n" +
                "Vollständiges Admin-Panel kommt bald!")
            .setPositiveButton("User verwalten") { _, _ ->
                openUserManagement()
            }
            .setNeutralButton("🎭 Rollen") { _, _ ->
                openRoleManagement()
            }
            .setNegativeButton("🚪 Logout") { _, _ ->
                performLogout()
            }
            .show()
    }
    
    // --- Queue Management (API v2.2) ---
    
    private fun openQueueManagement() {
        val token = currentToken ?: return
        
        // Check permission
        if (!authClient.hasPermission("call.view.all")) {
            Toast.makeText(this, "Keine Berechtigung für Queue-Verwaltung", Toast.LENGTH_SHORT).show()
            return
        }
        
        statusTextView.text = "Status: Lade Warteschlange..."
        
        val url = "https://$BACKEND_HOST/api/admin/queues"
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Queue-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                    statusTextView.text = "Status: Fehler beim Laden"
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                response.use {
                    if (!it.isSuccessful) {
                        safeRunOnUiThread {
                            val errorBody = it.body?.string() ?: ""
                            
                            // Parse error message if JSON
                            val errorMsg = try {
                                org.json.JSONObject(errorBody).optString("error", errorBody)
                            } catch (e: Exception) {
                                errorBody
                            }
                            
                            // Log error details for debugging
                            Log.e("AppActivity", "Admin queue fetch failed: ${it.code} - $errorMsg")
                            
                            // Report error to backend (Admin operation failure)
                            errorReporter.reportError(
                                errorType = ErrorReporter.ErrorType.NETWORK,
                                errorMessage = "Admin queue fetch failed: HTTP ${it.code}",
                                severity = ErrorReporter.Severity.ERROR,
                                context = mapOf(
                                    "endpoint" to "/api/admin/queues",
                                    "statusCode" to it.code.toString(),
                                    "errorBody" to errorBody.take(500) // Limit size
                                ),
                                authToken = currentToken
                            )
                            
                            Toast.makeText(this@AppActivity, "Queue-Fehler: ${it.code} - $errorMsg", Toast.LENGTH_LONG).show()
                            statusTextView.text = "Status: Fehler ${it.code}"
                        }
                        return
                    }
                    
                    val jsonData = org.json.JSONObject(it.body?.string() ?: "{}")
                    val queuesArray = jsonData.optJSONArray("queues")
                    
                    safeRunOnUiThread {
                        showQueueList(queuesArray)
                        statusTextView.text = "Status: Queue-Verwaltung"
                    }
                }
            }
        })
    }
    
    private fun showQueueList(queuesArray: org.json.JSONArray?) {
        if (queuesArray == null || queuesArray.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle("📋 Warteschlange leer")
                .setMessage("Aktuell keine wartenden Anrufe.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        val queueItems = mutableListOf<String>()
        val queueData = mutableListOf<org.json.JSONObject>()
        
        for (i in 0 until queuesArray.length()) {
            val queueEntry = queuesArray.getJSONObject(i)
            val sessionId = queueEntry.optString("session_id", "unknown")
            val domainName = queueEntry.optString("domain_name", queueEntry.optString("domain_id"))
            val status = queueEntry.optString("status", "queued")
            val waitTime = queueEntry.optLong("waitTime", 0) / 1000 // ms to seconds
            
            val statusIcon = when(status) {
                "queued" -> "⏳"
                "ringing" -> "📞"
                else -> "❓"
            }
            
            // Show session ID prefix for admin debugging
            val sessionPrefix = sessionId.take(8)
            queueItems.add("$statusIcon $domainName - ${waitTime}s wartend [ID: $sessionPrefix]")
            queueData.add(queueEntry)
        }
        
        AlertDialog.Builder(this)
            .setTitle("📋 Warteschlange (${queueItems.size})")
            .setItems(queueItems.toTypedArray()) { _, which ->
                showQueueActions(queueData[which])
            }
            .setNeutralButton("🔄 Aktualisieren") { _, _ ->
                openQueueManagement() // Reload
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
    
    private fun showQueueActions(queueEntry: org.json.JSONObject) {
        val sessionId = queueEntry.optString("session_id")
        val domainName = queueEntry.optString("domain_name", queueEntry.optString("domain_id"))
        val status = queueEntry.optString("status", "queued")
        val waitTime = queueEntry.optLong("waitTime", 0) / 1000
        
        AlertDialog.Builder(this)
            .setTitle("Call-Details")
            .setMessage("Domain: $domainName\n" +
                "Session: ${sessionId.take(20)}...\n" +
                "Status: $status\n" +
                "Wartezeit: ${waitTime}s")
            .setPositiveButton("👤 Agent zuweisen") { _, _ ->
                assignQueueToAgent(sessionId, domainName)
            }
            .setNeutralButton("🗑️ Löschen") { _, _ ->
                confirmDeleteQueue(sessionId, domainName)
            }
            .setNegativeButton("Zurück", null)
            .show()
    }
    
    private fun assignQueueToAgent(sessionId: String, domainName: String) {
        val token = currentToken ?: return
        
        // Check permission (API v2.2: call.assign)
        if (!authClient.hasPermission("call.assign")) {
            Toast.makeText(this, "Keine Berechtigung zum Zuweisen (call.assign erforderlich)", Toast.LENGTH_SHORT).show()
            return
        }
        
        // In production: Show agent selection dialog
        // For now: Assign to current user
        val currentUserId = authClient.getUserId() ?: run {
            Toast.makeText(this, "User ID nicht verfügbar", Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.d("AppActivity", "Assigning queue $sessionId (domain: $domainName) to agent $currentUserId")
        
        val url = "https://$BACKEND_HOST/api/admin/queues/$sessionId/assign"
        val json = org.json.JSONObject().apply {
            put("agentId", currentUserId)
        }
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Zuweisen fehlgeschlagen ($domainName): ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@AppActivity, "✅ Call zugewiesen: $domainName", Toast.LENGTH_SHORT).show()
                        openQueueManagement() // Refresh
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        Toast.makeText(this@AppActivity, "Fehler ${response.code} ($domainName): $errorBody", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun confirmDeleteQueue(sessionId: String, domainName: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Call entfernen?")
            .setMessage("Möchten Sie diesen wartenden Call wirklich aus der Warteschlange entfernen?\n\n" +
                "Domain: $domainName\n" +
                "Session: ${sessionId.take(20)}...")
            .setPositiveButton("🗑️ Löschen") { _, _ ->
                deleteQueueEntry(sessionId)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun deleteQueueEntry(sessionId: String) {
        val token = currentToken ?: return
        
        // Check permission
        if (!authClient.hasPermission("call.manage")) {
            Toast.makeText(this, "Keine Berechtigung zum Löschen", Toast.LENGTH_SHORT).show()
            return
        }
        
        val url = "https://$BACKEND_HOST/api/admin/queues/$sessionId"
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Löschen fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@AppActivity, "✅ Call entfernt", Toast.LENGTH_SHORT).show()
                        openQueueManagement() // Refresh
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: $errorBody", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    // --- Ende Queue Management ---
    
    // --- Outgoing Calls (Agent → Visitor) API v2.2 ---
    
    private fun initiateOutgoingCall() {
        val token = currentToken ?: run {
            Log.w("AppActivity", "initiateOutgoingCall: No auth token available")
            Toast.makeText(this, "Nicht angemeldet", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Log token validation for debugging
        Log.d("AppActivity", "Initiating outgoing call with valid token (length: ${token.length})")
        
        // Check permission
        if (!authClient.hasPermission("call.initiate")) {
            Toast.makeText(this, "Keine Berechtigung für ausgehende Calls", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Dialog für Visitor-Informationen
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        val visitorIdInput = android.widget.EditText(this).apply {
            hint = "Visitor ID (Email, Tel, etc.)"
        }
        
        val messageInput = android.widget.EditText(this).apply {
            hint = "Einladungsnachricht (optional)"
            setText("Sie wurden zu einem Call eingeladen")
        }
        
        layout.addView(visitorIdInput)
        layout.addView(messageInput)
        
        // Domain Selection
        val domains = authClient.getDomains()
        val domainInput = android.widget.Spinner(this)
        val domainAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, domains)
        domainInput.adapter = domainAdapter
        
        if (domains.isNotEmpty()) {
            layout.addView(android.widget.TextView(this).apply {
                text = "Domain:"
                setPadding(0, 20, 0, 5)
            })
            layout.addView(domainInput)
        }
        
        AlertDialog.Builder(this)
            .setTitle("📞 Ausgehender Call")
            .setView(layout)
            .setPositiveButton("Call initiieren") { _, _ ->
                val visitorId = sanitizeInput(visitorIdInput.text.toString())
                val message = messageInput.text.toString().trim()
                val domain = if (domains.isNotEmpty()) domains[domainInput.selectedItemPosition] else DOMAIN_ID
                
                if (visitorId.isBlank()) {
                    Toast.makeText(this, "Bitte Visitor ID eingeben", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                createOutgoingCall(visitorId, domain, message.takeIf { it.isNotEmpty() })
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun createOutgoingCall(visitorId: String, domain: String, message: String?) {
        val token = currentToken ?: return
        
        statusTextView.text = "Status: Erstelle ausgehenden Call..."
        
        val url = "https://$BACKEND_HOST/api/agent/initiate_call"
        val json = org.json.JSONObject().apply {
            put("visitorId", visitorId)
            put("domain", domain)
            if (message != null) put("message", message)
        }
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Call-Erstellung fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                    statusTextView.text = "Status: Fehler"
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                response.use {
                    if (!it.isSuccessful) {
                        safeRunOnUiThread {
                            val errorBody = it.body?.string() ?: ""
                            val errorJson = try {
                                org.json.JSONObject(errorBody)
                            } catch (e: Exception) {
                                null
                            }
                            val errorMsg = errorJson?.optString("error", errorBody) ?: errorBody
                            Toast.makeText(this@AppActivity, "Fehler ${it.code}: $errorMsg", Toast.LENGTH_LONG).show()
                            statusTextView.text = "Status: Fehler ${it.code}"
                        }
                        return
                    }
                    
                    val jsonData = org.json.JSONObject(it.body?.string() ?: "{}")
                    val roomId = jsonData.optString("room_id")
                    val visitorToken = jsonData.optString("visitor_token")
                    val visitorLink = jsonData.optString("visitor_link")
                    val expiresIn = jsonData.optInt("expires_in", 86400)
                    
                    safeRunOnUiThread {
                        showVisitorLinkDialog(visitorId, roomId, visitorLink, visitorToken, expiresIn)
                        statusTextView.text = "Status: Call erstellt - warte auf Visitor"
                    }
                }
            }
        })
    }
    
    private fun showVisitorLinkDialog(visitorId: String, roomId: String, visitorLink: String, visitorToken: String, expiresIn: Int) {
        val hoursValid = expiresIn / 3600
        
        val message = "✅ Call-Link erstellt!\n\n" +
            "Visitor: $visitorId\n" +
            "Room ID: $roomId\n" +
            "Gültig für: ${hoursValid}h\n\n" +
            "Link:\n$visitorLink\n\n" +
            "Token (für API):\n${visitorToken.take(40)}...\n\n" +
            "Senden Sie diesen Link an den Visitor via Email, SMS oder Chat."
        
        AlertDialog.Builder(this)
            .setTitle("📞 Call-Link bereit")
            .setMessage(message)
            .setPositiveButton("📋 Link kopieren") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Visitor Call Link", visitorLink)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "✅ Link kopiert!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("🔑 Token kopieren") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Visitor Token", visitorToken)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "✅ Token kopiert!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("📤 Teilen") { _, _ ->
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "Sie wurden zu einem Call eingeladen: $visitorLink")
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(shareIntent, "Call-Link teilen"))
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
    
    // --- Ende Outgoing Calls ---
    
    private fun openUserManagement() {
        val token = currentToken ?: return
        
        statusTextView.text = "Status: Lade User-Daten..."
        
        // API Call zu /api/admin/data
        val url = "https://$BACKEND_HOST/api/admin/data"
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                    statusTextView.text = "Status: Fehler beim Laden"
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                response.use {
                    if (!it.isSuccessful) {
                        safeRunOnUiThread {
                            Toast.makeText(this@AppActivity, "Fehler: ${it.code}", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    
                    val jsonData = org.json.JSONObject(it.body?.string() ?: "{}")
                    val usersArray = jsonData.optJSONArray("users")
                    val domainsArray = jsonData.optJSONArray("domains")
                    
                    safeRunOnUiThread {
                        showUserList(usersArray, domainsArray)
                        statusTextView.text = "Status: Admin-Modus"
                    }
                }
            }
        })
    }
    
    private fun showUserList(usersArray: org.json.JSONArray?, domainsArray: org.json.JSONArray?) {
        if (usersArray == null) {
            Toast.makeText(this, "Keine User gefunden", Toast.LENGTH_SHORT).show()
            return
        }
        
        val userList = mutableListOf<String>()
        val userIds = mutableListOf<String>()
        
        for (i in 0 until usersArray.length()) {
            val user = usersArray.getJSONObject(i)
            val email = user.optString("email")
            val approved = user.optBoolean("approved")
            val status = if (approved) "✅" else "⏳"
            userList.add("$status $email")
            userIds.add(user.optString("id"))
        }
        
        AlertDialog.Builder(this)
            .setTitle("User Verwaltung (${userList.size})")
            .setItems(userList.toTypedArray()) { _, which ->
                val userId = userIds[which]
                val userObj = usersArray.getJSONObject(which)
                showUserActions(userId, userObj, domainsArray)
            }
            .setPositiveButton("➕ Neuer User") { _, _ ->
                createNewUser()
            }
            .setNegativeButton("Zurück", null)
            .show()
    }
    
    private fun createNewUser() {
        // Check permission
        if (!authClient.hasPermission("user.create")) {
            Toast.makeText(this, "Keine Berechtigung zum Erstellen", Toast.LENGTH_SHORT).show()
            return
        }
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        val emailInput = android.widget.EditText(this).apply {
            hint = "Email"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Passwort"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        
        val nameInput = android.widget.EditText(this).apply {
            hint = "Name (optional)"
        }
        
        layout.apply {
            addView(emailInput)
            addView(passwordInput)
            addView(nameInput)
        }
        
        AlertDialog.Builder(this)
            .setTitle("➕ Neuen User erstellen")
            .setView(layout)
            .setPositiveButton("Erstellen") { _, _ ->
                val email = sanitizeInput(emailInput.text.toString())
                val password = passwordInput.text.toString()
                val name = nameInput.text.toString().trim().takeIf { it.isNotEmpty() }
                
                if (email.isBlank() || password.isBlank()) {
                    Toast.makeText(this, "Email und Passwort erforderlich", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                submitCreateUser(email, password, name)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun submitCreateUser(email: String, password: String, name: String?) {
        val token = currentToken ?: return
        
        statusTextView.text = "Status: Erstelle User..."
        
        val url = "https://$BACKEND_HOST/api/admin/create"
        val json = org.json.JSONObject().apply {
            put("email", email)
            put("password", password)
            if (name != null) put("name", name)
        }
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                
                // Report network error
                errorReporter.reportNetworkError(
                    message = "Failed to load queue: ${e.message}",
                    throwable = e,
                    context = mapOf(
                        "endpoint" to "/api/admin/queue",
                        "action" to "load_queue"
                    ),
                    authToken = token
                )
                
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                    statusTextView.text = "Status: Fehler"
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    if (response.isSuccessful) {
                        val responseData = org.json.JSONObject(response.body?.string() ?: "{}")
                        val userId = responseData.optString("userId", "")
                        
                        // Log successful user creation with ID for analytics
                        Log.i("AppActivity", "User created successfully: $email (ID: $userId)")
                        
                        // Show userId in toast for admin reference
                        if (userId.isNotEmpty()) {
                            Toast.makeText(this@AppActivity, "✅ User erstellt: $email\n(ID: ${userId.take(12)}...)", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@AppActivity, "✅ User erstellt: $email", Toast.LENGTH_SHORT).show()
                        }
                        
                        statusTextView.text = "Status: User erstellt"
                        openUserManagement() // Refresh
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        val errorMsg = try {
                            org.json.JSONObject(errorBody).optString("error", errorBody)
                        } catch (e: Exception) {
                            errorBody
                        }
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: $errorMsg", Toast.LENGTH_LONG).show()
                        statusTextView.text = "Status: Fehler ${response.code}"
                    }
                }
            }
        })
    }
    
    private fun showUserActions(userId: String, userObj: org.json.JSONObject, domainsArray: org.json.JSONArray?) {
        val email = userObj.optString("email")
        val approved = userObj.optBoolean("approved")
        val allowedDomains = userObj.optJSONArray("allowed_domains")
        
        val domainsList = mutableListOf<String>()
        if (allowedDomains != null) {
            for (i in 0 until allowedDomains.length()) {
                domainsList.add(allowedDomains.optString(i))
            }
        }
        
        val message = buildString {
            append("Email: $email\n\n")
            append("Status: ${if (approved) "✅ Freigegeben" else "⏳ Wartet auf Freischaltung"}\n\n")
            append("Zugewiesene Domains:\n")
            if (domainsList.isEmpty()) {
                append("  (keine)\n")
            } else {
                domainsList.forEach { append("  • $it\n") }
            }
        }
        
        val builder = AlertDialog.Builder(this)
            .setTitle("User: $email")
            .setMessage(message)
        
        
        
        builder.setNeutralButton("🏢 Domains zuweisen") { _, _ ->
            assignDomains(userId, email, domainsList, domainsArray)
        }
        
        // FIX: Use setPositiveButton instead of second setNeutralButton
        if (!approved) {
            builder.setPositiveButton("✅ Freischalten & Bearbeiten") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Aktion wählen")
                    .setMessage("Möchten Sie den Nutzer zuerst freischalten?")
                    .setPositiveButton("Freischalten") { _, _ ->
                        approveUser(userId, email)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            editUser(userId, userObj)
                        }, 500)
                    }
                    .setNegativeButton("Nur Bearbeiten") { _, _ ->
                        editUser(userId, userObj)
                    }
                    .show()
            }
        } else {
            builder.setPositiveButton("✏️ Bearbeiten") { _, _ ->
                editUser(userId, userObj)
            }
        }
        
        builder.setNegativeButton("🗑️ Löschen") { _, _ ->
            confirmDeleteUser(userId, email)
        }
        
        builder.show()
    }
    
    private fun editUser(userId: String, userObj: org.json.JSONObject) {
        // Check permission
        if (!authClient.hasPermission("user.edit.team") && !authClient.hasPermission("user.edit.all")) {
            Toast.makeText(this, "Keine Berechtigung zum Bearbeiten", Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentEmail = userObj.optString("email", "")
        val currentName = userObj.optString("displayName", "")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        val emailInput = android.widget.EditText(this).apply {
            hint = "Email"
            setText(currentEmail)
        }
        
        val nameInput = android.widget.EditText(this).apply {
            hint = "Name"
            setText(currentName)
        }
        
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Neues Passwort (leer lassen für keine Änderung)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        
        layout.apply {
            addView(emailInput)
            addView(nameInput)
            addView(passwordInput)
        }
        
        AlertDialog.Builder(this)
            .setTitle("✏️ User bearbeiten")
            .setView(layout)
            .setPositiveButton("Speichern") { _, _ ->
                val newEmail = sanitizeInput(emailInput.text.toString())
                val newName = nameInput.text.toString().trim().takeIf { it.isNotEmpty() }
                val newPassword = passwordInput.text.toString().takeIf { it.isNotEmpty() }
                
                if (newEmail.isBlank()) {
                    Toast.makeText(this, "Email erforderlich", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                submitEditUser(userId, newEmail, newName, newPassword)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun submitEditUser(userId: String, email: String, displayName: String?, password: String?) {
        val token = currentToken ?: return
        
        val url = "https://$BACKEND_HOST/api/admin/users/$userId"
        val json = org.json.JSONObject().apply {
            put("email", email)
            if (displayName != null) put("displayName", displayName)
            if (password != null) put("password", password)
        }
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .patch(body)
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@AppActivity, "✅ User aktualisiert", Toast.LENGTH_SHORT).show()
                        openUserManagement() // Refresh
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: $errorBody", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun confirmDeleteUser(userId: String, email: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ User löschen?")
            .setMessage("Möchten Sie den User wirklich löschen?\n\n$email\n\nDiese Aktion kann nicht rückgängig gemacht werden!")
            .setPositiveButton("🗑️ Löschen") { _, _ ->
                deleteUser(userId, email)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun deleteUser(userId: String, email: String) {
        // Check permission
        if (!authClient.hasPermission("user.delete")) {
            Toast.makeText(this, "Keine Berechtigung zum Löschen", Toast.LENGTH_SHORT).show()
            return
        }
        
        val token = currentToken ?: return
        
        val url = "https://$BACKEND_HOST/api/admin/users/$userId"
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@AppActivity, "✅ User gelöscht: $email", Toast.LENGTH_SHORT).show()
                        openUserManagement() // Refresh
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: $errorBody", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun approveUser(userId: String, email: String) {
        val token = currentToken ?: return
        val url = "https://$BACKEND_HOST/api/admin/approve/$userId"
        
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody(null)) // Migrated from deprecated RequestBody.create()
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@AppActivity, "✅ $email wurde freigegeben", Toast.LENGTH_LONG).show()
                        openUserManagement() // Refresh
                    } else {
                        Toast.makeText(this@AppActivity, "Fehler: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun assignDomains(userId: String, email: String, currentDomains: List<String>, domainsArray: org.json.JSONArray?) {
        if (domainsArray == null) {
            Toast.makeText(this, "Keine Domains verfügbar", Toast.LENGTH_SHORT).show()
            return
        }
        
        val availableDomains = mutableListOf<String>()
        val selectedDomains = currentDomains.toMutableList()
        
        for (i in 0 until domainsArray.length()) {
            val domain = domainsArray.getJSONObject(i)
            availableDomains.add(domain.optString("id"))
        }
        
        val checkedItems = BooleanArray(availableDomains.size) { i ->
            availableDomains[i] in selectedDomains
        }
        
        AlertDialog.Builder(this)
            .setTitle("Domains für $email")
            .setMultiChoiceItems(availableDomains.toTypedArray(), checkedItems) { _, which, isChecked ->
                if (isChecked) {
                    selectedDomains.add(availableDomains[which])
                } else {
                    selectedDomains.remove(availableDomains[which])
                }
            }
            .setPositiveButton("Speichern") { _, _ ->
                saveDomainAssignment(userId, selectedDomains.distinct())
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun saveDomainAssignment(userId: String, domains: List<String>) {
        val token = currentToken ?: return
        val url = "https://$BACKEND_HOST/api/admin/assign-domains"
        
        val json = org.json.JSONObject().apply {
            put("targetUserId", userId)
            put("domainIds", org.json.JSONArray(domains))  // API v2.2: 'domainIds' not 'allowed_domains'
        }
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@AppActivity, "✅ Domains zugewiesen", Toast.LENGTH_LONG).show()
                        openUserManagement() // Refresh
                    } else {
                        Toast.makeText(this@AppActivity, "Fehler: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun openDomainManagement() {
        if (!authClient.hasPermission("domains.manage") && !authClient.hasPermission("*")) {
            Toast.makeText(this, "Keine Berechtigung für Domain-Verwaltung", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Lade Domains...", Toast.LENGTH_SHORT).show()
        val token = currentToken ?: return
        val url = "https://call-server.netdoc64.workers.dev/api/admin/domains"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Domain-Abruf fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && body != null) {
                        try {
                            val json = JSONObject(body)
                            // API v2.2: Flexible parsing for success wrapper or direct domains array
                            val domainsArray = if (json.has("success") && json.getBoolean("success")) {
                                json.getJSONArray("domains")
                            } else if (json.has("domains")) {
                                json.getJSONArray("domains")
                            } else {
                                null
                            }
                            
                            if (domainsArray != null) {
                                showDomainList(domainsArray)
                            } else {
                                Toast.makeText(this@AppActivity, "Fehler: ${json.optString("error", "Keine Domains gefunden")}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@AppActivity, "JSON-Parse-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun showDomainList(domains: JSONArray) {
        val domainItems = mutableListOf<String>()
        val domainObjects = mutableListOf<JSONObject>()
        
        for (i in 0 until domains.length()) {
            val domain = domains.getJSONObject(i)
            val domainId = domain.getString("id")
            val name = domain.optString("name", domainId)
            val isActive = domain.optBoolean("is_active", true)
            val status = if (isActive) "✅" else "❌"
            
            domainItems.add("$status $name ($domainId)")
            domainObjects.add(domain)
        }
        
        if (domainItems.isEmpty()) {
            Toast.makeText(this, "Keine Domains gefunden", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("🌐 Domain-Verwaltung")
            .setItems(domainItems.toTypedArray()) { _, which ->
                val selectedDomain = domainObjects[which]
                showDomainActions(selectedDomain)
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
    
    private fun showDomainActions(domain: JSONObject) {
        val domainId = domain.getString("id")
        val name = domain.optString("name", domainId)
        
        AlertDialog.Builder(this)
            .setTitle("Domain: $name")
            .setMessage("ID: $domainId\nStatus: ${if (domain.optBoolean("is_active", true)) "Aktiv" else "Inaktiv"}")
            .setPositiveButton("✏️ Bearbeiten") { _, _ ->
                showEditDomainDialog(domain)
            }
            .setNeutralButton("🔄 Status umschalten") { _, _ ->
                toggleDomainStatus(domain)
            }
            .setNegativeButton("Zurück", null)
            .show()
    }
    
    private fun showEditDomainDialog(domain: JSONObject) {
        val domainId = domain.getString("id")
        val currentName = domain.optString("name", domainId)
        val currentAliases = domain.optJSONArray("aliases")
        val aliasesStr = if (currentAliases != null) {
            (0 until currentAliases.length()).joinToString(", ") { currentAliases.getString(it) }
        } else ""
        
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }
        
        val nameInput = android.widget.EditText(this).apply {
            hint = "Domain Name"
            setText(currentName)
        }
        
        val aliasesInput = android.widget.EditText(this).apply {
            hint = "Aliases (kommagetrennt)"
            setText(aliasesStr)
        }
        
        container.addView(android.widget.TextView(this).apply {
            text = "Domain ID: $domainId (nicht änderbar)"
            setPadding(0, 0, 0, 20)
        })
        container.addView(android.widget.TextView(this).apply { text = "Name:" })
        container.addView(nameInput)
        container.addView(android.widget.TextView(this).apply { 
            text = "Aliases (optional):"
            setPadding(0, 20, 0, 0)
        })
        container.addView(aliasesInput)
        
        AlertDialog.Builder(this)
            .setTitle("✏️ Domain bearbeiten")
            .setView(container)
            .setPositiveButton("Speichern") { _, _ ->
                val newName = sanitizeInput(nameInput.text.toString())
                val aliasesText = aliasesInput.text.toString().trim()
                val newAliases = if (aliasesText.isNotEmpty()) {
                    aliasesText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
                
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Name darf nicht leer sein", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                updateDomain(domainId, newName, newAliases, domain.optBoolean("is_active", true))
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun toggleDomainStatus(domain: JSONObject) {
        val domainId = domain.getString("id")
        val currentStatus = domain.optBoolean("is_active", true)
        val newStatus = !currentStatus
        
        updateDomain(
            domainId,
            domain.optString("name", domainId),
            (domain.optJSONArray("aliases")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()),
            newStatus
        )
    }
    
    private fun updateDomain(domainId: String, name: String, aliases: List<String>, isActive: Boolean) {
        if (!authClient.hasPermission("domains.manage") && !authClient.hasPermission("*")) {
            Toast.makeText(this, "Keine Berechtigung", Toast.LENGTH_SHORT).show()
            return
        }
        
        val token = currentToken ?: return
        val url = "https://call-server.netdoc64.workers.dev/api/admin/domain/$domainId"
        
        val bodyJson = JSONObject().apply {
            put("name", name)
            put("aliases", JSONArray(aliases))
            put("is_active", isActive)
        }
        
        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .patch(requestBody)
            .build()
        
        Toast.makeText(this, "Aktualisiere Domain...", Toast.LENGTH_SHORT).show()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Domain-Update fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && body != null) {
                        try {
                            val json = JSONObject(body)
                            // API v2.2: Response is { status: "updated" }
                            val status = json.optString("status", "")
                            if (status == "updated" || json.optBoolean("success", false)) {
                                Toast.makeText(this@AppActivity, "✅ Domain erfolgreich aktualisiert", Toast.LENGTH_SHORT).show()
                                openDomainManagement() // Refresh list
                            } else {
                                Toast.makeText(this@AppActivity, "Fehler: ${json.optString("error", "Unbekannter Fehler")}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@AppActivity, "JSON-Parse-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: ${body ?: "Keine Details"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    // --- Role Management (API v2.2) ---
    
    private fun openRoleManagement() {
        if (!authClient.hasPermission("roles.manage") && !authClient.hasPermission("*")) {
            Toast.makeText(this, "Keine Berechtigung für Rollen-Verwaltung", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("🎭 Rollen-Verwaltung")
            .setMessage("Wählen Sie eine Aktion:")
            .setPositiveButton("➕ Neue Rolle") { _, _ ->
                createNewRole()
            }
            .setNeutralButton("📋 Rollen anzeigen") { _, _ ->
                showRoleList()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
    
    private fun createNewRole() {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }
        
        val nameInput = android.widget.EditText(this).apply {
            hint = "Rollenname (z.B. 'agent', 'supervisor')"
        }
        
        val descriptionInput = android.widget.EditText(this).apply {
            hint = "Beschreibung (z.B. 'Kundenbetreuung')"
        }
        
        val levelInput = android.widget.EditText(this).apply {
            hint = "Level (0-100, höher = mehr Rechte)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        
        val permissionsInput = android.widget.EditText(this).apply {
            hint = "Permissions (kommagetrennt, z.B. 'call.receive,call.view.all')"
            minLines = 3
        }
        
        container.addView(android.widget.TextView(this).apply { text = "Rollenname:" })
        container.addView(nameInput)
        container.addView(android.widget.TextView(this).apply { 
            text = "Beschreibung:"
            setPadding(0, 20, 0, 0)
        })
        container.addView(descriptionInput)
        container.addView(android.widget.TextView(this).apply { 
            text = "Level (0-100):"
            setPadding(0, 20, 0, 0)
        })
        container.addView(levelInput)
        container.addView(android.widget.TextView(this).apply { 
            text = "Permissions:"
            setPadding(0, 20, 0, 0)
        })
        container.addView(permissionsInput)
        container.addView(android.widget.TextView(this).apply {
            text = "\nBeispiel Permissions:\n• call.receive\n• call.view.all\n• admin.users.manage\n• admin.domains.manage\n• * (SuperAdmin)"
            textSize = 12f
            setPadding(0, 10, 0, 0)
        })
        
        AlertDialog.Builder(this)
            .setTitle("➕ Neue Rolle erstellen")
            .setView(container)
            .setPositiveButton("Erstellen") { _, _ ->
                val name = sanitizeInput(nameInput.text.toString())
                val description = sanitizeInput(descriptionInput.text.toString())
                val levelStr = levelInput.text.toString()
                val permissionsText = permissionsInput.text.toString().trim()
                
                if (name.isEmpty()) {
                    Toast.makeText(this, "Rollenname erforderlich", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (description.isEmpty()) {
                    Toast.makeText(this, "Beschreibung erforderlich", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val level = levelStr.toIntOrNull() ?: 0
                val permissions = if (permissionsText.isNotEmpty()) {
                    permissionsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
                
                createRole(name, description, level, permissions)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun createRole(name: String, description: String, level: Int, permissions: List<String>) {
        val token = currentToken ?: return
        val url = "https://call-server.netdoc64.workers.dev/api/admin/roles"
        
        val bodyJson = JSONObject().apply {
            put("name", name)
            put("description", description)  // API v2.2 required field
            put("level", level)
            put("permissions", JSONArray(permissions))
        }
        
        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()
        
        Toast.makeText(this, "Erstelle Rolle...", Toast.LENGTH_SHORT).show()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Rollen-Erstellung fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && body != null) {
                        try {
                            val json = JSONObject(body)
                            // API v2.2: Response is { status: "created", roleId: "..." }
                            val status = json.optString("status", "")
                            if (status == "created" || json.optBoolean("success", false)) {
                                val roleId = json.optString("roleId", "")
                                Toast.makeText(this@AppActivity, "✅ Rolle erstellt${if (roleId.isNotEmpty()) " ($roleId)" else ""}", Toast.LENGTH_SHORT).show()
                                showRoleList()
                            } else {
                                Toast.makeText(this@AppActivity, "Fehler: ${json.optString("error", "Unbekannter Fehler")}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@AppActivity, "JSON-Parse-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: ${body ?: "Keine Details"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun showRoleList() {
        val token = currentToken ?: return
        val url = "https://call-server.netdoc64.workers.dev/api/admin/data"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        
        Toast.makeText(this, "Lade Rollen...", Toast.LENGTH_SHORT).show()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Rollen-Abruf fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && body != null) {
                        try {
                            val json = JSONObject(body)
                            // API v2.2: /api/admin/data returns { users: [], domains: [], roles: [] }
                            val rolesArray = json.optJSONArray("roles")
                            if (rolesArray != null && rolesArray.length() > 0) {
                                displayRoleList(rolesArray)
                            } else {
                                Toast.makeText(this@AppActivity, "Keine Rollen gefunden", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@AppActivity, "JSON-Parse-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun displayRoleList(roles: JSONArray) {
        val roleItems = mutableListOf<String>()
        val roleObjects = mutableListOf<JSONObject>()
        
        for (i in 0 until roles.length()) {
            val role = roles.getJSONObject(i)
            val name = role.getString("name")
            val level = role.optInt("level", 0)
            val permCount = role.optJSONArray("permissions")?.length() ?: 0
            
            roleItems.add("🎭 $name (Level $level, $permCount Permissions)")
            roleObjects.add(role)
        }
        
        if (roleItems.isEmpty()) {
            Toast.makeText(this, "Keine Rollen gefunden", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("📋 Rollen-Liste")
            .setItems(roleItems.toTypedArray()) { _, which ->
                val selectedRole = roleObjects[which]
                showRoleActions(selectedRole)
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
    
    private fun showRoleActions(role: JSONObject) {
        val roleName = role.getString("name")
        val level = role.optInt("level", 0)
        val permissions = role.optJSONArray("permissions")
        val permList = if (permissions != null) {
            (0 until permissions.length()).joinToString("\n• ") { permissions.getString(it) }
        } else "Keine"
        
        AlertDialog.Builder(this)
            .setTitle("Rolle: $roleName")
            .setMessage("Level: $level\n\nPermissions:\n• $permList")
            .setPositiveButton("✏️ Bearbeiten") { _, _ ->
                editRole(role)
            }
            .setNeutralButton("🗑️ Löschen") { _, _ ->
                confirmDeleteRole(role)
            }
            .setNegativeButton("Zurück", null)
            .show()
    }
    
    private fun editRole(role: JSONObject) {
        val roleId = role.getString("id")
        val currentName = role.getString("name")
        val currentDescription = role.optString("description", "")
        val currentLevel = role.optInt("level", 0)
        val currentPermissions = role.optJSONArray("permissions")
        val permStr = if (currentPermissions != null) {
            (0 until currentPermissions.length()).joinToString(", ") { currentPermissions.getString(it) }
        } else ""
        
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }
        
        val nameInput = android.widget.EditText(this).apply {
            hint = "Rollenname"
            setText(currentName)
        }
        
        val descriptionInput = android.widget.EditText(this).apply {
            hint = "Beschreibung"
            setText(currentDescription)
        }
        
        val levelInput = android.widget.EditText(this).apply {
            hint = "Level (0-100)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentLevel.toString())
        }
        
        val permissionsInput = android.widget.EditText(this).apply {
            hint = "Permissions (kommagetrennt)"
            setText(permStr)
            minLines = 3
        }
        
        container.addView(android.widget.TextView(this).apply { 
            text = "Rolle ID: $roleId (nicht änderbar)"
            setPadding(0, 0, 0, 20)
        })
        container.addView(android.widget.TextView(this).apply { text = "Rollenname:" })
        container.addView(nameInput)
        container.addView(android.widget.TextView(this).apply { 
            text = "Beschreibung:"
            setPadding(0, 20, 0, 0)
        })
        container.addView(descriptionInput)
        container.addView(android.widget.TextView(this).apply { 
            text = "Level (0-100):"
            setPadding(0, 20, 0, 0)
        })
        container.addView(levelInput)
        container.addView(android.widget.TextView(this).apply { 
            text = "Permissions:"
            setPadding(0, 20, 0, 0)
        })
        container.addView(permissionsInput)
        
        AlertDialog.Builder(this)
            .setTitle("✏️ Rolle bearbeiten")
            .setView(container)
            .setPositiveButton("Speichern") { _, _ ->
                val newName = sanitizeInput(nameInput.text.toString())
                val newDescription = sanitizeInput(descriptionInput.text.toString())
                val levelStr = levelInput.text.toString()
                val permissionsText = permissionsInput.text.toString().trim()
                
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Rollenname erforderlich", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val level = levelStr.toIntOrNull() ?: 0
                val permissions = if (permissionsText.isNotEmpty()) {
                    permissionsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
                
                updateRole(roleId, newName, newDescription, level, permissions)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun updateRole(roleId: String, name: String, description: String, level: Int, permissions: List<String>) {
        val token = currentToken ?: return
        val url = "https://call-server.netdoc64.workers.dev/api/admin/roles/$roleId"
        
        val bodyJson = JSONObject().apply {
            put("name", name)
            put("description", description)  // API v2.2 field
            put("level", level)
            put("permissions", JSONArray(permissions))
        }
        
        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .patch(requestBody)
            .build()
        
        Toast.makeText(this, "Aktualisiere Rolle...", Toast.LENGTH_SHORT).show()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Rollen-Update fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && body != null) {
                        try {
                            val json = JSONObject(body)
                            // API v2.2: Response is { status: "updated" }
                            val status = json.optString("status", "")
                            if (status == "updated" || json.optBoolean("success", false)) {
                                Toast.makeText(this@AppActivity, "✅ Rolle aktualisiert", Toast.LENGTH_SHORT).show()
                                showRoleList() // Refresh
                            } else {
                                Toast.makeText(this@AppActivity, "Fehler: ${json.optString("error", "Unbekannter Fehler")}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@AppActivity, "JSON-Parse-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: ${body ?: "Keine Details"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun confirmDeleteRole(role: JSONObject) {
        val roleId = role.getString("id")
        val roleName = role.getString("name")
        
        AlertDialog.Builder(this)
            .setTitle("⚠️ Rolle löschen?")
            .setMessage("Möchten Sie die Rolle '$roleName' wirklich löschen?\n\nAlle Benutzer mit dieser Rolle verlieren ihre Zugriffsrechte.")
            .setPositiveButton("Löschen") { _, _ ->
                deleteRole(roleId, roleName)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun deleteRole(roleId: String, roleName: String) {
        val token = currentToken ?: return
        val url = "https://call-server.netdoc64.workers.dev/api/admin/roles/$roleId"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        
        Toast.makeText(this, "Lösche Rolle...", Toast.LENGTH_SHORT).show()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Rollen-Löschung fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && body != null) {
                        try {
                            val json = JSONObject(body)
                            // API v2.2: Response is { status: "deleted" }
                            val status = json.optString("status", "")
                            if (status == "deleted" || json.optBoolean("success", false)) {
                                Toast.makeText(this@AppActivity, "✅ Rolle '$roleName' gelöscht", Toast.LENGTH_SHORT).show()
                                showRoleList()
                            } else {
                                Toast.makeText(this@AppActivity, "Fehler: ${json.optString("error", "Unbekannter Fehler")}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@AppActivity, "JSON-Parse-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: ${body ?: "Keine Details"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    private fun openSupervisorPanel() {
        if (currentRole !in listOf("supervisor", "superadmin")) {
            Toast.makeText(this, "Keine Berechtigung", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("👁️ Supervisor Panel")
            .setMessage("Supervisor-Funktionen:\n\n" +
                "• Live-Monitoring von Anrufen\n" +
                "• Call-Statistiken\n" +
                "• Agent-Performance\n" +
                "• Ausgehende Calls (NEW)")
            .setPositiveButton("📹 Live Calls") { _, _ ->
                startLiveMonitoring()
            }
            .setNeutralButton("📞 Call initiieren") { _, _ ->
                initiateOutgoingCall()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
    
    private fun startLiveMonitoring() {
        val token = currentToken ?: return
        
        // Lade aktive Calls aus der Datenbank
        statusTextView.text = "Status: Lade aktive Calls..."
        
        val url = "https://$BACKEND_HOST/api/admin/data"
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                response.use {
                    if (!it.isSuccessful) {
                        safeRunOnUiThread {
                            Toast.makeText(this@AppActivity, "Fehler: ${it.code}", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    
                    // In einem realen System würden hier aktive Calls aus der DB kommen
                    // Für Demo: Zeige Domain-Auswahl zum Monitoring
                    val jsonData = org.json.JSONObject(it.body?.string() ?: "{}")
                    val domainsArray = jsonData.optJSONArray("domains")
                    
                    safeRunOnUiThread {
                        showMonitoringDomainSelection(domainsArray)
                    }
                }
            }
        })
    }
    
    private fun showMonitoringDomainSelection(domainsArray: org.json.JSONArray?) {
        if (domainsArray == null || domainsArray.length() == 0) {
            Toast.makeText(this, "Keine Domains gefunden", Toast.LENGTH_SHORT).show()
            return
        }
        
        val domainList = mutableListOf<String>()
        for (i in 0 until domainsArray.length()) {
            val domain = domainsArray.getJSONObject(i)
            domainList.add(domain.optString("id"))
        }
        
        AlertDialog.Builder(this)
            .setTitle("Domain für Monitoring wählen")
            .setItems(domainList.toTypedArray()) { _, which ->
                val selectedDomain = domainList[which]
                // Zeige aktive Calls dieser Domain
                showActiveCallsForDomain(selectedDomain)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun showActiveCallsForDomain(domainId: String) {
        // Simuliere aktive Calls (in Produktion: aus D1 calls Tabelle)
        val activeCalls = listOf(
            "Call 1: ${domainId}__session_abc123",
            "Call 2: ${domainId}__session_def456",
            "Call 3: ${domainId}__session_ghi789"
        )
        
        if (activeCalls.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("✅ Keine aktiven Calls")
                .setMessage("Aktuell keine Anrufe in $domainId aktiv.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("📹 Aktive Calls ($domainId)")
            .setItems(activeCalls.toTypedArray()) { _, which ->
                // Extrahiere Room-ID aus dem String
                val roomId = activeCalls[which].substringAfter(": ")
                joinCallAsMonitor(roomId)
            }
            .setNegativeButton("Zurück", null)
            .show()
    }
    
    private fun joinCallAsMonitor(roomId: String) {
        val token = currentToken ?: return
        
        AlertDialog.Builder(this)
            .setTitle("👁️ Monitoring-Modus")
            .setMessage("Möchten Sie diesem Call beitreten?\n\n" +
                "Room: $roomId\n\n" +
                "Im Monitor-Modus können Sie:\n" +
                "• Audio mithören\n" +
                "• Chat-Nachrichten sehen\n" +
                "• NICHT selbst sprechen/schreiben")
            .setPositiveButton("🎬 Beitreten") { _, _ ->
                startMonitoringCall(roomId, token)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun startMonitoringCall(roomId: String, token: String) {
        isMonitoring = true
        monitoringRoomId = roomId
        
        // Erstelle separaten SignalingClient für Monitoring
        monitoringClient = SignalingClient(object : SignalingListener {
            override fun onWebSocketOpen() {
                safeRunOnUiThread {
                    statusTextView.text = "Status: 👁️ Monitoring $roomId"
                    Toast.makeText(this@AppActivity, "Monitoring aktiv", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onNewSignalReceived(message: org.json.JSONObject) {
                safeRunOnUiThread {
                    // Zeige Monitoring-Daten an
                    handleMonitoringMessage(message)
                }
            }
            
            override fun onWebSocketClosed() {
                safeRunOnUiThread {
                    statusTextView.text = "Status: Monitoring beendet"
                    isMonitoring = false
                }
            }
            
            override fun onError(message: String) {
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Monitoring Error: $message", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onReconnecting(attempt: Int, delayMs: Int) {}
            override fun onReconnectFailed() {}
        }, BACKEND_HOST)
        
        // Verbinde mit mode=monitor Parameter
        monitoringClient?.connectWithMode(roomId, token, "monitor")
        
        // Zeige Monitoring-UI
        showMonitoringUI(roomId)
    }
    
    private fun handleMonitoringMessage(message: org.json.JSONObject) {
        val type = message.optString("type")
        
        when (type) {
            "chat" -> {
                val sender = message.optString("senderRole", "Unknown")
                val text = message.optString("text", "")
                appendMonitoringLog("💬 [$sender]: $text")
            }
            "offer", "answer" -> {
                appendMonitoringLog("🔊 WebRTC Signal: $type")
            }
            "system" -> {
                val action = message.optString("action")
                appendMonitoringLog("⚙️ System: $action")
            }
        }
    }
    
    private fun appendMonitoringLog(text: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logText = "[$timestamp] $text\n"
        
        // Füge zur Chat-View hinzu (oder eigenes Monitoring-TextView)
        runOnUiThread {
            val currentText = chatMessagesView.text.toString()
            chatMessagesView.text = currentText + logText
        }
    }
    
    private fun showMonitoringUI(roomId: String) {
        // Verstecke normale Call-UI, zeige Monitoring-Ansicht
        liveVisitorsRecyclerView.visibility = View.GONE
        activeCallLayout.visibility = View.VISIBLE
        activeCallInfo.text = "👁️ Monitoring: $roomId"
        
        // Chat-Input deaktivieren (nur lesen)
        chatInput.isEnabled = false
        chatInput.hint = "Monitoring-Modus (nur lesen)"
        chatSendButton.isEnabled = false
        
        // Ändere "Auflegen" Button zu "Monitoring beenden"
        callEndButton.text = "MONITORING BEENDEN"
        callEndButton.setOnClickListener {
            stopMonitoring()
        }
    }
    
    private fun stopMonitoring() {
        if (isMonitoring) {
            monitoringClient?.disconnect()
            monitoringClient = null
            isMonitoring = false
            monitoringRoomId = null
        }
        
        // Zurück zur normalen Ansicht
        showVisitorsTab()
        chatInput.isEnabled = true
        chatInput.hint = "Nachricht eingeben..."
        chatSendButton.isEnabled = true
        callEndButton.text = "AUFLEGEN"
        callEndButton.setOnClickListener { endCall() }
        chatMessagesView.text = ""
        
        statusTextView.text = "Status: Monitoring beendet"
    }
    
    private fun showCallStatistics() {
        val token = currentToken ?: return
        
        // Check permission
        if (!authClient.hasPermission("analytics.view.team") && !authClient.hasPermission("analytics.view.all")) {
            Toast.makeText(this, "Keine Berechtigung für Statistiken", Toast.LENGTH_SHORT).show()
            return
        }
        
        statusTextView.text = "Status: Lade Statistiken..."
        
        val url = "https://$BACKEND_HOST/api/admin/stats/summary"
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_LONG).show()
                    statusTextView.text = "Status: Fehler"
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                response.use {
                    if (!it.isSuccessful) {
                        safeRunOnUiThread {
                            Toast.makeText(this@AppActivity, "Fehler: ${it.code}", Toast.LENGTH_LONG).show()
                            statusTextView.text = "Status: Fehler ${it.code}"
                        }
                        return
                    }
                    
                    val jsonData = org.json.JSONObject(it.body?.string() ?: "{}")
                    safeRunOnUiThread {
                        displayStatistics(jsonData)
                        statusTextView.text = "Status: Statistiken angezeigt"
                    }
                }
            }
        })
    }
    
    private fun displayStatistics(data: org.json.JSONObject) {
        val summary = data.optJSONObject("summary")
        val breakdown = data.optJSONObject("breakdown")
        val agentActivity = data.optJSONObject("agentActivity")
        
        if (summary == null) {
            Toast.makeText(this, "Keine Statistiken verfügbar", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Parse summary data (API v2.2 new fields)
        val totalCalls = summary.optInt("totalCalls", 0)
        val activeCalls = summary.optInt("activeCalls", 0)
        val callsToday = summary.optInt("callsToday", 0)
        val avgDuration = summary.optInt("avgCallDuration", 0)
        val queuedCalls = summary.optInt("queuedCalls", 0)  // NEW
        val avgWaitTime = summary.optInt("avgWaitTime", 0)  // NEW
        val missedToday = summary.optInt("missedToday", 0)  // NEW
        
        // Agent activity (NEW)
        val available = agentActivity?.optInt("available", 0) ?: 0
        val busy = agentActivity?.optInt("busy", 0) ?: 0
        val onBreak = agentActivity?.optInt("break", 0) ?: 0
        val offline = agentActivity?.optInt("offline", 0) ?: 0
        
        val statsMessage = buildString {
            append("📊 Gesamt-Statistiken\n\n")
            append("Calls gesamt: $totalCalls\n")
            append("Aktive Calls: $activeCalls\n")
            append("Calls heute: $callsToday\n")
            append("⏱️ Ø Dauer: ${avgDuration}s\n\n")
            
            append("📋 Warteschlange (NEW)\n")
            append("In Warteschlange: $queuedCalls\n")
            append("⏳ Ø Wartezeit: ${avgWaitTime}s\n")
            append("❌ Verpasst heute: $missedToday\n\n")
            
            append("👥 Agent-Status (NEW)\n")
            append("✅ Verfügbar: $available\n")
            append("📞 Beschäftigt: $busy\n")
            append("☕ Pause: $onBreak\n")
            append("⚫ Offline: $offline\n\n")
            
            // Domain breakdown
            if (breakdown != null) {
                val byDomain = breakdown.optJSONArray("byDomain")
                if (byDomain != null && byDomain.length() > 0) {
                    append("🌐 Nach Domain:\n")
                    for (i in 0 until minOf(5, byDomain.length())) {
                        val entry = byDomain.getJSONObject(i)
                        val domain = entry.optString("domain_id", "unknown")
                        val count = entry.optInt("count", 0)
                        append("  • $domain: $count\n")
                    }
                }
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("📊 Call-Statistiken")
            .setMessage(statsMessage)
            .setPositiveButton("OK", null)
            .setNeutralButton("🔄 Aktualisieren") { _, _ ->
                showCallStatistics()
            }
            .show()
    }
    
    // --- Ende Admin/Supervisor Funktionen ---
    
    // --- Hilfsklassen ---

    // 1. VisitorAdapter: Zeigt die Live-Besucherliste an
    class VisitorAdapter(
        private val visitors: List<Visitor>, 
        private val callAction: (Visitor) -> Unit,
        private val chatAction: (Visitor) -> Unit,
        private val context: AppCompatActivity
    ) : RecyclerView.Adapter<VisitorAdapter.VisitorViewHolder>() {
        
        class VisitorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.visitor_name)
            val domain: TextView = view.findViewById(R.id.visitor_domain)
            val chatButton: Button = view.findViewById(R.id.chat_visitor_button)
            val callButton: Button = view.findViewById(R.id.call_visitor_button)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VisitorViewHolder {
            val itemLayout = LayoutInflater.from(parent.context).inflate(R.layout.visitor_list_item_glass, parent, false)
            return VisitorViewHolder(itemLayout)
        }

        override fun onBindViewHolder(holder: VisitorViewHolder, position: Int) {
            val visitor = visitors[position]
            holder.name.text = visitor.callerName
            holder.domain.text = visitor.domain
            
            // Chat Button - nur WebSocket, kein WebRTC
            holder.chatButton.setOnClickListener {
                chatAction(visitor)
            }
            
            // Call Button - WebSocket + WebRTC
            holder.callButton.setOnClickListener {
                callAction(visitor)
            }
        }
        
        override fun getItemCount() = visitors.size
    }

    // 2. PeerConnectionClient: WebRTC-Logik mit Audio-Support
    class PeerConnectionClient(private val factory: PeerConnectionFactory, private val activity: AppActivity? = null) {
        var peerConnection: PeerConnection? = null
        var onIceCandidateCallback: ((IceCandidate) -> Unit)? = null
        private var localAudioTrack: AudioTrack? = null
        private var audioSource: AudioSource? = null // Bug #21: Track audioSource for disposal
        private val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        init {
            // Audio-Track mit Echo Cancellation und Noise Suppression
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            
            audioSource = factory.createAudioSource(audioConstraints)
            localAudioTrack = factory.createAudioTrack("audio1", audioSource!!)
            
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
            peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) { onIceCandidateCallback?.invoke(candidate) }
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: DataChannel) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    // WebRTC Connection Quality Monitoring
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED -> 
                            activity?.runOnUiThread { activity.updateConnectionQuality("excellent") }
                        PeerConnection.IceConnectionState.COMPLETED -> 
                            activity?.runOnUiThread { activity.updateConnectionQuality("excellent") }
                        PeerConnection.IceConnectionState.CHECKING -> 
                            activity?.runOnUiThread { activity.updateConnectionQuality("good") }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            activity?.runOnUiThread { activity.updateConnectionQuality("poor") }
                            // Report WebRTC connection issue
                            activity?.errorReporter?.reportWebRTCError(
                                message = "WebRTC ICE connection disconnected",
                                context = mapOf(
                                    "state" to "DISCONNECTED",
                                    "call_id" to (activity.activeCallSessionId ?: "unknown")
                                ),
                                authToken = activity.currentToken
                            )
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            activity?.runOnUiThread { activity.updateConnectionQuality("bad") }
                            // Report critical WebRTC failure
                            activity?.errorReporter?.reportWebRTCError(
                                message = "WebRTC ICE connection failed - no connectivity established",
                                context = mapOf(
                                    "state" to "FAILED",
                                    "call_id" to (activity.activeCallSessionId ?: "unknown"),
                                    "severity" to "critical"
                                ),
                                authToken = activity.currentToken
                            )
                        }
                        else -> {}
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
                override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
                override fun onRemoveTrack(rtpReceiver: RtpReceiver) {}
                override fun onRenegotiationNeeded() {
                    // Handle renegotiation for network changes
                    Log.d("WebRTC", "Renegotiation needed")
                }
            })
            
            // Audio-Track zur PeerConnection hinzufügen
            localAudioTrack?.let { track ->
                peerConnection?.addTrack(track, listOf("stream1"))
                Log.d("WebRTC", "Audio track added to PeerConnection")
            }
        }
        
        fun createOffer(observer: SdpObserver) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            peerConnection?.createOffer(observer, constraints)
        }
        fun createAnswer(observer: SdpObserver) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            peerConnection?.createAnswer(observer, constraints)
        }
        
        fun handleAnswer(sdpJson: JSONObject) {
            val pc = peerConnection
            if (pc == null) {
                Log.e("PeerConnectionClient", "Cannot handle answer: PeerConnection is null")
                return
            }
            
            // Support both new 'data' format and old 'sdp' format (backward compatibility)
            val sdpString = if (sdpJson.has("data")) {
                sdpJson.getJSONObject("data").getString("sdp")
            } else {
                sdpJson.getString("sdp")
            }
            
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {}
                override fun onSetFailure(error: String) {}
            }, answer)
        }

        fun handleIceCandidate(candidateJson: JSONObject) {
            val pc = peerConnection
            if (pc == null) {
                Log.e("PeerConnectionClient", "Cannot add ICE candidate: PeerConnection is null")
                return
            }
            
            // Support both new 'data' format and old format (backward compatibility)
            val candidateData = if (candidateJson.has("data")) {
                candidateJson.getJSONObject("data")
            } else {
                candidateJson
            }
            
            val candidate = IceCandidate(
                candidateData.getString("sdpMid"),
                candidateData.getInt("sdpMLineIndex"),
                candidateData.getString("candidate")
            )
            pc.addIceCandidate(candidate)
        }

        fun close() {
            localAudioTrack?.dispose()
            localAudioTrack = null
            
            peerConnection?.close()
            peerConnection = null // Bug #20: Set to null after close
            
            audioSource?.dispose() // Bug #21: Dispose audioSource
            audioSource = null
        }
    }
}

// NOTE: Da Android Studio das Layout des RecyclerView-Items im Adapter erwartet, 
// fügen wir hier ein sehr rudimentäres XML-Template hinzu. In einem echten Projekt 
// müssten Sie eine separate visitor_list_item.xml Datei erstellen.
// Da ich keine neue Datei erstellen soll, füge ich das in die AppActivity ein, 
// aber es ist stark davon abgeraten. Wir nutzen den Code-Block als work-around:
// ******************************************************************************
/*
<layout>
    <LinearLayout android:orientation="horizontal" android:padding="16dp" android:background="#2E2E2E">
        <LinearLayout android:orientation="vertical" android:layout_weight="1">
            <TextView android:id="@+id/visitor_name" android:textSize="16sp" android:textColor="#FFFFFF" android:text="Besucher Name"/>
            <TextView android:id="@+id/visitor_domain" android:textSize="12sp" android:textColor="#BBBBBB" android:text="Domain"/>
        </LinearLayout>
        <Button android:id="@+id/call_visitor_button" android:text="Anrufen" android:backgroundTint="#4CAF50"/>
    </LinearLayout>
</layout>
*/
// ******************************************************************************
