# 2bro4Call - Vollständige App-Logic Beschreibung
**Datum:** 8. Dezember 2025  
**Version:** v2.3.0  
**Basierend auf:** Vollständiger Quellcode-Analyse

---

## 📱 App-Start bis Login (Cold Start)

### 1. AppActivity.onCreate() - Zeile 234-480

**Was passiert:**
```
1.1 ErrorReporter initialisieren
    → Backend URL: https://call-server.netdoc64.workers.dev
    → App-Version aus PackageInfo auslesen

1.2 Global Crash Handler einrichten
    → Crash wird in filesDir/last_crash.log geschrieben
    → Crash wird an Backend /api/errors/report gesendet (mit Token falls vorhanden)
    → Original Handler wird aufgerufen (App crasht trotzdem, aber Daten sind gesichert)

1.3 Layout laden: activity_app_layout_glass.xml
    → 17 UI-Elemente via findViewById() initialisieren
    → RecyclerView für Visitor-Liste
    → Chat-UI, Call-UI, Status-View, Badges

1.4 Auth-Buttons initial ausblenden
    → Login/Register/Admin/Supervisor Buttons: GONE
    → Connect Button: DISABLED (wird erst nach Permission freigegeben)

1.5 RecyclerView Setup
    → VisitorAdapter erstellen mit leerem liveVisitors
    → Layout: LinearLayoutManager
    → 3 Callbacks: generateOffer(), enterChatRoom(), Context

1.6 Event Listener registrieren
    → callEndButton → endCall()
    → chatSendButton → sendChatMessage()
    → chatInput → Enter-Taste → sendChatMessage()
    → menuButton → showMenuPopup()

1.7 Audio Permission prüfen
    → ensureAudioPermissionThenInit()
    → Falls GRANTED → startClientsAndAutoConnect()
    → Falls NICHT → requestPermissions() → Warten auf User
```

**Fluss-Diagramm:**
```
onCreate()
    ↓
[Audio Permission?] ─NO→ requestPermissions() ─→ onRequestPermissionsResult()
    ↓ YES                                              ↓
startClientsAndAutoConnect()  ←────────────────────────┘
    ↓
initializeWebRTC() + AuthClient + SignalingClient
    ↓
[Token in EncryptedSharedPrefs?] ─NO→ performLoginUI()
    ↓ YES
Auto-Login aktiviert
    ↓
startQueuePolling() + startCallServiceForFcm()
```

---

## 🔐 Login-Flow (performLoginUI) - Zeile 584-700

### 2. Login-Dialog und Authentication

**Was passiert:**
```
2.1 Login-Dialog anzeigen
    → Email + Password EditTexts
    → "Angemeldet bleiben" Checkbox (default: checked)
    → 3 Buttons: Login, Registrieren, Abbrechen

2.2 User gibt Email + Password ein und klickt "Login"
    → sanitizeInput(email) → XSS-Prevention
    → Validierung: Email Pattern + nicht leer
    → Button disabled: "⏳ Login läuft..."

2.3 AuthClient.login() HTTP Request
    → POST https://call-server.netdoc64.workers.dev/api/login
    → Body: { email, password }
    → Response (200): { token, user: { id, email, displayName, roles[], permissions[], allowedDomains[] } }

2.4 onSuccess Callback
    → Token in EncryptedSharedPreferences speichern (AES256-GCM)
    → User-Daten speichern: userId, email, displayName, roles, permissions, domains
    → currentToken = token
    → currentRole = roles[0].name (z.B. "agent", "superadmin")

2.5 UI-Updates
    → Dialog schließen
    → statusTextView: "Status: ✅ Eingeloggt - warte auf Anrufe..."
    → updateRoleBasedUI(currentRole) → Admin/Supervisor Buttons zeigen
    → userInfoBadge.text = "Max Mustermann\n(ID: 1234567...)"

2.6 Queue-System starten (KEIN fester Room!)
    → startQueuePolling() → Handler alle 5 Sekunden
    → initializeNotifications(token) → WebSocket /api/agent/notifications
    → startCallServiceForFcm(token) → Foreground Service starten

2.7 FCM Token an Backend senden
    → FirebaseMessaging.getToken() → String
    → AuthClient.sendFcmToken(token) → POST /api/agent/register_device
```

**Fluss-Diagramm:**
```
performLoginUI()
    ↓
[Email + Pass eingeben]
    ↓
AuthClient.login() → POST /api/login
    ↓
[Response 200?] ─NO→ Toast "❌ Login fehlgeschlagen"
    ↓ YES                   ↓ (401: Ungültige Credentials)
Save Token + User Data      ↓ (403: Account nicht freigegeben)
    ↓
updateRoleBasedUI()
    ↓
startQueuePolling() ←─────────┐
    ↓                          │
initializeNotifications() ←───┤ PARALLEL
    ↓                          │
startCallServiceForFcm() ←────┘
```

---

## 📞 Queue-Polling System - Zeile 1850-2000

### 3. Agent wartet auf Anrufe (Queue-basiert)

