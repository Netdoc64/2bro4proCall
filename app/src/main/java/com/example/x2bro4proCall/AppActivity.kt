package com.example.x2bro4proCall

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.webrtc.*

// Data Class für einen aktiven Web-Besucher
data class Visitor(
    val sessionId: String,
    val domain: String,
    val callerName: String,
    val logoUrl: String?,
    val timestamp: Long = System.currentTimeMillis()
)

// Die AppActivity implementiert das SignalingListener Interface (aus SignalingClient.kt)
class AppActivity : AppCompatActivity(), SignalingListener {

    // UI Elemente (Angenommen, sie wurden in activity_app_layout.xml hinzugefügt)
    private lateinit var statusTextView: TextView
    private lateinit var liveVisitorsRecyclerView: RecyclerView
    private lateinit var connectButton: Button 
    private lateinit var activeCallLayout: LinearLayout 
    private lateinit var callEndButton: Button 
    private lateinit var activeCallInfo: TextView
    // NOTE: visitorDataTextView ist das alte Element, das wir hier nicht mehr explizit nutzen.

    // Daten und Clients
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRtcClient: PeerConnectionClient
    private var currentRoom: String? = null
    private var currentToken: String? = null
    private val liveVisitors = mutableListOf<Visitor>()
    private lateinit var visitorAdapter: VisitorAdapter

    private var activeCallSessionId: String? = null
    // NOTE: Diese ID muss mit der ID im Web-Widget übereinstimmen!
    private val BUSINESS_ID = "biz_rolex_muenchen_01"
    // Backend host (ohne scheme), aus Spezifikation
    private val BACKEND_HOST = "call-server.netdoc64.workers.dev"
    private lateinit var authClient: AuthClient

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
        setContentView(R.layout.activity_app_layout) 
        
        // 1. UI-Referenzen initialisieren (Muss mit XML IDs übereinstimmen)
        statusTextView = findViewById(R.id.status_text_view)
        connectButton = findViewById(R.id.connect_button)
        val loginButton: Button = findViewById(R.id.login_button)
        val registerButton: Button = findViewById(R.id.register_button)
        liveVisitorsRecyclerView = findViewById(R.id.live_visitors_recycler) 
        activeCallLayout = findViewById(R.id.active_call_layout)
        callEndButton = findViewById(R.id.call_end_button)
        activeCallInfo = findViewById(R.id.active_call_info)

        // Login/Register visible buttons
        loginButton.setOnClickListener { performLoginUI() }
        registerButton.setOnClickListener { performRegisterUI() }
        
        // 2. Adapter und RecyclerView
        visitorAdapter = VisitorAdapter(liveVisitors, this::generateOffer, this)
        liveVisitorsRecyclerView.layoutManager = LinearLayoutManager(this)
        liveVisitorsRecyclerView.adapter = visitorAdapter
        
        // 3. Event Listener
        callEndButton.setOnClickListener { endCall() }
        connectButton.setOnClickListener {
            // manual reconnect / connect using known token/room
            val token = currentToken ?: authClient.getToken()
            val room = currentRoom ?: BUSINESS_ID
            if (token != null) {
                statusTextView.text = "Status: Manueller Verbindungsaufbau..."
                signalingClient.connect(room, token)
                currentRoom = room
                currentToken = token
                // show progress bar while connecting
                findViewById<ProgressBar>(R.id.reconnect_progress).visibility = View.VISIBLE
                connectButton.isEnabled = false
            } else {
                performLoginUI()
            }
        }

        // 4. Clients initialisieren und verbinden
        initializeWebRTC()
        authClient = AuthClient(this, "https://$BACKEND_HOST")
        signalingClient = SignalingClient(this, BACKEND_HOST)
        // Auto-connect if token exists
        val savedToken = authClient.getToken()
        if (savedToken != null) {
            val rooms = authClient.getRooms()
            val room = rooms.firstOrNull() ?: BUSINESS_ID
            currentRoom = room
            currentToken = savedToken
            signalingClient.connect(room, savedToken)
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
        
        showVisitorsTab()
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient.disconnect()
        webRtcClient.close()
    }

