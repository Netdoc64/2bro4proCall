# 2bro4Call Backend API Documentation v2.2

## 📍 Base URL
```
Production: https://call-server.netdoc64.workers.dev
Local: http://localhost:8787
```

## 🔐 Authentication
All admin endpoints require JWT Bearer Token:
```
Authorization: Bearer <your-jwt-token>
```

---

## 🟢 Public Endpoints

### 1. Login
```http
POST /api/login
```
**Body:**
```json
{
  "email": "admin@example.com",
  "password": "password123"
}
```
**Response:**
```json
{
  "token": "eyJhbGc...",
  "user": {
    "id": "uuid",
    "email": "admin@example.com",
    "roles": [{"id": "role_superadmin", "name": "SuperAdmin"}],
    "permissions": ["*"]
  }
}
```

### 2. Register
```http
POST /api/register
```
**Body:**
```json
{
  "email": "user@example.com",
  "password": "password123",
  "displayName": "John Doe"
}
```

### 3. Initiate Call
```http
POST /api/public/initiate_call
```
**Body:**
```json
{
  "domain_id": "example.com"
}
```

### 4. Domain Announce
```http
POST /api/domain/announce
```
**Body:**
```json
{
  "businessId": "example.com",
  "name": "My Business"
}
```

---

## 🟡 Agent Endpoints

### Outgoing Calls

#### Initiate Call to Visitor (Agent → Visitor)
```http
POST /api/agent/initiate_call
```
**Permission:** `call.initiate`
**Body:**
```json
{
  "visitorId": "visitor-identifier",
  "domain": "example.com",
  "message": "Optional invitation message"
}
```
**Response:**
```json
{
  "room_id": "example.com__uuid",
  "visitor_token": "jwt-token-for-visitor",
  "visitor_link": "wss://host/call/room_id?token=jwt",
  "expires_in": 86400
}
```

**Notes:**
- Agent must have access to the specified domain
- Creates call pre-assigned to agent (status: "ringing")
- Generates 24-hour token for visitor
- Optional message saved as system message
- Agent sends `visitor_link` to visitor via external channel
- Call activates when visitor joins via link

---

## 🔴 Admin Endpoints

### Data & Overview

#### Get All Data (Users, Domains, Roles)
```http
GET /api/admin/data
```
**Response:**
```json
{
  "users": [...],
  "domains": [...],
  "roles": [...]
}
```

#### Get Statistics Summary
```http
GET /api/admin/stats/summary
```
**Permission:** `analytics.read.team` or `analytics.read.all`
**Response:**
```json
{
  "summary": {
    "totalCalls": 1523,
    "activeCalls": 5,
    "callsToday": 47,
    "totalMessages": 3421,
    "avgCallDuration": 245,
    "queuedCalls": 3,
    "avgWaitTime": 67,
    "missedToday": 2
  },
  "breakdown": {
    "byDomain": [
      { "domain_id": "example.com", "count": 450 }
    ],
    "byAgent": [
      { "agent_id": "agent-uuid", "count": 125 }
    ],
    "last7Days": [
      { "day": "Mo", "count": 45 }
    ],
    "queueByDomain": [
      { "domain_id": "example.com", "count": 2 }
    ]
  },
  "agentActivity": {
    "available": 8,
    "busy": 5,
    "break": 2,
    "offline": 1
  }
}
```

**Notes:**
- `queuedCalls`: Real-time count of calls waiting (status: queued/ringing)
- `avgWaitTime`: Average time calls wait before agent pickup (in seconds)
- `missedToday`: Calls with status "missed" from today
- `queueByDomain`: Current queue distribution across domains
- `agentActivity`: Live agent status (updated from last 60 seconds heartbeat)
- Auto-filtered by user's `allowedDomains` if not SuperAdmin
- Updates every 5 seconds via auto-refresh in frontend

---

### 👥 User Management

#### Create User (NEW)
```http
POST /api/admin/create
```
**Permission:** `user.create`
**Body:**
```json
{
  "email": "newuser@example.com",
  "password": "securePassword123",
  "name": "John Doe"
}
```
**Response:**
```json
{
  "status": "created",
  "userId": "user-uuid-here"
}
```