**Was passiert:**
```
3.1 startQueuePolling() - Handler starten
    → queuePollingHandler = Handler(MainLooper)
    → queuePollingRunnable erstellen (rekursiv)
    → postDelayed(runnable, 5000ms) → ALLE 5 SEKUNDEN

3.2 fetchQueuedCalls() - HTTP Request
    → GET https://call-server.netdoc64.workers.dev/api/agent/queues
    → Header: Authorization: Bearer {token}
    → Response: { queues: [...], total: 10 }

3.3 Backend Response Format
    queues: [
        {
            session_id: "tarba__uuid-1234",
            domain_id: "tarba_schlusseldienst",
            domain_name: "Tarba Schlüsseldienst",
            status: "queued",  // oder "ringing"
            timestamp: 1701234567890
        }
    ]

3.4 Liste aktualisieren (KRITISCH: Aktiven Call behalten!)
    → Schritt 1: activeCall aus Liste extrahieren (falls sessionId == activeCallSessionId)
    → Schritt 2: Neue Liste bauen mit activeCall an Position 0
    → Schritt 3: Wartende Calls (queued/ringing) hinzufügen
    → Schritt 4: liveVisitors.clear() + addAll(newVisitors)
    → Schritt 5: visitorAdapter.notifyDataSetChanged()

3.5 UI-Updates
    → visitorCountBadge.text = liveVisitors.size.toString()
    → statusTextView.text = "Status: ✅ ${size} wartende(r) Anruf(e)"
```

**Parallel: Realtime WebSocket Notifications** (initializeNotifications)

```
3.6 AgentNotificationsClient.connect()
    → WebSocket: wss://call-server.netdoc64.workers.dev/api/agent/notifications?token={token}
    → Verbindung aufbauen (OkHttp WebSocket)

3.7 Backend sendet Events:
    
    EVENT: "new_call" (Visitor erstellt Room via REST API)
    → { type: "new_call", room_id, domain_id, domain_name, timestamp }
    → onNewCall() Callback
    → Visitor in liveVisitors hinzufügen (an TOP)
    → playNotificationSound() 🔔 (leiser Ton)
    → KEIN Ringtone (noch nicht "ringing" Status)

    EVENT: "visitor_waiting" (Visitor connected zu WebSocket)
    → { type: "visitor_waiting", room_id, domain_id, domain_name, timestamp }
    → Gleiche Behandlung wie "new_call" (nur Queue-Update)
    
    EVENT: "incoming_call" (Visitor klickt "Anrufen" Button)
    → { type: "incoming_call", room_id, from: "visitor", timestamp }
    → onCallRinging() Callback
    → playRingtone() 🔔🔔🔔 (lauter, wiederholend)
    → showIncomingCallDialog() → "Anruf annehmen?" Dialog

3.8 WebSocket Heartbeat
    → Alle 30 Sekunden: { type: "ping" } senden
    → Backend antwortet mit { type: "pong" }
    → Verhindert Connection Timeout
```

**Fluss-Diagramm:**
```
startQueuePolling() + initializeNotifications()
    ↓                       ↓
    ↓                       ↓
┌───▼─────────────┐  ┌─────▼──────────────┐
│ REST Polling    │  │ WebSocket Events   │
│ (alle 5s)       │  │ (Realtime)         │
└───┬─────────────┘  └─────┬──────────────┘
    ↓                       ↓
    ↓                 [new_call event]
    ↓                       ↓
    ↓                 Add to liveVisitors
    ↓                       ↓
    ↓                 playNotificationSound()
    ↓                       ↓
[GET /api/agent/queues]     ↓
    ↓                       ↓
Merge mit WebSocket-Daten   ↓
    ↓                       ↓
    └───────────┬───────────┘
                ↓
        Update RecyclerView
                ↓
        visitorAdapter.notifyDataSetChanged()
```

---

## 🔔 Anruf-Initiierung: Agent ruft Visitor an - Zeile 1195-1250

### 4. Agent klickt "Anrufen" Button in Queue-Liste

**Was passiert:**
```
4.1 generateOffer(visitor: Visitor) aufgerufen
    → visitor enthält: sessionId, roomId, domain, callerName

4.2 Prüfung: Bereits im Call?
    → Falls activeCallSessionId != null:
      → Dialog: "Möchten Sie aktuelles Gespräch beenden?"
      → Falls JA: endCall() → 500ms warten → generateOffer() erneut

4.3 Call-State setzen
    → activeCallSessionId = visitor.sessionId
    → isWebRTCActive = true
    → isOutgoingCall = true (Agent hat initiiert)

4.4 UI umschalten
    → showActiveCallTab(visitor)
    → activeCallLayout.visibility = VISIBLE
    → liveVisitorsRecyclerView.visibility = GONE
    → activeCallInfo.text = "Verbinde mit ${visitor.callerName}..."

4.5 WebSocket zu Visitor's Room verbinden
    → currentRoom = roomId (Format: domain__uuid)
    → signalingClient.connect(roomId, token)
    → WebSocket URL: wss://.../call/{roomId}?token={token}&mode=talk

4.6 WebRTC Offer erstellen (nach 300ms Delay)
    → webRtcClient.createOffer()
    → sdpObserver.onCreateSuccess(sdp)
    → pc.setLocalDescription(this, sdp) ← Thread-safe local variable
    
4.7 Offer via WebSocket senden
    → JSON: { type: "offer", data: { type: "offer", sdp: "..." }, targetSessionId: visitor.sessionId }
    → signalingClient.send(offer)
    → Backend leitet an Visitor weiter

4.8 Visitor's Browser empfängt Offer
    → Visitor hört Ringtone 🔔 (Backend sendet call_ringing event)
    → Visitor sieht "Eingehender Anruf" Dialog
    → Visitor klickt "Annehmen"
```