    private fun performLoginUI() {
        val emailInput = EditText(this).apply { hint = "Email"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val passInput = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
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
                    override fun onSuccess(token: String, role: String?, rooms: List<String>) {
                        runOnUiThread {
                            statusTextView.text = "Status: Auth erfolgreich"
                            // store token
                            currentToken = token
                            // connect to first room or show selection
                            if (rooms.isNotEmpty()) showRoomSelectionAndConnect(rooms, token) else {
                                currentRoom = BUSINESS_ID
                                signalingClient.connect(BUSINESS_ID, token)
                            }
                        }
                    }

                    override fun onFailure(message: String) {
                        runOnUiThread {
                            statusTextView.text = "Status: Auth fehlgeschlagen"
                            Toast.makeText(this@AppActivity, "Login failed: $message", Toast.LENGTH_LONG).show()
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
        val nameInput = EditText(this).apply { hint = "Name (optional)"; inputType = InputType.TYPE_TEXT_VARIATION_PERSON_NAME }
        val emailInput = EditText(this).apply { hint = "Email"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val passInput = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
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
                    override fun onSuccess(token: String, role: String?, rooms: List<String>) {
                        runOnUiThread {
                            statusTextView.text = "Status: Registrierung erfolgreich"
                            currentToken = token
                            if (rooms.isNotEmpty()) showRoomSelectionAndConnect(rooms, token) else {
                                currentRoom = BUSINESS_ID
                                signalingClient.connect(BUSINESS_ID, token)
                            }
                        }
                    }

                    override fun onFailure(message: String) {
                        runOnUiThread {
                            statusTextView.text = "Status: Registrierung fehlgeschlagen"
                            Toast.makeText(this@AppActivity, "Register failed: $message", Toast.LENGTH_LONG).show()
                        }
                    }
                })
                dlg.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showRoomSelectionAndConnect(rooms: List<String>, token: String) {
        runOnUiThread {
            val arr = rooms.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Wähle Raum")
                .setItems(arr) { _, which ->
                    val room = arr[which]
                    statusTextView.text = "Status: Verbinde zu $room"
                    currentRoom = room
                    currentToken = token
                    signalingClient.connect(room, token)
                }
                .setCancelable(true)
                .show()
        }
    }

    // --- UI Management ---
    private fun showVisitorsTab() {
        liveVisitorsRecyclerView.visibility = View.VISIBLE
        activeCallLayout.visibility = View.GONE
        connectButton.visibility = View.VISIBLE
    }
    
    private fun showActiveCallTab(visitor: Visitor) {
        liveVisitorsRecyclerView.visibility = View.GONE
        activeCallLayout.visibility = View.VISIBLE
        connectButton.visibility = View.GONE
        activeCallInfo.text = "Im Gespräch mit ${visitor.callerName} von ${visitor.domain}"
    }

    // --- Signaling Listener Implementierung ---

    override fun onWebSocketOpen() {
        runOnUiThread { statusTextView.text = "Status: Online und bereit (Raum: $BUSINESS_ID)" }
    }

    override fun onReconnecting(attempt: Int, delayMs: Int) {
        runOnUiThread {
            statusTextView.text = "Status: Verbindung verloren — reconnect Versuch $attempt in ${delayMs}ms"
            val pb = findViewById<ProgressBar>(R.id.reconnect_progress)
            pb.visibility = View.VISIBLE
            connectButton.isEnabled = false
        }
    }

    override fun onReconnectFailed() {
        runOnUiThread {
            statusTextView.text = "Status: Verbindung konnte nicht wiederhergestellt werden"
            val pb = findViewById<ProgressBar>(R.id.reconnect_progress)
            pb.visibility = View.GONE
            connectButton.isEnabled = true
            Toast.makeText(this, "Automatischer Reconnect fehlgeschlagen", Toast.LENGTH_LONG).show()
        }
    }

    override fun onWebSocketClosed() {
        runOnUiThread { statusTextView.text = "Status: Getrennt (Versuche Reconnect...)" }
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
                "chat" -> Toast.makeText(this, "Chat von: ${message.getString("text")}", Toast.LENGTH_SHORT).show()
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
                }
                signalingClient.send(offer)
                activeCallInfo.text = "Warte auf Annahme durch ${visitor.callerName}..."
            }
            override fun onCreateFailure(s: String) { Log.e("WebRTC", "Offer failed: $s") }
            override fun onSetFailure(s: String) { Log.e("WebRTC", "SetLocalDesc failed: $s") }
            override fun onSetSuccess() {}
        })
    }
    
    private fun handleIncomingOffer(message: JSONObject) {
        val sdp = message.getJSONObject("sdp")
        statusTextView.text = "Status: Eingehender Anruf!"
        
        val callerSessionId = message.optString("sessionId") 
        val caller = liveVisitors.find { it.sessionId == callerSessionId } ?: Visitor(callerSessionId, "N/A", "Web Visitor", null)
        
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
        showVisitorsTab()
        // WICHTIG: Signalisiere dem Worker, dass der Anruf beendet ist
        signalingClient.send(JSONObject().put("type", "hangup"))
    }
    
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