#### Approve User
```http
POST /api/admin/approve/{userId}
```
**Permission:** `user.approve`

#### Edit User
```http
PATCH /api/admin/users/{userId}
```
**Permission:** `user.edit.team` or `user.edit.all`
**Body:**
```json
{
  "email": "newemail@example.com",
  "password": "newpassword",
  "displayName": "New Name",
  "isActive": true
}
```
**Response:**
```json
{
  "status": "updated"
}
```

#### Delete User
```http
DELETE /api/admin/users/{userId}
```
**Permission:** `user.delete`
**Response:**
```json
{
  "status": "deleted"
}
```

#### Assign Domains to User
```http
POST /api/admin/assign-domains
```
**Permission:** `user.assign.domains`
**Body:**
```json
{
  "targetUserId": "user-uuid",
  "domainIds": ["example.com", "shop.example.com"]
}
```
**Response:**
```json
{
  "status": "assigned"
}
```

**Notes:**
- Parameter is `domainIds` (not `domains`)
- Replaces all existing domain assignments
- Empty array removes all domains

#### Assign Roles to User
```http
POST /api/admin/assign-roles
```
**Permission:** `role.assign`
**Body:**
```json
{
  "targetUserId": "user-uuid",
  "roleIds": ["role_agent", "role_supervisor"]
}
```

---

### 🛡️ Role Management (NEW)

#### Create Role
```http
POST /api/admin/roles
```
**Permission:** `role.create`
**Body:**
```json
{
  "name": "Support Agent",
  "description": "Customer support role",
  "permissions": ["calls.handle", "calls.history.own"],
  "level": 150
}
```
**Response:**
```json
{
  "status": "created",
  "roleId": "role_support_agent"
}
```

#### Update Role
```http
PATCH /api/admin/roles/{roleId}
```
**Permission:** `role.edit`
**Body:**
```json
{
  "name": "Updated Name",
  "description": "Updated description",
  "permissions": ["calls.handle", "users.view.team"],
  "level": 200
}
```

#### Delete Role
```http
DELETE /api/admin/roles/{roleId}
```
**Permission:** `role.delete`
**Response:**
```json
{
  "status": "deleted"
}
```

---

### 🌐 Domain Management

#### Add Domain
```http
POST /api/admin/domain/add
```
**Permission:** `domain.create`
**Body:**
```json
{
  "id": "example.com",
  "name": "Example Company",
  "aliases": ["www.example.com", "shop.example.com"]
}
```

#### Update Domain (NEW)
```http
PATCH /api/admin/domain/{domainId}
```
**Permission:** `domain.edit`
**Body:**
```json
{
  "name": "Updated Name",
  "aliases": ["www.example.com"],
  "is_active": true
}
```

#### Delete Domain
```http
DELETE /api/admin/domain/{domainId}
```
**Permission:** `domain.delete`

---

### 📞 Call Management

#### Get Calls History
```http
GET /api/admin/calls?domain=example.com&status=active&from=0&to=1700000000000&limit=100&offset=0
```
**Permission:** `call.history.read.team` or `call.history.read.all`

**Query Parameters:**
- `domain` (optional): Filter by domain
- `status` (optional): `queued`, `ringing`, `active`, `ended`, `missed`
- `from` (optional): Start timestamp
- `to` (optional): End timestamp
- `limit` (optional): Max results (default: 100)
- `offset` (optional): Pagination offset

#### Get Active Calls
```http
GET /api/admin/calls/active
```
**Permission:** `call.view.all`

#### Delete Call
```http
DELETE /api/admin/calls/{sessionId}
```
**Permission:** `call.history.delete`

---

### 📋 Queue Management (NEW)

#### Get Call Queues
```http
GET /api/admin/queues
```
**Permission:** `call.view.all`
**Response:**
```json
{
  "queues": [
    {
      "session_id": "example.com__uuid",
      "domain_id": "example.com",
      "domain_name": "Example Company",
      "status": "queued",
      "start_time": 1700000000000,
      "waitTime": 45000
    }
  ]
}
```

#### Assign Queue to Agent
```http
POST /api/admin/queues/{sessionId}/assign
```
**Permission:** `call.assign`
**Body:**
```json
{
  "agentId": "agent-user-uuid"
}
```