**Code-Ausschnitt (vereinfacht):**
```kotlin
private fun generateOffer(visitor: Visitor) {
    activeCallSessionId = visitor.sessionId
    isWebRTCActive = true
    isOutgoingCall = true
    showActiveCallTab(visitor)
    
    currentRoom = visitor.roomId
    signalingClient.connect(visitor.roomId, token)
    
    Handler(Looper.getMainLooper()).postDelayed({
        webRtcClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val pc = webRtcClient.peerConnection // Thread-safe
                if (pc != null) {
                    pc.setLocalDescription(this, sdp)
                    val offer = JSONObject().apply {
                        put("type", "offer")
                        put("data", JSONObject().apply {
                            put("type", "offer")
                            put("sdp", sdp.description)
                        })
                        put("targetSessionId", visitor.sessionId)
                    }
                    signalingClient.send(offer)
                }
            }
        })
    }, 300)
}
```

---

## 📥 Anruf-Empfang: Visitor ruft Agent an - Zeile 1260-1390

### 5. Eingehender Anruf von Visitor

**Was passiert:**
```
5.1 Backend sendet "incoming_call" Event via WebSocket
    → AgentNotificationsClient empfängt: { type: "incoming_call", room_id, from: "visitor" }
    → onCallRinging() Callback
    → playRingtone() 🔔🔔🔔 (MediaPlayer mit Loop)

5.2 showIncomingCallDialog(visitor, roomId)
    → AlertDialog: "📞 Eingehender Anruf von {visitor.callerName}"
    → 2 Buttons: "Annehmen ✅" + "Ablehnen ❌"

5.3 Agent klickt "Annehmen"
    → stopRingtone() → MediaPlayer.stop()
    → Dialog schließen
    → handleIncomingOffer(message) aufrufen

5.4 WebSocket empfängt Offer von Visitor
    → onNewSignalReceived() → type: "offer"
    → handleIncomingOffer(message)
    
5.5 handleIncomingOffer() - Zeile 1260-1390
    → sdpData aus message.data extrahieren
    → callerSessionId = message.sessionId

5.6 Prüfung: Bereits im Call?
    → Falls activeCallSessionId != null UND != callerSessionId:
      → Sende "busy" Signal an Visitor
      → return (Call ablehnen)

5.7 Call-State setzen
    → activeCallSessionId = callerSessionId
    → isWebRTCActive = true
    → isOutgoingCall = false (Visitor hat initiiert)
    → showActiveCallTab(caller)

5.8 PeerConnection Thread-Safe Access (FIX #4)
    → val pc = webRtcClient.peerConnection (lokale Variable)
    → Falls pc == null: Error + endCall() + return

5.9 call_accept an Backend senden
    → JSON: { type: "call_accept" }
    → signalingClient.send(acceptMsg)
    → Backend setzt Room-Status auf "active"

5.10 Remote Description setzen
    → offerDesc = SessionDescription(OFFER, sdp)
    → pc.setRemoteDescription(sdpObserver, offerDesc)

5.11 Answer erstellen
    → webRtcClient.createAnswer()
    → answerSdp erstellt
    → pc.setLocalDescription(this, answerSdp)

5.12 Answer via WebSocket senden
    → JSON: { type: "answer", data: { type: "answer", sdp: "..." }, targetSessionId: callerSessionId }
    → signalingClient.send(answer)
    → activeCallInfo.text = "Verbunden, im Gespräch"
```

**Kritische Punkte (Fixed in Phase 1):**
- ✅ Thread-safe PeerConnection access (lokale Variable statt direkter Zugriff)
- ✅ call_accept BEFORE setRemoteDescription (Backend race condition fix)
- ✅ Null-Check für peerConnection mit Error-Handling

---

## 🔌 WebRTC Verbindungsaufbau - Zeile 4184-4300

### 6. ICE Candidate Exchange & Audio Track Setup

**Was passiert:**
```
6.1 PeerConnectionClient initialisieren (onCreate)
    → PeerConnectionFactory.initialize()
    → Singleton pattern mit webRtcInitialized flag

6.2 Audio Track erstellen
    → AudioSource mit Constraints:
      - googEchoCancellation: true
      - googNoiseSuppression: true
      - googAutoGainControl: true
      - googHighpassFilter: true
    → AudioTrack = factory.createAudioTrack("audio1", audioSource)

6.3 PeerConnection erstellen
    → ICE Server: stun:stun.l.google.com:19302
    → RTCConfiguration mit iceServers
    → PeerConnection.Observer registrieren

6.4 ICE Candidate Events
    → onIceCandidate(candidate) wird aufgerufen
    → JSON: { type: "ice", data: { candidate, sdpMid, sdpMLineIndex }, targetSessionId }
    → signalingClient.send(candidateJson)
    → Andere Seite empfängt über onNewSignalReceived() → type: "ice"

6.5 ICE Connection State Monitoring
    → CHECKING → connectionQualityView: "good"
    → CONNECTED → connectionQualityView: "excellent"
    → COMPLETED → connectionQualityView: "excellent"
    → DISCONNECTED → connectionQualityView: "poor" + Error Report
    → FAILED → connectionQualityView: "bad" + Critical Error Report

6.6 Audio Stream aktiviert
    → localAudioTrack.setEnabled(true) automatisch
    → peerConnection.addTrack(localAudioTrack, ["stream1"])
    → Remote Audio Stream empfangen via onAddTrack()
    → Android Audio Manager spielt Remote Stream ab
```

