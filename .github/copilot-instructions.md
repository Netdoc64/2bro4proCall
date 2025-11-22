# 2bro4Call - AI Coding Agent Instructions

## Project Overview
**2bro4Call** is an Android VoIP app enabling real-time WebRTC audio calls between website visitors and agents. Built with Kotlin, it integrates WebSocket signaling, Firebase Cloud Messaging, and foreground services for persistent call handling.

**Package:** `com.x2bro4pro.bro4call`  
**Backend:** `call-server.netdoc64.workers.dev` (Cloudflare Workers)  
**Min SDK:** 24 | **Target SDK:** 34 | **Gradle:** 8.1.1 | **AGP:** 8.1.1 | **Kotlin:** 1.9.0

## Architecture & Data Flow

### Core Component Interactions
```
Web Visitor → Backend WebSocket → CallService (foreground) → Notification
                                         ↓                        ↓
                                   SignalingClient         (User taps)
                                         ↓                        ↓
                                   AppActivity ← Service Binding →
                                         ↓
                                 PeerConnectionClient (WebRTC)
```

### Key Components
1. **AppActivity** (1734 lines) - Main UI controller implementing `SignalingListener`
   - Contains **embedded inner classes** `VisitorAdapter` (line 1575) and `PeerConnectionClient` (line 1603)
   - Binds to `CallService` via `ServiceConnection` pattern
   - Manages visitor RecyclerView and WebRTC peer connections
   - Sets global crash handler writing to `filesDir/last_crash.log`

2. **CallService** - Foreground service (`FOREGROUND_SERVICE_TYPE_MICROPHONE`)
   - Maintains persistent WebSocket connection via `SignalingClient`
   - Uses `WakeLock` to prevent connection drops
   - Auto-restarts on task removal (`onTaskRemoved()`) with 3s delay
   - Returns `START_STICKY` to survive crashes

3. **SignalingClient** - WebSocket manager with intelligent reconnection
   - URL format: `wss://{HOST}/call/{roomId}?token={token}&mode={mode}`
   - Modes: `talk` (agent), `listen` (supervisor monitoring)
   - **Exponential backoff:** Resets to mid-backoff at max attempts instead of giving up
   - **Heartbeat:** Sends `{"type":"ping"}` every 30s to keep connection alive
   - Validates `lastRoomId` and `lastToken` before reconnect attempts

4. **AuthClient** - REST authentication using `EncryptedSharedPreferences`
   - Endpoints: `/api/login`, `/api/register`
   - Storage keys: `jwt_token`, `jwt_role`, `jwt_domains`, `active_room_id`
   - Uses `MasterKeys.AES256_GCM_SPEC` for credential encryption
   - Max 3 retries with `retryOnConnectionFailure=true`

5. **BootReceiver** - Auto-starts `CallService` after device reboot
   - Only starts if valid JWT token exists
   - Loads or generates room ID from `AuthClient.getRoomId()`
   - Handles `IllegalStateException` for service start failures

6. **NetworkChangeReceiver** - Triggers reconnection on network change (WiFi ↔ Mobile)

## Critical Developer Conventions

### Room ID Pattern (STRICT)
```kotlin
// Format: {DOMAIN_ID}__{SESSION_ID} (double underscore is separator)
fun generateCallRoomId(domainId: String = "tarba_schlusseldienst"): String {
    val sessionId = java.util.UUID.randomUUID().toString()
    return "${domainId}__${sessionId}"  // tarba_schlusseldienst__uuid
}
```
- Must match backend domain configuration
- Backend parses on `__` to extract domain and session

### SignalingListener Contract
All WebSocket components must implement:
```kotlin
interface SignalingListener {
    fun onWebSocketOpen()
    fun onNewSignalReceived(message: JSONObject)
    fun onWebSocketClosed()
    fun onError(message: String)
    fun onReconnecting(attempt: Int, delayMs: Int)
    fun onReconnectFailed()
}
```

### Backend Host Configuration (CRITICAL)
```kotlin
// SignalingClient: domain ONLY (adds wss:// and path)
SignalingClient(listener, "call-server.netdoc64.workers.dev")

// AuthClient: FULL URL (makes direct HTTP requests)
AuthClient(context, "https://call-server.netdoc64.workers.dev")
```
Mixing these causes connection failures.

### WebRTC Library Selection
**Only use:** `com.dafruits:webrtc:113.0.0` (Maven Central)
- Other WebRTC artifacts cause "Not Found" errors or ABI conflicts
- Requires `pickFirst` packaging for `libc++_shared.so` (see `app/build.gradle:52-57`)

### Service Binding Pattern
```kotlin
private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val serviceBinder = binder as CallService.CallServiceBinder
        callService = serviceBinder.getService()
        // Register callbacks for bidirectional communication
        callService?.onCallReceived = { sessionId, domain -> /* ... */ }
        callService?.onConnectionStateChanged = { connected -> /* ... */ }
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        callService = null
        isServiceBound = false
    }
}
```

