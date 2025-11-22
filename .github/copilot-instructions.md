# 2bro4Call - AI Coding Agent Instructions

## Project Overview
**2bro4Call** is an Android VoIP application that enables real-time WebRTC audio calls between website visitors and agents. Built with Kotlin, it integrates WebSocket signaling, Firebase Cloud Messaging, and foreground services for persistent call handling.

**Package:** `com.x2bro4pro.bro4call`  
**Backend:** `call-server.netdoc64.workers.dev` (Cloudflare Workers)

## Architecture

### Core Components
1. **AppActivity** (`AppActivity.kt`) - Main UI controller, implements `SignalingListener`
   - Manages visitor list (RecyclerView with `VisitorAdapter`)
   - Handles authentication via `AuthClient`
   - Controls WebRTC peer connections via embedded `PeerConnectionClient` class
   - Binds to `CallService` for background call handling

2. **CallService** (`CallService.kt`) - Foreground service for persistent connectivity
   - Maintains WebSocket connection via `SignalingClient`
   - Shows notifications for incoming calls (FOREGROUND_SERVICE_TYPE_MICROPHONE)
   - Implements `SignalingListener` for real-time signaling
   - Uses WakeLock to prevent connection drops

3. **SignalingClient** (`SignalingClient.kt`) - WebSocket manager
   - Connects to `wss://call-server.netdoc64.workers.dev/call/{roomId}?token={token}&mode={mode}`
   - Implements exponential backoff reconnection (max 8 attempts, 20% jitter)
   - Modes: `talk` (agent), `listen` (supervisor monitoring)

4. **AuthClient** (`AuthClient.kt`) - Authentication & secure storage
   - REST API client for login/register (`/api/login`, `/api/register`)
   - Uses `EncryptedSharedPreferences` for JWT token storage
   - Manages role-based access (agent, admin, supervisor)

5. **MyFirebaseMessagingService** - Push notifications when app is closed
   - Handles `incoming_call` and `call_ended` FCM messages
   - Starts `CallService` on incoming calls

### Data Flow
```
Web Visitor → Backend WebSocket → CallService (background) → Notification
                                                          ↓
                                                  AppActivity (UI updates)
                                                          ↓
                                               WebRTC PeerConnection
```

## Critical Developer Conventions

### Room ID Pattern
- **Format:** `{DOMAIN_ID}__{SESSION_ID}` (double underscore separator)
- Example: `tarba_schlusseldienst__uuid`
- Domain ID must match backend configuration
- Generated in `AppActivity.generateCallRoomId()`

### SignalingListener Interface
All components that interact with WebSocket must implement:
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

### WebRTC Configuration
- **STUN Server:** `stun:stun.l.google.com:19302`
- **WebRTC Library:** `com.dafruits:webrtc:113.0.0` (Maven Central - avoid others)
- **Packaging:** Resolves native library conflicts via `pickFirst` in `app/build.gradle`

### Service Binding Pattern
```kotlin
// AppActivity always binds to CallService:
private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val serviceBinder = binder as CallService.CallServiceBinder
        callService = serviceBinder.getService()
        // Setup callbacks: onCallReceived, onConnectionStateChanged
    }
}
```

### Theme & UI Style
- **Theme:** `Theme.SciFi` (dark sci-fi aesthetic)
- **Colors:** Neon cyan (`#00E5FF`), magenta accents
- **Layout:** `activity_app_layout.xml` uses MaterialCardView with space gradient background
- All buttons styled via `Widget.SciFi.Button` (12dp corner radius)

## Build & Development

### Build Commands
```bash
# Debug build
./gradlew assembleDebug --stacktrace

# Clean build
./gradlew clean assembleDebug

# Install on connected device
./gradlew installDebug
```

### CI/CD
- GitHub Actions workflow: `.github/workflows/android_build.yml`
- Requires JDK 17 (Gradle 8.1.1 + AGP 8.1.1)
- Artifacts uploaded to GitHub (app-debug.apk)

### Firebase Configuration
- **Project ID:** `bro4call`
- **Package:** `com.x2bro4pro.bro4call`
- Config file: `app/google-services.json` (don't commit API keys)

### Key Gradle Settings
```properties
android.useAndroidX=true
android.enableJetifier=true
android.suppressUnsupportedCompileSdk=34  # SDK 34 with Gradle 8.0
org.gradle.jvmargs=-Xmx2048m             # Increase heap for build
```

## Common Patterns

### Adding New Signaling Message Types
1. Update `SignalingClient.onMessage()` to parse new type
2. Add handler in `AppActivity.onNewSignalReceived()` or `CallService.onNewSignalReceived()`
3. Send messages via `SignalingClient.send(JSONObject)`

### Testing Reconnection Logic
- Disable network in emulator/device
- Check `onReconnecting()` callbacks in logs
- Verify exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s

### Permission Handling
Required runtime permissions (API 24+):
- `RECORD_AUDIO` - request in `AppActivity.onCreate()`
- `POST_NOTIFICATIONS` (API 33+) - for incoming call alerts
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` - for reliable background service

## Known Issues & Workarounds

### Native Library Conflicts
WebRTC includes `libc++_shared.so` for multiple ABIs. Resolve with:
```gradle
packagingOptions {
    pickFirst 'lib/x86/libc++_shared.so'
    pickFirst 'lib/x86_64/libc++_shared.so'
    pickFirst 'lib/armeabi-v7a/libc++_shared.so'
    pickFirst 'lib/arm64-v8a/libc++_shared.so'
}
```

### Backend Host Configuration
Always use **domain only** for `SignalingClient`, **full URL** for `AuthClient`:
```kotlin
SignalingClient(listener, "call-server.netdoc64.workers.dev")
AuthClient(context, "https://call-server.netdoc64.workers.dev")
```

### FCM Token Registration
`TOO_MANY_REGISTRATIONS` error: Disable auto token sending in `onTokenRefresh()` during development.

## Testing Checklist
- [ ] WebSocket connects after login
- [ ] Incoming call notification appears (test with backend trigger)
- [ ] Audio streams established (check WebRTC peer connection state)
- [ ] Service survives app kill (boot receiver)
- [ ] Reconnection works after network drop
- [ ] Battery optimization dialog shown (first run)