**WebRTC State Machine:**
```
[Offer erstellen]
    ↓
setLocalDescription(offer)
    ↓
send(offer) via WebSocket
    ↓
[Warte auf Answer]
    ↓
receive(answer) via WebSocket
    ↓
setRemoteDescription(answer)
    ↓
[ICE Gathering startet]
    ↓
onIceCandidate() mehrfach aufgerufen
    ↓
send(ice) via WebSocket für jeden Candidate
    ↓
receive(ice) von anderer Seite
    ↓
addIceCandidate() für jeden empfangenen
    ↓
[ICE State: CHECKING → CONNECTED]
    ↓
🎤 Audio Stream aktiv!
```

---

## 💬 Chat-Funktionalität - Zeile 1393-1450

### 7. Text-Chat während Call (optional)

**Was passiert:**
```
7.1 User tippt Nachricht in chatInput
    → Enter-Taste oder chatSendButton.onClick()
    → sendChatMessage() aufrufen

7.2 sendChatMessage() - Validierung
    → text = chatInput.text.toString().trim()
    → Falls leer ODER activeCallSessionId == null: return

7.3 Chat-Message JSON erstellen
    → { type: "chat", text: "Hallo", targetSessionId: activeCallSessionId }
    → signalingClient.send(chatMsg)

7.4 Eigene Message im UI anzeigen
    → appendChatMessage("Agent", text)
    → chatInput.clear()

7.5 Andere Seite empfängt via onNewSignalReceived()
    → type: "chat" → handleChatMessage(message)
    → sender = message.senderRole (z.B. "Besucher")
    → text = message.text
    → appendChatMessage(sender, text)

7.6 appendChatMessage() - Memory Management
    → Timestamp formatieren: [HH:mm:ss]
    → Message: "[14:23:45] Agent: Hallo\n"
    → Limit auf 100 Zeilen (Memory Leak Prevention)
    → chatMessagesView.text += newMessage
    → Auto-scroll zu neuester Message
```

**Chat-Flow (bidirektional):**
```
Agent                           WebSocket                    Visitor
  ↓                                 ↓                           ↓
Tippt "Hallo"                       ↓                           ↓
  ↓                                 ↓                           ↓
sendChatMessage()                   ↓                           ↓
  ↓                                 ↓                           ↓
JSON erstellen ─────send()─────→ Backend ─────forward()────→ Browser
  ↓                                 ↓                           ↓
appendChatMessage()                 ↓                  onMessage(chat)
  ↓                                 ↓                           ↓
[14:23:45] Agent: Hallo             ↓            [14:23:45] Agent: Hallo
  ↓                                 ↓                           ↓
  ↓                                 ↓                   Tippt "Danke"
  ↓                                 ↓                           ↓
  ↓                           ←───send()───────────────────────┘
  ↓                                 ↓
onNewSignalReceived(chat) ←───forward()
  ↓
appendChatMessage()
  ↓
[14:23:50] Besucher: Danke
```

---

## 🔴 Call Beenden - Zeile 1377-1391

### 8. endCall() - Cleanup & State Reset

**Was passiert:**
```
8.1 endCall() aufgerufen
    → Manuell: callEndButton.onClick()
    → Automatisch: Andere Seite hängt auf (call_ended event)
    → Automatisch: WebSocket Fehler

8.2 State Variablen zurücksetzen
    → activeCallSessionId = null
    → isWebRTCActive = false
    → isOutgoingCall = false
    → chatMessagesView.text = "" (Chat-Verlauf löschen)

8.3 UI zurück zu Queue-Liste
    → showVisitorsTab()
    → activeCallLayout.visibility = GONE
    → liveVisitorsRecyclerView.visibility = VISIBLE

8.4 Hangup-Signal senden (mit lateinit check - FIX #2)
    → if (::signalingClient.isInitialized)
    → JSON: { type: "hangup" }
    → signalingClient.send(hangup)
    → Backend setzt Room-Status auf "completed"

8.5 Backend-Reaktion
    → "call_ended" Event an alle Teilnehmer
    → Room aus Queue entfernen
    → Statistiken aktualisieren (call_duration, etc.)

8.6 Visitor-Browser empfängt call_ended
    → "Anruf beendet" Meldung
    → Connection Quality zurück auf idle
```

**Wichtig:** PeerConnection wird NICHT geschlossen in endCall()!
- Grund: Connection kann wiederverwendet werden
- Wird nur geschlossen in onDestroy() → webRtcClient.close()

---

## 🚀 CallService - Background Persistence - Zeile 1-392 (CallService.kt)

### 9. Foreground Service für FCM Push Notifications

