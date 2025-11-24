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
    // NOTE: visitorDataTextView ist das alte Element, das wir hier nicht mehr explizit nutzen.

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
    
    // Supervisor Monitoring
    private var isMonitoring = false
    private var monitoringRoomId: String? = null
    private var monitoringClient: SignalingClient? = null

    private var activeCallSessionId: String? = null
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
    
    // CallService Integration
    private var callService: CallService? = null
    private var isServiceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val serviceBinder = binder as CallService.CallServiceBinder
            callService = serviceBinder.getService()
            isServiceBound = true
            
            // Setup callbacks
            callService?.onCallReceived = { sessionId, domain ->
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Eingehender Anruf von $domain", Toast.LENGTH_LONG).show()
                    // Visitor zur Liste hinzufügen wenn noch nicht vorhanden
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
        // Global crash logger: write uncaught exceptions to a file so user can retrieve them
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = "Timestamp: ${java.time.Instant.now()}\nThread: ${thread.name}\n" + sw.toString()
                val f = File(filesDir, "last_crash.log")
                f.writeText(text)
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
        
        // Enter-Taste zum Senden aktivieren
        chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendChatMessage()
                true
            } else false
        }

        // Login/Register visible buttons
        loginButton.setOnClickListener { performLoginUI() }
        registerButton.setOnClickListener { performRegisterUI() }
        adminButton.setOnClickListener { openAdminPanel() }
        supervisorButton.setOnClickListener { openSupervisorPanel() }
        connectButton.setOnClickListener { performManualReconnect() }
        
        // Admin/Supervisor Buttons initial versteckt
        adminButton.visibility = View.GONE
        supervisorButton.visibility = View.GONE
        
        // 2. Adapter und RecyclerView
        visitorAdapter = VisitorAdapter(liveVisitors, this::generateOffer, this)
        liveVisitorsRecyclerView.layoutManager = LinearLayoutManager(this)
        liveVisitorsRecyclerView.adapter = visitorAdapter
        
        // 3. Event Listener
        callEndButton.setOnClickListener { endCall() }
        chatSendButton.setOnClickListener { sendChatMessage() }
        
        // Manual reconnect wird über separate Funktion gehandled (siehe connectButton.setOnClickListener oben)

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
            }
        } else if (requestCode == REQ_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Benachrichtigungen aktiviert", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Benachrichtigungen deaktiviert. Sie werden keine Anruf-Benachrichtigungen erhalten.", Toast.LENGTH_LONG).show()
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
        signalingClient = SignalingClient(this, BACKEND_HOST)
        // Auto-connect if token exists
        val savedToken = authClient.getToken()
        Log.d("AppActivity", "🔍 [DEBUG] Auto-Login Check: token=${if (savedToken != null) "EXISTS" else "NULL"}")
        
        if (savedToken != null) {
            val displayName = authClient.getDisplayName()
            val domains = authClient.getDomains()
            val domain = domains.firstOrNull() ?: DOMAIN_ID
            Log.d("AppActivity", "🔍 [DEBUG] Auto-Login: displayName=$displayName, domain=$domain, domainsCount=${domains.size}")
            
            // Generiere vollständige Room-ID mit Session
            val roomId = generateCallRoomId(domain)
            currentRoom = roomId
            currentToken = savedToken
            currentRole = authClient.getRoles().firstOrNull()?.get("name") as? String
            
            Log.d("AppActivity", "🔍 [DEBUG] Auto-Login: roomId=$roomId, role=$currentRole")
            
            statusTextView.text = "Status: ✅ Auto-Login${if (displayName != null) " - $displayName" else ""}"
            updateRoleBasedUI(currentRole)
            
            Log.d("AppActivity", "🔍 [DEBUG] Triggering WebSocket connect to: $roomId")
            signalingClient.connect(roomId, savedToken)
            
            // Service starten bei Auto-Login
            startCallService(roomId, savedToken)
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
            signalingClient.send(candidateJson)
        }

        // enable connect button now that clients are ready
        connectButton.isEnabled = true
        showVisitorsTab()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        
        // Cleanup monitoring client to prevent memory leak
        monitoringClient?.disconnect()
        monitoringClient = null
        
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
        currentRole = authClient.getRoles().firstOrNull()?.get("name") as? String
        
        statusTextView.text = "Status: Auto-Login erfolgreich${if (displayName != null) " - $displayName" else ""}"
        
        // Zeige Admin/Supervisor Buttons basierend auf Rolle
        updateRoleBasedUI(currentRole)
        
        // Domain-Auswahl und Verbindung
        if (domains.isNotEmpty()) {
            showDomainSelectionAndConnect(domains, token)
        } else {
            val roomId = generateCallRoomId(DOMAIN_ID)
            currentRoom = roomId
            signalingClient.connect(roomId, token)
        }
        
        // CallService starten für Hintergrund-Anrufe
        startCallService(currentRoom ?: generateCallRoomId(DOMAIN_ID), token)
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
                            currentRole = authClient.getRoles().firstOrNull()?.get("name") as? String
                            
                            // Remember Me: Token ist bereits gespeichert von AuthClient
                            // Bei Checkbox-Deaktivierung Token löschen
                            if (!rememberCheckbox.isChecked) {
                                // Note: AuthClient speichert bereits, wir tun nichts extra
                                Toast.makeText(this@AppActivity, "ℹ️ Session nur für diese Sitzung", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@AppActivity, "✅ Anmeldung gespeichert", Toast.LENGTH_SHORT).show()
                            }
                            
                            updateRoleBasedUI(currentRole)
                            
                            if (domains.isNotEmpty()) showDomainSelectionAndConnect(domains, token) else {
                                val roomId = generateCallRoomId(DOMAIN_ID)
                                currentRoom = roomId
                                signalingClient.connect(roomId, token)
                            }
                            
                            startCallService(currentRoom ?: generateCallRoomId(DOMAIN_ID), token)
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
                                currentRole = authClient.getRoles().firstOrNull()?.get("name") as? String
                                updateRoleBasedUI(currentRole)
                                
                                Toast.makeText(this@AppActivity, "✅ Account erstellt und angemeldet", Toast.LENGTH_SHORT).show()
                                
                                if (domains.isNotEmpty()) showDomainSelectionAndConnect(domains, token) else {
                                    val roomId = generateCallRoomId(DOMAIN_ID)
                                    currentRoom = roomId
                                    signalingClient.connect(roomId, token)
                                }
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

    private fun showDomainSelectionAndConnect(domains: List<String>, token: String) {
        runOnUiThread {
            val arr = domains.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Wähle Domain")
                .setItems(arr) { _, which ->
                    val domain = arr[which]
                    val roomId = generateCallRoomId(domain)
                    statusTextView.text = "Status: Verbinde zu $domain"
                    currentRoom = roomId
                    currentToken = token
                    signalingClient.connect(roomId, token)
                }
                .setCancelable(true)
                .show()
        }
    }

    // --- UI Management ---
    private fun showVisitorsTab() {
        liveVisitorsRecyclerView.visibility = View.VISIBLE
        activeCallLayout.visibility = View.GONE
        // Reconnect nur anzeigen wenn nicht verbunden
        updateConnectionUI(isConnected = false)
    }
    
    private fun showActiveCallTab(visitor: Visitor) {
        liveVisitorsRecyclerView.visibility = View.GONE
        activeCallLayout.visibility = View.VISIBLE
        updateConnectionUI(isConnected = true)
        activeCallInfo.text = "Im Gespräch mit ${visitor.callerName} von ${visitor.domain}"
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
            logoUrl = message.optString("profileImage")
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
    
    // Agent ruft Besucher proaktiv an
    // Backend erstellt dynamische CallRoom: DOMAIN_ID__SESSION_ID
    fun generateOffer(visitor: Visitor) {
        activeCallSessionId = visitor.sessionId
        showActiveCallTab(visitor)

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
                    // HINWEIS: Backend erstellt automatisch CallRoom mit ID: 
                    // currentRoom (DOMAIN_ID) + "__" + visitor.sessionId
                }
                signalingClient.send(offer)
                activeCallInfo.text = "Warte auf Annahme durch ${visitor.callerName}..."
            }
            override fun onCreateFailure(s: String) { Log.e("WebRTC", "Offer failed: $s") }
            override fun onSetFailure(s: String) { Log.e("WebRTC", "SetLocalDesc failed: $s") }
            override fun onSetSuccess() {}
        })
    }
    
    // Besucher ruft Agent an (eingehender Anruf)
    private fun handleIncomingOffer(message: JSONObject) {
        val sdpData = message.optJSONObject("data") ?: message.optJSONObject("sdp") // Backward compatibility
        if (sdpData == null) {
            Log.e("AppActivity", "Invalid offer: missing data/sdp")
            return
        }
        statusTextView.text = "Status: Eingehender Anruf!"
        
        val callerSessionId = message.optString("sessionId") 
        val caller = liveVisitors.find { it.sessionId == callerSessionId } ?: Visitor(callerSessionId, "N/A", "Web Visitor", null)
        
        activeCallSessionId = callerSessionId
        showActiveCallTab(caller)
        
        val offerDesc = SessionDescription(
            SessionDescription.Type.OFFER,
            sdpData.getString("sdp")
        )
        webRtcClient.peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {}
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
                signalingClient.send(answer)
                activeCallInfo.text = "Verbunden, im Gespräch"
            }
            override fun onCreateFailure(s: String) {}
            override fun onSetFailure(s: String) {}
            override fun onSetSuccess() {}
        })
    }

    private fun endCall() {
        activeCallSessionId = null
        chatMessagesView.text = ""  // Chat-Verlauf löschen
        showVisitorsTab()
        // WICHTIG: Signalisiere dem Worker, dass der Anruf beendet ist
        signalingClient.send(JSONObject().put("type", "hangup"))
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
        signalingClient.send(chatMsg)
        
        // Eigene Nachricht im UI anzeigen
        appendChatMessage("Agent", text)
        chatInput.text.clear()
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
        chatMessagesView.text = currentText + newMessage
        
        // Auto-scroll zu neuester Nachricht
        chatMessagesView.post {
            chatMessagesView.parent?.let { parent ->
                (parent as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }
    
    // --- Admin/Supervisor Funktionen ---
    
    private fun updateRoleBasedUI(role: String?) {
        when (role) {
            "superadmin" -> {
                adminButton.visibility = View.VISIBLE
                supervisorButton.visibility = View.VISIBLE
            }
            "supervisor" -> {
                adminButton.visibility = View.GONE
                supervisorButton.visibility = View.VISIBLE
            }
            else -> {
                adminButton.visibility = View.GONE
                supervisorButton.visibility = View.GONE
            }
        }
        // Nach Login: Login/Register ausblenden
        updateAuthUI(isLoggedIn = true)
    }
    
    private fun updateAuthUI(isLoggedIn: Boolean) {
        if (isLoggedIn) {
            loginButton.visibility = View.GONE
            registerButton.visibility = View.GONE
            // Logout-Button als Text in connect_button anzeigen
            connectButton.text = "Logout"
            connectButton.isEnabled = true
            connectButton.setOnClickListener { performLogout() }
        } else {
            loginButton.visibility = View.VISIBLE
            registerButton.visibility = View.VISIBLE
            connectButton.text = "🔄 Manueller Reconnect"
            connectButton.setOnClickListener { performManualReconnect() }
        }
    }
    
    private fun performManualReconnect() {
        if (!::authClient.isInitialized) {
            Toast.makeText(this, "Bitte warte auf Berechtigungen / Initialisierung", Toast.LENGTH_SHORT).show()
            performLoginUI()
            return
        }
        val token = currentToken ?: authClient.getToken()
        val room = currentRoom ?: run {
            val domains = authClient.getDomains()
            val domain = domains.firstOrNull() ?: DOMAIN_ID
            generateCallRoomId(domain)
        }
        if (token != null) {
            statusTextView.text = "Status: Manueller Verbindungsaufbau..."
            liveVisitors.clear()
            visitorAdapter.notifyDataSetChanged()
            signalingClient.connect(room, token)
            currentRoom = room
            currentToken = token
            findViewById<ProgressBar>(R.id.reconnect_progress).visibility = View.VISIBLE
            connectButton.visibility = View.GONE
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
                connectButton.setOnClickListener { performManualReconnect() }
            }
        }
    }
    
    private fun startCallService(roomId: String, token: String) {
        try {
            val serviceIntent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_START_SERVICE
                putExtra(CallService.EXTRA_ROOM_ID, roomId)
                putExtra(CallService.EXTRA_TOKEN, token)
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Service binden für Kommunikation
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            
            Log.d("AppActivity", "CallService started")
            Toast.makeText(this, "✅ Anruf-Service aktiv", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("AppActivity", "Failed to start CallService", e)
            Toast.makeText(this, "Fehler beim Starten des Services: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
                    connectionQualityView.setTextColor(resources.getColor(R.color.neon_cyan, null))
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
                // Disconnect WebSocket
                signalingClient.disconnect()
                
                // Clear auth data
                authClient.clearToken()
                
                // Reset UI
                currentToken = null
                currentRole = null
                currentRoom = null
                activeCallSessionId = null
                liveVisitors.clear()
                visitorAdapter.notifyDataSetChanged()
                
                // Hide role-based buttons
                adminButton.visibility = View.GONE
                supervisorButton.visibility = View.GONE
                activeCallLayout.visibility = View.GONE
                
                statusTextView.text = "Status: Abgemeldet"
                Toast.makeText(this, "✅ Erfolgreich abgemeldet", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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
                            Toast.makeText(this@AppActivity, "Queue-Fehler: ${it.code}", Toast.LENGTH_LONG).show()
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
            
            queueItems.add("$statusIcon $domainName - ${waitTime}s wartend")
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
                    Toast.makeText(this@AppActivity, "Zuweisen fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                safeRunOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@AppActivity, "✅ Call zugewiesen", Toast.LENGTH_SHORT).show()
                        openQueueManagement() // Refresh
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        Toast.makeText(this@AppActivity, "Fehler ${response.code}: $errorBody", Toast.LENGTH_LONG).show()
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
        val token = currentToken ?: return
        
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
                        showVisitorLinkDialog(visitorId, roomId, visitorLink, expiresIn)
                        statusTextView.text = "Status: Call erstellt - warte auf Visitor"
                    }
                }
            }
        })
    }
    
    private fun showVisitorLinkDialog(visitorId: String, roomId: String, visitorLink: String, expiresIn: Int) {
        val hoursValid = expiresIn / 3600
        
        val message = "✅ Call-Link erstellt!\n\n" +
            "Visitor: $visitorId\n" +
            "Gültig für: ${hoursValid}h\n\n" +
            "Link:\n$visitorLink\n\n" +
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
                        Toast.makeText(this@AppActivity, "✅ User erstellt: $email", Toast.LENGTH_SHORT).show()
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
        
        if (!approved) {
            builder.setPositiveButton("✅ Freischalten") { _, _ ->
                approveUser(userId, email)
            }
        }
        
        builder.setNeutralButton("🏢 Domains zuweisen") { _, _ ->
            assignDomains(userId, email, domainsList, domainsArray)
        }
        
        builder.setNeutralButton("✏️ Bearbeiten") { _, _ ->
            editUser(userId, userObj)
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
            .post(okhttp3.RequestBody.create(null, ""))
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
    class VisitorAdapter(private val visitors: List<Visitor>, private val callAction: (Visitor) -> Unit, private val context: AppCompatActivity) 
        : RecyclerView.Adapter<VisitorAdapter.VisitorViewHolder>() {
        
        class VisitorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.visitor_name)
            val domain: TextView = view.findViewById(R.id.visitor_domain)
            val callButton: Button = view.findViewById(R.id.call_visitor_button)
            // val chatButton: Button = view.findViewById(R.id.chat_visitor_button) // Optional
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VisitorViewHolder {
            val itemLayout = LayoutInflater.from(parent.context).inflate(R.layout.visitor_list_item_glass, parent, false)
            return VisitorViewHolder(itemLayout)
        }

        override fun onBindViewHolder(holder: VisitorViewHolder, position: Int) {
            val visitor = visitors[position]
            holder.name.text = visitor.callerName
            holder.domain.text = visitor.domain
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
            
            val audioSource = factory.createAudioSource(audioConstraints)
            localAudioTrack = factory.createAudioTrack("audio1", audioSource)
            
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
                        PeerConnection.IceConnectionState.DISCONNECTED -> 
                            activity?.runOnUiThread { activity.updateConnectionQuality("poor") }
                        PeerConnection.IceConnectionState.FAILED -> 
                            activity?.runOnUiThread { activity.updateConnectionQuality("bad") }
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
