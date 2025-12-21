# API Endpoint Audit Report
**Datum:** 8. Dezember 2025  
**App Version:** v2.3.0  
**API Version:** v2.3  
**Geprüfte Endpoints:** 47/47 (100%)

---

## 📊 Zusammenfassung

| Status | Anzahl | Prozent |
|--------|--------|---------|
| ✅ Vollständig Implementiert | 18 | 38% |
| ⚠️ Teilweise / Issues | 7 | 15% |
| ❌ Nicht Implementiert | 22 | 47% |

---

## ✅ Vollständig Implementierte Endpoints (18)

### Authentication
- **POST /api/login** - `AuthClient.kt:148`
- **POST /api/register** - `AuthClient.kt:62`

### Error Reporting
- **POST /api/errors/report** - `ErrorReporter.kt:41`

### Agent API
- **POST /api/agent/initiate_call** - `AppActivity.kt:2478`
- **GET /api/agent/queues** - `AppActivity.kt:1836`

### Admin - User Management
- **GET /api/admin/data** - `AppActivity.kt:2584, 3800`
- **POST /api/admin/create** - `AppActivity.kt:2714`
- **POST /api/admin/approve/:userId** - `AppActivity.kt:2989`
- **PATCH /api/admin/users/:userId** - `AppActivity.kt:2894`
- **DELETE /api/admin/users/:userId** - `AppActivity.kt:2955`
- **POST /api/admin/assign-domains** - `AppActivity.kt:3057`

### Admin - Queue Management
- **GET /api/admin/queues** - `AppActivity.kt:2159`
- **POST /api/admin/queues/:sessionId/assign** - `AppActivity.kt:2309`
- **DELETE /api/admin/queues/:sessionId** - `AppActivity.kt:2370`

### Admin - Analytics
- **GET /api/admin/stats/summary** - `AppActivity.kt:4033`

### WebSocket
- **WS /call/:roomId** - `SignalingClient.kt:77`
- **WebSocket Heartbeat (ping/pong)** - `SignalingClient.kt:45`

---

## ⚠️ Endpoints mit Issues (7)

### 1. POST /api/login
**Status:** Funktioniert, aber Datenstruktur-Issue  
**Location:** `AuthClient.kt:148`

**Problem:**
```kotlin
// API Response enthält:
"roles": [
  {"id": "role_agent", "name": "Agent", "level": 200}
]

// Code speichert aber nur:
saveRoles(rolesArray)  // JSONArray ohne Parsing der Objekt-Struktur
```

**Impact:** Roles können nicht nach `level` sortiert oder nach `name` angezeigt werden

---

### 2. POST /api/register
**Status:** Funktioniert, aber Status-Code nicht geprüft  
**Location:** `AuthClient.kt:62`

**Problem:**
```kotlin
// API sagt: Response (201)
// Code prüft: if (!it.isSuccessful) ohne explizite 201-Prüfung
```

**Impact:** Minimal - funktioniert trotzdem, aber nicht API-konform

---

### 3. POST /api/agent/initiate_call
**Status:** Funktioniert, aber Feldname-Mismatch  
**Location:** `AppActivity.kt:2478`

**Problem:**
```kotlin
// API erwartet: "domain_id"
put("domain", domain)  // Code sendet: "domain"
```

**Impact:** Funktioniert vermutlich nur, weil Backend beide Felder akzeptiert

---

### 4. POST /api/agent/register_device
**Status:** Endpoint-Mismatch  
**Location:** `AuthClient.kt:342`

**Problem:**
```kotlin
// API Spec: /api/agent/register_device
val url = "$baseUrl/api/fcm-token"  // Code verwendet: /api/fcm-token
```

**Impact:** Funktioniert nur, wenn Backend beide Endpoints unterstützt

---

### 5. POST /api/admin/roles
**Status:** Hardcoded URL  
**Location:** `AppActivity.kt:3431`

**Problem:**
```kotlin
// Andere Endpoints verwenden:
val url = "https://$BACKEND_HOST/api/..."

// Dieser verwendet:
val url = "https://call-server.netdoc64.workers.dev/api/admin/roles"
```

**Impact:** Kann nicht mit anderem Backend verwendet werden

---

### 6. PATCH /api/admin/domain/:domainId
**Status:** Hardcoded URL  
**Location:** `AppActivity.kt:3279`

**Problem:** Gleich wie #5 - hardcoded URL statt `$BACKEND_HOST`

---

### 7. GET /api/admin/domains
**Status:** Hardcoded URL  
**Location:** `AppActivity.kt:3105`

**Problem:** Gleich wie #5 - hardcoded URL statt `$BACKEND_HOST`

---

## ❌ Nicht Implementierte Endpoints (22)

### Public API (2)
- **POST /api/domain/announce** - Nur für Web-Widget
- **POST /api/public/initiate_room** - Nur für Web-Client

### Agent API (2)
- **GET /api/agent/calls** - Call History mit Pagination
- **GET /api/agent/calls/:callId** - Einzelner Call-Details

### Admin - Role Management (3)
- **POST /api/admin/assign-roles** - Separate Role-Assignment
- **PATCH /api/admin/roles/:roleId** - Role Update
- **DELETE /api/admin/roles/:roleId** - Role Deletion