**Was passiert:**
```
9.1 startCallServiceForFcm(token) in AppActivity
    → Intent erstellen: ACTION_START_SERVICE
    → EXTRA_TOKEN = token
    → KEIN EXTRA_ROOM_ID (Agent hat keinen festen Room!)
    → startForegroundService(intent)

9.2 CallService.onStartCommand() - Zeile 55-85
    → Token aus Intent extrahieren
    → currentToken = token speichern
    → startForegroundService() aufrufen
    → connectAgentNotifications(token) aufrufen

9.3 Foreground Notification erstellen
    → Channel: "call_service_channel"
    → Title: "2bro4Call Bereit"
    → Text: "Warte auf eingehende Anrufe..."
    → Icon: app_logo.png
    → Stop-Action: Beenden Button
    → startForeground(SERVICE_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)

9.4 WakeLock erwerben (10 Minuten Timeout)
    → PARTIAL_WAKE_LOCK
    → Tag: "2bro4Call::CallService"
    → acquire(10 * 60 * 1000L)
    → Verhindert Device Sleep während Service aktiv

9.5 AgentNotificationsClient verbinden
    → WebSocket zu /api/agent/notifications
    → Listener-Callbacks registrieren
    → onCallRinging() → showIncomingCallNotification()

9.6 Incoming Call Notification (HIGH Priority)
    → Channel: "incoming_call_channel"
    → Category: CATEGORY_CALL
    → Priority: PRIORITY_HIGH
    → Title: "📞 Eingehender Anruf"
    → Text: "Besucher auf {domain}"
    → Intent → öffnet AppActivity mit extras: incoming_room_id, incoming_domain
    → Ringtone abspielen (System Ringtone)

9.7 Service Binding
    → AppActivity.onCreate() → bindService(serviceConnection)
    → CallServiceBinder → getService()
    → callService?.onCallReceived = { roomId, domain -> ... }
    → Callbacks für bidirektionale Kommunikation

9.8 onTaskRemoved() - App aus Recent Apps gewischt
    → Service neu starten nach 3 Sekunden Delay
    → KEIN fester Room (nur FCM-Modus)
    → Verhindert Notification-Verlust
```

**Service Lifecycle:**
```
[App gestartet]
    ↓
Login erfolgreich
    ↓
startCallServiceForFcm(token)
    ↓
startForeground() → Notification anzeigen
    ↓
WakeLock acquire()
    ↓
AgentNotificationsClient.connect()
    ↓
[Service läuft im Hintergrund]
    ↓
[App aus Recent Apps gewischt]
    ↓
onTaskRemoved() → scheduleRestart()
    ↓
3 Sekunden warten
    ↓
startForegroundService() erneut
    ↓
[Service läuft weiter!]
```

---

## 🔄 SignalingClient - WebSocket Management - Zeile 1-316 (SignalingClient.kt)

### 10. WebSocket Lifecycle & Reconnection

**Was passiert:**
```
10.1 signalingClient.connect(roomId, token)
    → lastRoomId = roomId speichern (für Auto-Reconnect)
    → lastToken = token speichern
    → userInitiatedDisconnect = false
    → reconnectAttempts = 0 zurücksetzen

10.2 WebSocket URL bauen
    → scheme = backendHost ohne "https://"
    → URL: wss://{scheme}/call/{roomId}?token={token}&mode=talk
    → mode=talk für Agent (kann senden/empfangen)
    → mode=listen für Supervisor (nur empfangen)

10.3 OkHttp WebSocket erstellen
    → connectTimeout: 10s
    → readTimeout: 30s
    → pingInterval: 20s (automatische Pings von OkHttp)
    → WebSocketListener registrieren

10.4 onOpen() - Verbindung hergestellt
    → isConnected = true
    → reconnectAttempts = 0 zurücksetzen
    → reconnecting = false
    → listener.onWebSocketOpen() → AppActivity UI Update
    → startHeartbeat() → Custom Ping alle 30s

10.5 onMessage(text) - Nachricht empfangen
    → JSON parsen: JSONObject(text)
    → type extrahieren: json.optString("type")
    → switch(type):
        - "offer", "answer", "ice", "candidate", "system", "chat" 
          → listener.onNewSignalReceived(json)
        - "pong" → Heartbeat-Antwort ignorieren

10.6 Heartbeat Mechanismus
    → backgroundHandler.postDelayed(pingRunnable, 30000)
    → pingRunnable sendet: { type: "ping", timestamp: ... }
    → Backend antwortet mit: { type: "pong" }
    → Verhindert Connection Timeout (zusätzlich zu OkHttp Ping)

10.7 onClosing() / onFailure() - Verbindung verloren
    → isConnected = false
    → listener.onWebSocketClosed() oder listener.onError()
    → Falls NICHT userInitiatedDisconnect:
      → scheduleReconnectIfNeeded()

10.8 scheduleReconnectIfNeeded() - Exponential Backoff
    → Prüfung: lastRoomId & lastToken vorhanden? (FIX #5)
    → Falls NEIN: onReconnectFailed() + return (verhindert Logout-Loop)
    → reconnecting = true
    → reconnectAttempts++ (max 8)
    → Delay berechnen: 1s, 2s, 4s, 8s, 16s, 32s, 64s (max 60s)
    → Jitter hinzufügen: ±20% Zufallsvariation
    → listener.onReconnecting(attempt, delay)
    → backgroundHandler.postDelayed(reconnect, delay)

10.9 Max Attempts erreicht (8)
    → NICHT aufgeben! (FIX from original code)
    → reconnectAttempts = 6 (zurück zu mittlerem Backoff)
    → 2 Minuten warten, dann weiter versuchen
    → Verhindert permanenten Ausfall bei langen Netzwerk-Problemen

10.10 disconnect() - Manuelles Trennen
    → userInitiatedDisconnect = true (verhindert Auto-Reconnect)
    → stopHeartbeat() → backgroundHandler.removeCallbacks()
    → webSocket.close(1000, "App disconnected")
    → lastRoomId = null (FIX #5 - verhindert Reconnect nach Logout)
    → lastToken = null
    → handlerThread.quitSafely() (Cleanup)
```

