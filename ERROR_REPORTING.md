# Error Reporting System - Integration Guide

## Overview
Das Error Reporting System wurde erfolgreich in die 2bro4Call Android App integriert. Es ermöglicht automatisches Tracking von Fehlern, Crashes und Problemen mit Backend-Integration gemäß API v2.3.

## Components

### 1. ErrorReporter.kt
Neue Klasse für Error Reporting mit folgenden Features:

**Unterstützte Error Types:**
- `CRASH` - App crashes (fatal)
- `NETWORK` - Netzwerkfehler (HTTP, WebSocket)
- `WEBRTC` - WebRTC Connection Failures
- `PERMISSION` - Permission denials
- `UI` - UI-bezogene Fehler
- `OTHER` - Sonstige Fehler

**Severity Levels:**
- `FATAL` - Kritische Fehler (App crash)
- `ERROR` - Schwere Fehler (Feature nicht verfügbar)
- `WARNING` - Warnungen (Degraded functionality)
- `INFO` - Informative Meldungen

**Features:**
- ✅ Anonymous reporting (kein Auth erforderlich)
- ✅ Optional: User-Verknüpfung mit JWT Token
- ✅ Automatische Device-Info Collection (Model, OS, Screen Size)
- ✅ Stack Trace Capture (max 10k chars)
- ✅ Context Information (screen, action, call_id, domain_id)
- ✅ Async reporting (blockiert App nicht)

### 2. Integration Points

#### AppActivity.kt
**Global Crash Handler:**
```kotlin
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    // Writes to local file (filesDir/last_crash.log)
    // Reports to backend with user token if available
    errorReporter.reportCrash(...)
}
```

**Permission Errors:**
- RECORD_AUDIO permission denial → reported
- POST_NOTIFICATIONS permission denial → reported (warning level)

**Network Errors:**
- Queue loading failures → reported
- Admin API errors → reported with endpoint context

**WebRTC Errors:**
- ICE connection DISCONNECTED → reported (error level)
- ICE connection FAILED → reported (critical level)

#### SignalingClient.kt
**WebSocket Failures:**
- Connection failures → reported with backend host + response code
- Only reports if not user-initiated disconnect
- Includes reconnect attempt number in context

## API Endpoint

```
POST https://call-server.netdoc64.workers.dev/api/errors/report

Headers:
  Content-Type: application/json
  Authorization: Bearer <token> (optional - links to user)

Body:
{
  "app_version": "1.0",
  "platform": "android",
  "device_info": {
    "model": "Samsung Galaxy S21",
    "os_version": "Android 13 (API 33)",
    "screen_size": "1080x2400",
    "device_id": "...",
    "brand": "Samsung",
    "product": "..."
  },
  "error_type": "crash|network|webrtc|permission|ui|other",
  "error_message": "Error description (max 2000 chars)",
  "stack_trace": "Full stack trace (max 10k chars)",
  "context": {
    "screen": "AppActivity",
    "action": "load_queue",
    "call_id": "example.com__uuid",
    "domain_id": "example.com"
  },
  "severity": "fatal|error|warning|info"
}

Response:
{
  "status": "reported",
  "reportId": "err_1234567890"
}
```

## Usage Examples

### 1. Report a Crash
```kotlin
errorReporter.reportCrash(
    message = "App crashed: NullPointerException in WebRTC",
    throwable = exception,
    context = mapOf(
        "thread" to "main",
        "screen" to "AppActivity",
        "call_id" to activeCallSessionId
    ),
    authToken = currentToken
)
```

### 2. Report Network Error
```kotlin
errorReporter.reportNetworkError(
    message = "Failed to load queue: Connection timeout",
    throwable = ioException,
    context = mapOf(
        "endpoint" to "/api/admin/queue",
        "action" to "load_queue"
    ),
    authToken = token
)
```

### 3. Report WebRTC Error
```kotlin
errorReporter.reportWebRTCError(
    message = "WebRTC ICE connection failed",
    context = mapOf(
        "state" to "FAILED",
        "call_id" to sessionId,
        "severity" to "critical"
    ),
    authToken = currentToken
)
```

### 4. Report Permission Error
```kotlin
errorReporter.reportPermissionError(
    message = "RECORD_AUDIO permission denied by user",
    context = mapOf(
        "permission" to "RECORD_AUDIO",
        "screen" to "AppActivity"
    ),
    authToken = null
)
```

## Testing

### 1. Test Crash Reporting
```bash
# Trigger a test crash
adb shell am force-stop com.x2bro4pro.bro4call
# Check backend logs for crash report
```

### 2. Test Network Error
```bash
# Enable airplane mode
adb shell cmd connectivity airplane-mode enable
# Try loading queue in app
# Disable airplane mode
adb shell cmd connectivity airplane-mode disable
```

### 3. Check Logs
```bash
# Filter for ErrorReporter logs
adb logcat | grep "ErrorReporter"

# Look for:
# ✅ Error reported successfully: err_xyz
# 🔴 FATAL ERROR: ...
# ⚠️  Failed to report error to backend
```

## Configuration

### Backend URL
Configured in `AppActivity.kt`:
```kotlin
errorReporter = ErrorReporter(
    context = applicationContext,
    backendBaseUrl = "https://call-server.netdoc64.workers.dev",
    appVersion = packageManager.getPackageInfo(packageName, 0).versionName
)
```

### Timeouts
Configured in `ErrorReporter.kt`:
```kotlin
OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
```

## Limitations

1. **Message Limits:**
   - Error message: max 2000 chars
   - Stack trace: max 10000 chars

2. **Async Only:**
   - All reporting is async (fire-and-forget)
   - No retry mechanism for failed reports

3. **No Batching:**
   - Each error is reported individually
   - No offline queue for failed reports

## Admin Dashboard

Errors können im Backend Admin Panel verwaltet werden:
```
GET /api/admin/errors
Authorization: Bearer <admin-token>

Response:
{
  "errors": [
    {
      "id": "err_123",
      "app_version": "1.0",
      "platform": "android",
      "error_type": "crash",
      "error_message": "...",
      "severity": "fatal",
      "timestamp": "2025-11-24T10:30:00Z",
      "user_id": "user_xyz",
      "resolved": false,
      "resolution_notes": null
    }
  ]
}
```

## Future Enhancements

1. **Offline Queue:**
   - Store failed reports in local DB
   - Retry when connection restored

2. **Error Aggregation:**
   - Batch similar errors
   - Send digest instead of individual reports

3. **User Feedback:**
   - Optional error dialog for user input
   - Include user comments in report

4. **Performance Metrics:**
   - Track ANR (Application Not Responding)
   - Monitor memory usage
   - Track battery consumption

## Rollout Status

✅ **Implemented:**
- ErrorReporter.kt created
- Global crash handler integrated
- WebRTC error reporting
- Network error reporting
- Permission error reporting
- SignalingClient integration

⏳ **Pending:**
- Admin dashboard testing
- Error resolution workflow
- Performance monitoring integration

## Version History

**v2.3.0 (Nov 24, 2025)**
- Initial implementation
- API v2.3 compliance
- Support for all error types and severity levels