#### Delete Queue Entry
```http
DELETE /api/admin/queues/{sessionId}
```
**Permission:** `call.manage`

---

### 💬 Messages

#### Get Messages
```http
GET /api/admin/messages?session={sessionId}&limit=500
```
**Permission:** `call.history.read.team` or `call.history.read.all`

#### Delete Messages
```http
DELETE /api/admin/messages/{sessionId}
```
**Permission:** `call.history.delete`

---

### 🧹 System Management

#### Cleanup Old Data
```http
POST /api/admin/cleanup
```
**Permission:** `*` (SuperAdmin only)
**Body:**
```json
{
  "olderThanDays": 30,
  "deleteCalls": true,
  "deleteMessages": true
}
```

---

## 🔒 Permission Bundles

### Calls
- `calls.handle` - Calls annehmen
- `calls.monitor` - Calls mithören
- `calls.manage` - Calls verwalten
- `calls.history.own` - Eigene Historie
- `calls.history.team` - Team Historie
- `calls.history.all` - Alle Historien

### Users
- `users.view.own` - Eigenes Profil
- `users.view.team` - Team Profile
- `users.view.all` - Alle User
- `users.manage.subordinates` - Untergeordnete verwalten
- `users.manage.all` - Alle User verwalten

### Roles
- `roles.view` - Rollen ansehen
- `roles.manage` - Rollen verwalten (CRUD)

### Domains
- `domains.view` - Domains ansehen
- `domains.manage` - Domains verwalten (CRUD)

### Analytics
- `analytics.view.own` - Eigene Stats
- `analytics.view.team` - Team Stats
- `analytics.view.all` - Alle Stats

### System
- `system.admin` - Vollzugriff (SuperAdmin)

---

## 📊 HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 201 | Created |
| 400 | Bad Request (Validation Error) |
| 401 | Unauthorized (Missing/Invalid Token) |
| 403 | Forbidden (Insufficient Permissions) |
| 404 | Not Found |
| 409 | Conflict (Already Exists) |
| 429 | Too Many Requests (Rate Limited) |
| 500 | Internal Server Error |

---

## 🔄 WebSocket (Durable Objects)

### Connect to Call Room
```
ws://localhost:8787/call/{roomId}?token={jwt}&mode=talk
```

**Modes:**
- `talk` - Active participant (Agent/Visitor)
- `monitor` - Listen-only (Supervisor)

**Message Format:**
```json
{
  "type": "offer|answer|candidate|chat|ping",
  "sdp": "...",
  "candidate": {...},
  "text": "message"
}
```

---

## 🚀 Rate Limiting

- **Limit:** 100 requests per minute per IP
- **Block Duration:** 5 minutes on exceed
- **Max Request Size:** 5MB

---

## 📝 Notes

1. All timestamps are in Unix milliseconds
2. All dates in ISO 8601 format
3. JWT tokens expire after 8 hours
4. CORS is dynamically loaded from registered domains
5. SQL injection protection via prepared statements
6. All inputs are validated and sanitized

---

## 🆕 Changes in v2.2

✅ **NEW:** Agent Initiate Call endpoint (outgoing calls)
✅ **NEW:** User Create endpoint
✅ **NEW:** Extended Analytics with Queue metrics
✅ **NEW:** Agent Activity tracking (live status)
✅ **NEW:** Queue by Domain breakdown
✅ **IMPROVED:** Stats endpoint returns 8 summary metrics instead of 5
✅ **IMPROVED:** Auto-refresh every 5 seconds for live data
✅ **FIXED:** Correct endpoint paths (/api/admin/users/ not /user/)
✅ **FIXED:** Correct parameter names (domainIds not domains)

---

## 🆕 Changes in v2.1

✅ **NEW:** Role Management CRUD
✅ **NEW:** Domain PATCH endpoint
✅ **NEW:** Queue Management (GET, Assign, Delete)
✅ **IMPROVED:** Better error messages
✅ **IMPROVED:** Consistent CORS headers
✅ **IMPROVED:** Permission checks

---

## 📞 Support

For issues or questions, check the logs:
```bash
npx wrangler tail
```