**Reconnection Flow:**
```
[WebSocket Verbindung verloren]
    ↓
onFailure() oder onClosing()
    ↓
[userInitiatedDisconnect?] ─YES→ STOP (kein Reconnect)
    ↓ NO
[lastRoomId & lastToken valid?] ─NO→ onReconnectFailed()
    ↓ YES
reconnectAttempts++ (max 8)
    ↓
delay = exponential_backoff(attempts) + jitter
    ↓
listener.onReconnecting(attempt, delay)
    ↓
wait {delay}ms
    ↓
connect(lastRoomId, lastToken) erneut
    ↓
[Erfolgreich?] ─YES→ reconnectAttempts = 0
    ↓ NO
Zurück zu scheduleReconnectIfNeeded()
```

---

## 🔚 App Shutdown - onDestroy() - Zeile 501-548

### 11. Activity Lifecycle Ende & Cleanup

**Was passiert (mit Phase 1 Fixes):**
```
11.1 stopQueuePolling() (FIX #3)
    → queuePollingHandler.removeCallbacksAndMessages(null)
    → queuePollingHandler = null
    → queuePollingRunnable = null
    → Verhindert Battery Drain nach Logout

11.2 stopRingtone()
    → ringtonePlayer?.stop()
    → ringtonePlayer?.release()
    → ringtonePlayer = null

11.3 notificationPlayer cleanup
    → notificationPlayer?.release()
    → notificationPlayer = null

11.4 Popup Window cleanup (FIX #19)
    → currentPopupWindow?.dismiss()
    → currentPopupWindow = null

11.5 Service Callbacks clear (FIX #1)
    → isServiceBound = false (Flag FIRST!)
    → callService?.onCallReceived = null
    → callService?.onConnectionStateChanged = null
    → try { unbindService() } catch (IllegalArgumentException)

11.6 Monitoring & Notifications cleanup
    → monitoringClient?.disconnect()
    → monitoringClient = null
    → notificationsClient?.disconnect()
    → notificationsClient = null

11.7 HTTP Calls cancel (FIX #7 - Thread-safe)
    → synchronized(activeCalls) {
        activeCalls.forEach { it.cancel() }
        activeCalls.clear()
      }

11.8 WebSocket & WebRTC cleanup (FIX #2 - lateinit checks)
    → if (::signalingClient.isInitialized) {
        signalingClient.disconnect()
      }
    → if (::webRtcClient.isInitialized) {
        webRtcClient.close()
      }

11.9 PeerConnectionClient.close()
    → localAudioTrack?.dispose()
    → localAudioTrack = null
    → peerConnection?.close()
    → peerConnection = null (FIX #20)
    → audioSource?.dispose() (FIX #21)
    → audioSource = null
```

**Cleanup-Reihenfolge (kritisch!):**
```
1. Queue Polling stoppen (verhindert neue HTTP Requests)
2. Medienplayer freigeben (Audio-Ressourcen)
3. Popups schließen (UI-Cleanup)
4. Service-Flag setzen (verhindert neue Callbacks) ← FIX #1
5. Service-Callbacks clearen
6. Service unbinden (mit try-catch)
7. WebSocket-Clients trennen
8. HTTP-Calls abbrechen (synchronized) ← FIX #7
9. WebRTC schließen (mit lateinit checks) ← FIX #2
10. Audio-Tracks & PeerConnection aufräumen
```

---

## 📊 State Management - Kritische Variablen

### 12. Shared State & Thread-Safety

**Thread-Sensitive Variablen:**
```kotlin
@Volatile
private var isServiceBound = false  // FIX #6 - Volatile für Multi-Thread Access

private var activeCallSessionId: String? = null  // UI Thread only
private var isWebRTCActive: Boolean = false      // UI Thread only
private var isOutgoingCall: Boolean = false      // UI Thread only

private var currentToken: String? = null  // UI Thread only (EncryptedPrefs via AuthClient)
private var currentRoom: String? = null   // UI Thread only

// Thread-safe Collection (FIX #7)
private val activeCalls = java.util.Collections.synchronizedList(
    mutableListOf<okhttp3.Call>()
)

// UI-only (RecyclerView)
private val liveVisitors = mutableListOf<Visitor>()
```

**State Transitions (Call Flow):**
```
[IDLE]
    ↓
activeCallSessionId = null
isWebRTCActive = false
isOutgoingCall = false
    ↓
[Agent klickt "Anrufen"]
    ↓
activeCallSessionId = visitor.sessionId
isWebRTCActive = true
isOutgoingCall = true
    ↓
[CALLING]
    ↓
WebRTC Offer → Answer → ICE Exchange
    ↓
[ACTIVE]
    ↓
Audio Stream läuft
    ↓
[User klickt "Auflegen" ODER Visitor hängt auf]
    ↓
endCall()
    ↓
activeCallSessionId = null
isWebRTCActive = false
isOutgoingCall = false
    ↓
[IDLE]
```

---

## 🎯 Critical Paths - Was MUSS funktionieren

### 13. Must-Work Scenarios

**1. Login → Queue Polling → Call Annehmen:**
```
✅ onCreate() → Permission Check → startClientsAndAutoConnect()
✅ performLoginUI() → AuthClient.login() → Token speichern
✅ startQueuePolling() → Handler alle 5s
✅ fetchQueuedCalls() → RecyclerView aktualisieren
✅ AgentNotificationsClient → incoming_call Event
✅ playRingtone() + showIncomingCallDialog()
✅ handleIncomingOffer() → createAnswer() → send(answer)
✅ ICE Exchange → Audio Stream
✅ endCall() → State cleanup
```

