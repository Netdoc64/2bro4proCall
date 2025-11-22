package com.example.x2bro4proCall

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
import java.io.PrintWriter
import java.io.StringWriter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.webrtc.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
    // NOTE: visitorDataTextView ist das alte Element, das wir hier nicht mehr explizit nutzen.

    // Daten und Clients
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRtcClient: PeerConnectionClient
    private var currentRoom: String? = null
    private var currentToken: String? = null
    private var currentRole: String? = null
    private val liveVisitors = mutableListOf<Visitor>()
    private lateinit var visitorAdapter: VisitorAdapter
    
    // Supervisor Monitoring
    private var isMonitoring = false
    private var monitoringRoomId: String? = null
    private var monitoringClient: SignalingClient? = null

    private var activeCallSessionId: String? = null
    // NOTE: Diese Domain-ID muss mit der ID im Backend übereinstimmen!
    private val DOMAIN_ID = "tarba_schlusseldienst"  // Beispiel-Domain
    // Backend host (ohne scheme), aus Spezifikation
    private val BACKEND_HOST = "call-server.netdoc64.workers.dev"
    
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
    private fun initializeWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        webRtcClient = PeerConnectionClient(factory)
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

        setContentView(R.layout.activity_app_layout) 
        
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
        if (savedToken != null) {
            val domains = authClient.getDomains()
            val domain = domains.firstOrNull() ?: DOMAIN_ID
            // Generiere vollständige Room-ID mit Session
            val roomId = generateCallRoomId(domain)
            currentRoom = roomId
            currentToken = savedToken
            signalingClient.connect(roomId, savedToken)
            
            // Service starten bei Auto-Login
            startCallService(roomId, savedToken)
        } else {
            // prompt login
            performLoginUI()
        }
        // forward local ICE candidates to signaling worker
        webRtcClient.onIceCandidateCallback = { candidate ->
            val candidateJson = JSONObject().apply {
                put("type", "candidate")
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
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
        signalingClient.disconnect()
        webRtcClient.close()
    }

    private fun performLoginUI() {
        val emailInput = EditText(this).apply { hint = "Email"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(emailInput)
            addView(passInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Agent Login")
            .setView(layout)
            .setPositiveButton("Login") { dlg, _ ->
                val email = emailInput.text.toString()
                val pass = passInput.text.toString()
                if (email.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, "Bitte Email und Passwort eingeben", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                statusTextView.text = "Status: Authentifiziere..."
                authClient.login(email, pass, object : AuthClient.LoginCallback {
                    override fun onSuccess(token: String, role: String?, domains: List<String>) {
                        runOnUiThread {
                            statusTextView.text = "Status: Auth erfolgreich"
                            // store token and role
                            currentToken = token
                            currentRole = role
                            
                            // Zeige Admin/Supervisor Buttons basierend auf Rolle
                            updateRoleBasedUI(role)
                            
                            // connect to first domain or show selection
                            if (domains.isNotEmpty()) showDomainSelectionAndConnect(domains, token) else {
                                val roomId = generateCallRoomId(DOMAIN_ID)
                                currentRoom = roomId
                                signalingClient.connect(roomId, token)
                            }
                            
                            // CallService starten für Hintergrund-Anrufe
                            startCallService(currentRoom ?: generateCallRoomId(DOMAIN_ID), token)
                        }
                    }

                    override fun onFailure(message: String) {
                        runOnUiThread {
                            // Zeige detaillierte Fehlermeldung
                            statusTextView.text = "Status: Login fehlgeschlagen"
                            
                            // AlertDialog für bessere Sichtbarkeit
                            AlertDialog.Builder(this@AppActivity)
                                .setTitle("Login Fehler")
                                .setMessage(message)
                                .setPositiveButton("OK") { dlg, _ ->
                                    dlg.dismiss()
                                    // Bei "nicht freigegeben" Login-Dialog erneut öffnen
                                    if (!message.contains("⏳")) {
                                        performLoginUI()
                                    }
                                }
                                .setCancelable(false)
                                .show()
                        }
                    }
                })
                dlg.dismiss()
            }
            .setNeutralButton("Registrieren") { dlg, _ ->
                dlg.dismiss()
                performRegisterUI()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun performRegisterUI() {
        val nameInput = EditText(this).apply { hint = "Name (optional)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME }
        val emailInput = EditText(this).apply { hint = "Email"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(nameInput)
            addView(emailInput)
            addView(passInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Agent Registrierung")
            .setView(layout)
            .setPositiveButton("Registrieren") { dlg, _ ->
                val name = nameInput.text.toString().takeIf { it.isNotBlank() }
                val email = emailInput.text.toString()
                val pass = passInput.text.toString()
                if (email.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, "Bitte Email und Passwort eingeben", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                statusTextView.text = "Status: Registriere..."
                authClient.register(name, email, pass, object : AuthClient.RegisterCallback {
                    override fun onSuccess(token: String, role: String?, domains: List<String>) {
                        runOnUiThread {
                            statusTextView.text = "Status: Registrierung erfolgreich"
                            currentToken = token
                            currentRole = role
                            updateRoleBasedUI(role)
                            if (domains.isNotEmpty()) showDomainSelectionAndConnect(domains, token) else {
                                val roomId = generateCallRoomId(DOMAIN_ID)
                                currentRoom = roomId
                                signalingClient.connect(roomId, token)
                            }
                        }
                    }

                    override fun onFailure(message: String) {
                        runOnUiThread {
                            // Bei Erfolg (✅) zeige andere Meldung
                            val isSuccess = message.startsWith("✅")
                            statusTextView.text = if (isSuccess) "Status: Registrierung erfolgreich" else "Status: Registrierung fehlgeschlagen"
                            
                            // AlertDialog für detaillierte Meldung
                            AlertDialog.Builder(this@AppActivity)
                                .setTitle(if (isSuccess) "Registrierung erfolgreich" else "Registrierung Fehler")
                                .setMessage(message)
                                .setPositiveButton("OK") { dlg, _ ->
                                    dlg.dismiss()
                                    // Bei Erfolg zum Login-Dialog wechseln
                                    if (isSuccess) {
                                        performLoginUI()
                                    }
                                }
                                .setCancelable(false)
                                .show()
                        }
                    }
                })
                dlg.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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
        runOnUiThread { 
            statusTextView.text = "Status: ✅ Verbunden"
            updateConnectionUI(isConnected = true)
            connectionQualityView.visibility = View.VISIBLE
            updateConnectionQuality("excellent")
        }
    }

    override fun onReconnecting(attempt: Int, delayMs: Int) {
        runOnUiThread {
            statusTextView.text = "Status: Verbindung verloren — reconnect Versuch $attempt in ${delayMs}ms"
            val pb = findViewById<ProgressBar>(R.id.reconnect_progress)
            pb.visibility = View.VISIBLE
            updateConnectionUI(isConnected = false)
        }
    }

    override fun onReconnectFailed() {
        runOnUiThread {
            statusTextView.text = "Status: Verbindung konnte nicht wiederhergestellt werden"
            val pb = findViewById<ProgressBar>(R.id.reconnect_progress)
            pb.visibility = View.GONE
            updateConnectionUI(isConnected = false)
            Toast.makeText(this, "Automatischer Reconnect fehlgeschlagen", Toast.LENGTH_LONG).show()
        }
    }

    override fun onWebSocketClosed() {
        runOnUiThread { 
            statusTextView.text = "Status: Getrennt (Versuche Reconnect...)"
            updateConnectionUI(isConnected = false)
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, "WS Error: $message", Toast.LENGTH_LONG).show()
            val pb = findViewById<ProgressBar>(R.id.reconnect_progress)
            pb.visibility = View.GONE
            connectButton.isEnabled = true
        }
    }

    override fun onNewSignalReceived(message: JSONObject) {
        runOnUiThread {
            when (message.getString("type")) {
                "identify" -> handleNewVisitor(message) 
                "system" -> handleSystemMessage(message)
                "offer" -> handleIncomingOffer(message) 
                "answer" -> webRtcClient.handleAnswer(message.getJSONObject("sdp"))
                "candidate" -> webRtcClient.handleIceCandidate(message.getJSONObject("candidate"))
                "chat" -> handleChatMessage(message)
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
    }

    private fun handleVisitorLeft(message: JSONObject) {
        val sessionId = message.optString("sessionId", "")
        val index = liveVisitors.indexOfFirst { it.sessionId == sessionId }
        if (index != -1) {
            liveVisitors.removeAt(index)
            visitorAdapter.notifyItemRemoved(index)
            statusTextView.text = "Status: ${liveVisitors.size} Live-Besucher"
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
                    put("sdp", JSONObject().apply {
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
        val sdp = message.getJSONObject("sdp")
        statusTextView.text = "Status: Eingehender Anruf!"
        
        val callerSessionId = message.optString("sessionId") 
        val caller = liveVisitors.find { it.sessionId == callerSessionId } ?: Visitor(callerSessionId, "N/A", "Web Visitor", null)
        
        activeCallSessionId = callerSessionId
        showActiveCallTab(caller)
        
        val offerDesc = SessionDescription(
            SessionDescription.Type.OFFER,
            sdp.getString("sdp")
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
                    put("sdp", JSONObject().apply { 
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
            signalingClient.connect(room, token)
            currentRoom = room
            currentToken = token
            findViewById<ProgressBar>(R.id.reconnect_progress).visibility = View.VISIBLE
            connectButton.visibility = View.GONE
        } else {
            performLoginUI()
        }
    }
    
    private fun performLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Möchten Sie sich wirklich abmelden?")
            .setPositiveButton("Ja") { _, _ ->
                // CallService stoppen
                stopCallService()
                
                signalingClient.disconnect()
                authClient.clearToken()
                currentToken = null
                currentRoom = null
                currentRole = null
                updateAuthUI(isLoggedIn = false)
                updateRoleBasedUI(null)
                statusTextView.text = "Status: Abgemeldet"
                Toast.makeText(this, "Erfolgreich abgemeldet", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Nein", null)
            .show()
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
    
    private fun openAdminPanel() {
        if (currentRole != "superadmin") {
            Toast.makeText(this, "Keine Berechtigung", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Admin-Panel Intent (später eigene Activity)
        AlertDialog.Builder(this)
            .setTitle("🔧 SuperAdmin Panel")
            .setMessage("Admin-Funktionen:\n\n" +
                "• User freischalten\n" +
                "• Domains zuweisen\n" +
                "• Domains verwalten\n\n" +
                "Vollständiges Admin-Panel kommt bald!")
            .setPositiveButton("User verwalten") { _, _ ->
                openUserManagement()
            }
            .setNeutralButton("Domain verwalten") { _, _ ->
                openDomainManagement()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
    
    private fun openUserManagement() {
        val token = currentToken ?: return
        
        statusTextView.text = "Status: Lade User-Daten..."
        
        // API Call zu /api/admin/data
        val url = "https://$BACKEND_HOST/api/admin/data"
        val client = okhttp3.OkHttpClient()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                    statusTextView.text = "Status: Fehler beim Laden"
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@AppActivity, "Fehler: ${it.code}", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    
                    val jsonData = org.json.JSONObject(it.body?.string() ?: "{}")
                    val usersArray = jsonData.optJSONArray("users")
                    val domainsArray = jsonData.optJSONArray("domains")
                    
                    runOnUiThread {
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
            .setNegativeButton("Zurück", null)
            .show()
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
        
        builder.setNegativeButton("Zurück", null)
            .show()
    }
    
    private fun approveUser(userId: String, email: String) {
        val token = currentToken ?: return
        val url = "https://$BACKEND_HOST/api/admin/approve/$userId"
        
        val client = okhttp3.OkHttpClient()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(okhttp3.RequestBody.create(null, ""))
            .build()
        
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                runOnUiThread {
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
        val url = "https://$BACKEND_HOST/api/admin/assign"
        
        val json = org.json.JSONObject().apply {
            put("targetUserId", userId)
            put("allowed_domains", org.json.JSONArray(domains))
        }
        
        val client = okhttp3.OkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                runOnUiThread {
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
        Toast.makeText(this, "Domain-Verwaltung kommt in Kürze", Toast.LENGTH_SHORT).show()
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
                "• Agent-Performance\n")
            .setPositiveButton("📹 Live Calls überwachen") { _, _ ->
                startLiveMonitoring()
            }
            .setNeutralButton("📊 Statistiken") { _, _ ->
                showCallStatistics()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
    
    private fun startLiveMonitoring() {
        val token = currentToken ?: return
        
        // Lade aktive Calls aus der Datenbank
        statusTextView.text = "Status: Lade aktive Calls..."
        
        val url = "https://$BACKEND_HOST/api/admin/data"
        val client = okhttp3.OkHttpClient()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@AppActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@AppActivity, "Fehler: ${it.code}", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    
                    // In einem realen System würden hier aktive Calls aus der DB kommen
                    // Für Demo: Zeige Domain-Auswahl zum Monitoring
                    val jsonData = org.json.JSONObject(it.body?.string() ?: "{}")
                    val domainsArray = jsonData.optJSONArray("domains")
                    
                    runOnUiThread {
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
                runOnUiThread {
                    statusTextView.text = "Status: 👁️ Monitoring $roomId"
                    Toast.makeText(this@AppActivity, "Monitoring aktiv", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onNewSignalReceived(message: org.json.JSONObject) {
                runOnUiThread {
                    // Zeige Monitoring-Daten an
                    handleMonitoringMessage(message)
                }
            }
            
            override fun onWebSocketClosed() {
                runOnUiThread {
                    statusTextView.text = "Status: Monitoring beendet"
                    isMonitoring = false
                }
            }
            
            override fun onError(message: String) {
                runOnUiThread {
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
        // Platzhalter für Statistiken
        AlertDialog.Builder(this)
            .setTitle("📊 Call-Statistiken")
            .setMessage("Statistik-Features:\n\n" +
                "• Anzahl Calls pro Domain\n" +
                "• Durchschnittliche Gesprächsdauer\n" +
                "• Agent-Performance\n" +
                "• Warteschlangen-Analyse\n\n" +
                "Kommt in Kürze!")
            .setPositiveButton("OK", null)
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
            // NOTE: Für die Kompilierung ohne Android Studio ist dies die beste Lösung. 
            // In einem realen Projekt würde man hier ein visitor_list_item.xml laden.
            val itemLayout = LayoutInflater.from(parent.context).inflate(R.layout.visitor_list_item_template, parent, false)
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

    // 2. PeerConnectionClient: WebRTC-Logik (unverändert)
    class PeerConnectionClient(factory: PeerConnectionFactory) {
        var peerConnection: PeerConnection? = null
        var onIceCandidateCallback: ((IceCandidate) -> Unit)? = null
        private val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        init {
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
            peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) { onIceCandidateCallback?.invoke(candidate) }
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: DataChannel) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
                override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
                override fun onRemoveTrack(rtpReceiver: RtpReceiver) {}
                override fun onRenegotiationNeeded() {}
            })
        }
        
        fun createOffer(observer: SdpObserver) { peerConnection?.createOffer(observer, MediaConstraints()) }
        fun createAnswer(observer: SdpObserver) { peerConnection?.createAnswer(observer, MediaConstraints()) }
        
        fun handleAnswer(sdpJson: JSONObject) {
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdpJson.getString("sdp"))
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {}
                override fun onSetFailure(error: String) {}
            }, answer)
        }

        fun handleIceCandidate(candidateJson: JSONObject) {
            val candidate = IceCandidate(
                candidateJson.getString("sdpMid"),
                candidateJson.getInt("sdpMLineIndex"),
                candidateJson.getString("candidate")
            )
            peerConnection?.addIceCandidate(candidate)
        }

        fun close() { peerConnection?.close() }
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