### UI Theme System
- **Theme:** `Theme.SciFi` (parent: `Theme.MaterialComponents.DayNight.NoActionBar`)
- **Layout:** `activity_app_layout_glass.xml` (glassmorphism design)
- **Button Style:** `Widget.Glass.Button` (16dp corner radius)
  - Variants: `.Primary`, `.Secondary`, `.Success` with custom backgrounds
- **Colors:** Defined in `res/values/colors.xml` (glass_accent_1, glass_accent_2, etc.)

## Build & Development

### Build Commands
```bash
# Clean build (recommended after dependency changes)
./gradlew clean assembleDebug --stacktrace

# Debug build
./gradlew assembleDebug

# Install to connected device/emulator
./gradlew installDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Gradle Configuration
- **Suppress SDK warning:** `android.suppressUnsupportedCompileSdk=34` (Gradle 8.0 with SDK 34)
- **Memory:** `org.gradle.jvmargs=-Xmx2048m` (prevents OOM during build)
- **Repositories:** `google()`, `mavenCentral()` (no jitpack or custom repos)

### ProGuard Rules (Release)
```gradle
# app/proguard-rules.pro
-keep class org.webrtc.** { *; }          # WebRTC native methods
-keep class com.x2bro4pro.bro4call.** { *; } # Keep all app classes
-keep class okhttp3.** { *; }             # WebSocket client
-keep class androidx.security.crypto.** { *; } # EncryptedSharedPreferences
```

### CI/CD
- **Workflow:** `.github/workflows/android_build.yml`
- **JDK Version:** 17 (required for Gradle 8.0+)
- **Artifacts:** Uploads `app-debug.apk` via `actions/upload-artifact@v4`

### Firebase Setup
- **Project:** `bro4call`
- **Config:** `app/google-services.json` (auto-generates `build/generated/res/processDebugGoogleServices/values/values.xml`)
- **FCM Channels:** `fcm_notifications`, `incoming_call_channel`

## Common Development Patterns

### Adding Signaling Message Types
1. **Parse in SignalingClient:**
```kotlin
// SignalingClient.kt line ~105
override fun onMessage(webSocket: WebSocket, text: String) {
    val json = JSONObject(text)
    when (json.optString("type")) {
        "offer", "answer", "candidate", "system" -> listener.onNewSignalReceived(json)
        "your_new_type" -> listener.onNewSignalReceived(json)
    }
}
```

2. **Handle in listeners:**
```kotlin
// AppActivity.kt or CallService.kt
override fun onNewSignalReceived(message: JSONObject) {
    when (message.optString("type")) {
        "your_new_type" -> handleYourMessage(message)
    }
}
```

3. **Send from client:**
```kotlin
val msg = JSONObject().apply {
    put("type", "your_new_type")
    put("data", someValue)
}
signalingClient.send(msg)
```

### Testing Reconnection Logic
```bash
# Enable airplane mode, then disable after 10s
adb shell cmd connectivity airplane-mode enable
# Check logs for: "Reconnecting attempt X with delay Y ms"
adb logcat | grep "SignalingClient"
```
Expected backoff delays: 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s (then resets to mid-range)

### Permission Handling
```kotlin
// AppActivity.onCreate() checks:
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
    != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
}
// Required: RECORD_AUDIO, POST_NOTIFICATIONS (API 33+), REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

### Input Sanitization
```kotlin
// All user inputs pass through:
private fun sanitizeInput(input: String): String {
    return input.replace(Regex("[<>&\"']"), "").trim().take(200)
}
```

## Known Issues & Fixes

### Native Library Conflicts
**Symptom:** `Duplicate files copied in APK lib/*/libc++_shared.so`  
**Fix:** Already configured in `app/build.gradle`:
```gradle
packagingOptions {
    pickFirst 'lib/x86/libc++_shared.so'
    pickFirst 'lib/x86_64/libc++_shared.so'
    pickFirst 'lib/armeabi-v7a/libc++_shared.so'
    pickFirst 'lib/arm64-v8a/libc++_shared.so'
}
```

### Reconnection Loops on Logout
**Cause:** `SignalingClient` stores `lastRoomId`/`lastToken` and attempts reconnect even after logout  
**Fix:** Implemented in `SignalingClient.scheduleReconnectIfNeeded()`:
```kotlin
if (roomNow.isNullOrBlank() || tokenNow.isNullOrBlank()) {
    listener.onReconnectFailed()
    return // Don't reconnect without credentials
}
```

### FCM Token Registration Errors
**Symptom:** `TOO_MANY_REGISTRATIONS`  
**Cause:** Rapid token refresh during development  
**Fix:** Disable auto-sending in `MyFirebaseMessagingService.onNewToken()` for debug builds

## Testing Checklist
- [ ] Login/Register with backend (credentials stored encrypted)
- [ ] WebSocket connects after login (check "WebSocket connection opened" log)
- [ ] Incoming call notification (test with backend FCM trigger)
- [ ] Answer call → WebRTC audio established (check PeerConnection state logs)
- [ ] App backgrounded → `CallService` stays alive (check notification)
- [ ] Device reboot → `BootReceiver` restarts service (if logged in)
- [ ] Network loss → reconnection backoff (check "Reconnecting attempt X" logs)
- [ ] Battery optimization dialog shown on first run