**2. Logout → Polling Stop → Service Stop:**
```
✅ Logout-Dialog bestätigt
✅ stopQueuePolling() (FIX #3) → Handler stoppen
✅ signalingClient.disconnect()
✅ stopCallService() → Service beenden
✅ authClient.clearToken() → EncryptedPrefs löschen
✅ UI Reset → liveVisitors.clear()
```

**3. App Minimize → CallService läuft weiter:**
```
✅ onPause() → Activity pausiert (aber CallService läuft)
✅ CallService.onTaskRemoved() → Service restart nach 3s
✅ AgentNotificationsClient bleibt verbunden
✅ Incoming Call → System Notification + Ringtone
✅ User klickt Notification → App öffnet → Call wird angenommen
```

**4. Network Loss → Reconnect → Call continues:**
```
✅ WebSocket onFailure()
✅ scheduleReconnectIfNeeded() → Exponential Backoff
✅ reconnectAttempts++ → 1s, 2s, 4s, ... delay
✅ connect(lastRoomId, lastToken) erneut
✅ onOpen() → reconnectAttempts = 0
✅ ICE Connection Recovery → Audio stream bleibt aktiv
```

---

## 🐛 Known Edge Cases & Fixes

### 14. Spezielle Situationen

**Edge Case 1: Visitor hängt während ICE Gathering auf**
```
Problem: Agent hat Offer gesendet, aber Visitor schließt Browser
Lösung:
  → Backend sendet "call_ended" Event nach 30s Timeout
  → AppActivity.onCallEnded() → endCall() automatisch
  → UI zurück zu Queue-Liste
```

**Edge Case 2: Agent nimmt 2 Calls gleichzeitig an (Race Condition)**
```
Problem: Agent klickt "Annehmen" für Call A, aber Call B kommt rein bevor WebRTC aktiv
Lösung:
  → handleIncomingOffer() prüft: activeCallSessionId != null && != callerSessionId
  → Falls TRUE: send({ type: "busy" }) an Call B
  → Visitor B bekommt "Agent beschäftigt" Meldung
```

**Edge Case 3: Token expired während aktivem Call**
```
Problem: Token läuft ab (z.B. nach 24h), HTTP Requests geben 401 zurück
Aktuell: Queue Polling schlägt fehl, aber Call läuft weiter (WebSocket bleibt aktiv)
TODO: Token Refresh Mechanism (Phase 2 - HIGH Priority Fix #15)
```

**Edge Case 4: Permission revoked während Call**
```
Problem: User revoked RECORD_AUDIO in Settings während Call
Lösung: (NICHT implementiert - selten!)
  → Android beendet Microphone Access automatisch
  → WebRTC onIceConnectionState: FAILED
  → errorReporter.reportWebRTCError("Microphone access lost")
  → Toast: "Mikrofon-Zugriff verloren"
  → endCall() automatisch
```

**Edge Case 5: Device Sleep während Call**
```
Problem: Screen geht aus, Device schläft → WebSocket disconnected
Lösung:
  → CallService hält WakeLock (PARTIAL_WAKE_LOCK)
  → Device schläft NICHT während Service aktiv
  → WakeLock Timeout: 10 Minuten (TODO: FIX #16 - renewal)
```

---

## 🔍 Debugging Hooks

### 15. Debug-Informationen im Log

**Wichtige Log-Tags:**
```kotlin
"AppActivity"           // Haupt-UI Events
"SignalingClient"       // WebSocket Lifecycle
"AgentNotifications"    // Realtime Events
"CallService"           // Background Service
"WebRTC"               // PeerConnection Events
"AuthClient"           // Login/Auth
"ErrorReporter"        // Error Reporting
```

**Wichtige Log-Messages:**
```
// Login
"🔍 [DEBUG] Auto-Login Check: token=EXISTS"
"✅ Angemeldet - gespeichert"

// Queue
"Queue API returned: total=5, queues.length=5"
"🆕 New call notification: tarba__uuid-123 (Tarba)"

// WebSocket
"🔍 [DEBUG] ========== WEBSOCKET OPENED =========="
"🔍 [DEBUG] ========== WEBSOCKET FAILURE =========="
"Reconnecting attempt 3 with delay 4000ms"

// Call Flow
"🔔 Call ringing: tarba__uuid-123 (initiator: visitor)"
"WebRTC Offer sent to room tarba__uuid-123"
"✅ Sent call_accept to backend"
"Remote offer set successfully"

// ICE
"Sent heartbeat ping"
"Received pong"
"ICE connection state: CONNECTED"
```

---

## 📝 Zusammenfassung - Was passiert WANN

### Cold Start bis erster Call (Timeline)