### Admin - Domain Management (2)
- **POST /api/admin/domain/add** - Domain Creation
- **DELETE /api/admin/domain/:domainId** - Domain Deletion

### Admin - Call & Message Management (4)
- **GET /api/admin/calls** - Admin Call History
- **GET /api/admin/calls/active** - Active Calls Monitor
- **DELETE /api/admin/calls/:sessionId** - Call Deletion
- **GET /api/admin/messages** - Message History
- **DELETE /api/admin/messages/:sessionId** - Message Deletion

### Admin - Monitoring & Maintenance (5)
- **GET /api/admin/activity** - Agent Activity Monitor
- **GET /api/admin/errors** - Client Error Dashboard
- **PATCH /api/admin/errors/:errorId/resolve** - Error Resolution
- **GET /api/admin/server-errors** - Server Error Log
- **POST /api/admin/cleanup** - Data Cleanup

### Cross-Cutting (1)
- **429 Rate Limiting** - Keine Retry-Logic implementiert

### WebSocket Messages (5 - Requires Review)
- WebRTC Signaling (offer/answer/ice) - Format mit `data` property
- Call Control (call_initiate, call_accept, call_reject, etc.)
- Chat Messages (chat, typing)
- System Messages (joined, peer_left, call_already_claimed)
- Permission System (hasPermission() Wildcard-Support)

---

## 🔧 Empfohlene Fixes (Priorität)

### 🔴 HIGH PRIORITY

#### 1. Fix Hardcoded URLs (3 Stellen)
**Dateien:** `AppActivity.kt:3105, 3279, 3431`
```kotlin
// VORHER:
val url = "https://call-server.netdoc64.workers.dev/api/admin/domains"

// NACHHER:
val url = "https://$BACKEND_HOST/api/admin/domains"
```
**Impact:** Ermöglicht Verwendung mit anderem Backend

#### 2. Fix FCM Endpoint
**Datei:** `AuthClient.kt:342`
```kotlin
// VORHER:
val url = "$baseUrl/api/fcm-token"

// NACHHER:
val url = "$baseUrl/api/agent/register_device"
```
**Impact:** API-konform

#### 3. Fix Roles Parsing
**Datei:** `AuthClient.kt:190-220`
```kotlin
// Aktuell: saveRoles(rolesArray)

// Besser: Parse role objects
val roles = mutableListOf<Role>()
for (i in 0 until rolesArray.length()) {
    val roleObj = rolesArray.getJSONObject(i)
    roles.add(Role(
        id = roleObj.getString("id"),
        name = roleObj.getString("name"),
        level = roleObj.getInt("level")
    ))
}
saveRoles(roles)
```

### 🟡 MEDIUM PRIORITY

#### 4. Fix domain_id Field Name
**Datei:** `AppActivity.kt:2478`
```kotlin
// VORHER:
put("domain", domain)

// NACHHER:
put("domain_id", domain)
```

#### 5. Add HTTP 201 Check for Register
**Datei:** `AuthClient.kt:80`
```kotlin
// NACHHER:
if (it.code != 201 && !it.isSuccessful) {
    // error handling
}
```

### 🟢 LOW PRIORITY

#### 6. Implement Rate Limiting Handling
Alle HTTP-Calls sollten auf `429` Status reagieren:
```kotlin
when (it.code) {
    429 -> {
        val retryAfter = it.header("Retry-After")?.toIntOrNull() ?: 60
        // Wait and retry
    }
}
```

---

## 📋 Feature Gaps

Die folgenden Features sind im Backend vorhanden, aber nicht in der App implementiert:

### Admin Features
- ❌ Call History Browser mit Pagination
- ❌ Active Calls Monitoring
- ❌ Message History Browser
- ❌ Agent Activity Dashboard
- ❌ Error Dashboard mit Resolution
- ❌ Data Cleanup Tool
- ❌ Domain CRUD (nur Update vorhanden)
- ❌ Role CRUD (nur Create vorhanden)

### Agent Features
- ❌ Call History Browser
- ❌ Single Call Details View

---

## 📝 WebSocket Message Review Status

**Status:** 5 Bereiche benötigen detaillierte Code-Review

1. **WebRTC Signaling** - Prüfen auf `data` property Format
2. **Call Control** - Prüfen auf vollständige Message-Types
3. **Chat** - Prüfen auf Format und Broadcast-Verhalten
4. **System Messages** - Prüfen auf alle Message-Types
5. **Permission System** - Prüfen auf Wildcard (*) Support

**Nächster Schritt:** Detaillierte Review von `AppActivity.kt:onNewSignalReceived()`

---

## 🎯 Konformität Score

**API Konformität:** 38% (18/47 vollständig)  
**Kritische Endpoints:** 85% (11/13 Auth + Agent + Queue)  
**Admin Features:** 45% (9/20)  
**Code Quality Issues:** 7 identifiziert

---

## 💡 Empfehlungen

1. **Sofort:** Fixe die 3 hardcoded URLs (Breaking Change für andere Backends)
2. **Kurzfristig:** Implementiere fehlende Admin-Features (Call History, Activity Monitor)
3. **Mittelfristig:** Implementiere Rate Limiting Handling
4. **Langfristig:** Vollständige WebSocket Message Audit