```
00:00.000 - onCreate()
00:00.050 - ErrorReporter initialisiert
00:00.100 - UI-Elemente via findViewById()
00:00.150 - Audio Permission Check
00:00.200 - [User gewährt Permission]
00:00.250 - initializeWebRTC() - PeerConnectionFactory
00:00.300 - AuthClient initialisieren
00:00.350 - SignalingClient initialisieren
00:00.400 - Token aus EncryptedSharedPrefs laden
00:00.450 - Auto-Login: Token EXISTS
00:00.500 - startQueuePolling() - Handler POST
00:00.550 - initializeNotifications(token)
00:00.600 - AgentNotificationsClient.connect()
00:00.650 - startCallServiceForFcm(token)
00:00.700 - CallService.onStartCommand()
00:00.750 - startForeground() - Notification
00:00.800 - WakeLock.acquire(10min)
00:00.850 - AgentNotificationsClient WebSocket OPEN
00:00.900 - "✅ Realtime verbunden"
00:01.000 - [5 Sekunden später]
00:05.000 - fetchQueuedCalls() - HTTP Request
00:05.100 - GET /api/agent/queues - Response
00:05.150 - RecyclerView aktualisieren - 3 Calls in Queue
00:05.200 - visitorCountBadge: "3"
00:10.000 - [5 Sekunden später - Polling Cycle]
00:10.100 - fetchQueuedCalls() erneut
00:15.000 - [WebSocket Event]
00:15.050 - { type: "new_call", room_id: "tarba__uuid-456" }
00:15.100 - onNewCall() - Add to liveVisitors
00:15.150 - playNotificationSound() 🔔
00:15.200 - RecyclerView: 4 Calls (3 alt + 1 neu)
00:20.000 - [WebSocket Event]
00:20.050 - { type: "incoming_call", room_id: "tarba__uuid-456", from: "visitor" }
00:20.100 - onCallRinging() - Play Ringtone 🔔🔔🔔
00:20.150 - showIncomingCallDialog()
00:22.000 - [Agent klickt "Annehmen"]
00:22.050 - stopRingtone()
00:22.100 - [WebSocket empfängt Offer]
00:22.150 - handleIncomingOffer()
00:22.200 - activeCallSessionId = "tarba__uuid-456"
00:22.250 - showActiveCallTab()
00:22.300 - send({ type: "call_accept" })
00:22.350 - setRemoteDescription(offer)
00:22.400 - createAnswer()
00:22.450 - setLocalDescription(answer)
00:22.500 - send(answer)
00:22.600 - [ICE Exchange startet]
00:22.700 - onIceCandidate() - send(ice) x5
00:22.800 - receive(ice) from Visitor x5
00:22.900 - addIceCandidate() x5
00:23.000 - onIceConnectionChange: CONNECTED
00:23.050 - connectionQualityView: "excellent"
00:23.100 - 🎤 Audio Stream aktiv!
00:23.200 - activeCallInfo: "Verbunden, im Gespräch"
[... Call läuft 5 Minuten ...]
05:23.000 - [Agent klickt "Auflegen"]
05:23.050 - endCall()
05:23.100 - send({ type: "hangup" })
05:23.150 - activeCallSessionId = null
05:23.200 - showVisitorsTab()
05:23.250 - [Backend: call_ended Event an Visitor]
05:23.300 - statusTextView: "Status: ✅ 3 wartende(r) Anruf(e)"
```

---

## ✅ Verification Checklist

**Testen, ob Logic korrekt funktioniert:**

- [ ] **Login Flow**
  - Login-Dialog öffnet sich
  - Email/Password Validierung funktioniert
  - Token wird in EncryptedSharedPrefs gespeichert
  - Auto-Login beim nächsten Start

- [ ] **Queue Polling**
  - Handler läuft alle 5 Sekunden
  - HTTP Request zu /api/agent/queues
  - RecyclerView zeigt wartende Calls
  - Badge zeigt korrekte Anzahl

- [ ] **Realtime Notifications**
  - WebSocket zu /api/agent/notifications verbindet
  - new_call Event fügt Call hinzu
  - incoming_call Event spielt Ringtone
  - call_ended Event entfernt Call

- [ ] **Outgoing Call (Agent → Visitor)**
  - "Anrufen" Button startet Offer
  - WebSocket verbindet zu Room
  - Offer wird gesendet
  - Answer empfangen
  - ICE Exchange erfolgreich
  - Audio Stream aktiv

- [ ] **Incoming Call (Visitor → Agent)**
  - incoming_call Event empfangen
  - Ringtone spielt
  - Dialog "Annehmen?" erscheint
  - Offer empfangen
  - call_accept gesendet
  - Answer erstellt und gesendet
  - Audio Stream aktiv

- [ ] **Call Ende**
  - "Auflegen" Button sendet hangup
  - State wird zurückgesetzt
  - UI zurück zu Queue
  - Andere Seite bekommt call_ended

- [ ] **Service Persistence**
  - CallService startet nach Login
  - Foreground Notification erscheint
  - App aus Recent Apps wischen → Service läuft weiter
  - Notification bleibt sichtbar

- [ ] **Logout**
  - Queue Polling stoppt (FIX #3)
  - WebSocket disconnected
  - Service gestoppt
  - Token gelöscht
  - UI resettet

- [ ] **Network Recovery**
  - Airplane Mode aktivieren
  - WebSocket disconnected
  - Reconnection versucht mit Backoff
  - Airplane Mode deaktivieren
  - WebSocket reconnected

- [ ] **Cleanup (onDestroy)**
  - Alle Handler gestoppt
  - Service unbound (mit Flag-Fix)
  - HTTP Calls cancelled (synchronized)
  - WebRTC geschlossen (mit lateinit checks)

---

**Ende der Logic-Beschreibung**

Diese Beschreibung deckt alle kritischen Pfade ab. Für Details zu spezifischen Features (Admin-Panel, Supervisor-Monitoring, Analytics) siehe jeweilige Code-Sections in AppActivity.kt.
