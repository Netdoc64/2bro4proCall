# 2bro4Call Backend API Reference

## Authentication

**Login**
```
POST /api/login
Body: { email, password }
Response: { token, user: { id, email, displayName, roles[], permissions[], allowedDomains[] } }
```

**Register**
```
POST /api/register
Body: { email, password, displayName }
Response: { message, userId }
```

## Public Endpoints

**Initiate Call (Visitor)**
```
POST /api/public/initiate_call
Body: { domain_id }
Response: { room_id, token, canonical_domain, expires_in }
Note: Creates call in "queued" status, visitor waits for agent
```

**Domain Announce**
```
POST /api/domain/announce
Body: { businessId, name }
Response: { status: "announced" }
```

## Agent Endpoints

**Initiate Outgoing Call (Agent → Visitor)**
```
POST /api/agent/initiate_call
Auth: Required (call.initiate)
Body: { visitorId, domain, message? }
Response: { 
  room_id, 
  visitor_token, 
  visitor_link: "wss://host/call/:roomId?token=...",
  expires_in: 86400
}
Note: 
- Creates call pre-assigned to agent (status: "ringing")
- Generates 24h token for visitor
- Optional "message" saved as system message (invitation)
- Agent must have access to domain
- Visitor receives link to join call
```

## Admin - Users

**Get All Data**
```
GET /api/admin/data
Auth: Required (user.read.all)
Response: { users[], domains[], roles[] }
```

**Create User**
```
POST /api/admin/create
Auth: Required (user.create)
Body: { email, password, name }
Response: { status: "created", userId }
```

**Approve User**
```
POST /api/admin/approve/:userId
Auth: Required (user.approve)
Response: { status: "approved" }
```

**Edit User**
```
PATCH /api/admin/users/:userId
Auth: Required (user.edit.team)
Body: { email?, password?, displayName?, isActive? }
Response: { status: "updated" }
```

**Delete User**
```
DELETE /api/admin/users/:userId
Auth: Required (user.delete)
Response: { status: "deleted" }
```

**Assign Domains**
```
POST /api/admin/assign-domains
Auth: Required (user.assign.domains)
Body: { targetUserId, domainIds: [] }
Response: { status: "assigned" }
Note: Parameter is 'domainIds' not 'domains'
```

**Assign Roles**
```
POST /api/admin/assign-roles
Auth: Required (role.assign)
Body: { targetUserId, roleIds: [] }
Response: { status: "assigned" }
```

## Admin - Roles

**Create Role**
```
POST /api/admin/roles
Auth: Required (role.create)
Body: { name, description, permissions: [], level }
Response: { status: "created", roleId }
```

**Update Role**
```
PATCH /api/admin/roles/:roleId
Auth: Required (role.edit)
Body: { name?, description?, permissions?: [], level? }
Response: { status: "updated" }
```

**Delete Role**
```
DELETE /api/admin/roles/:roleId
Auth: Required (role.delete)
Response: { status: "deleted" }
```

## Admin - Domains

**Add Domain**
```
POST /api/admin/domain/add
Auth: Required (domain.create)
Body: { id, name, aliases: [] }
Response: { status: "saved" }
```

**Update Domain**
```
PATCH /api/admin/domain/:domainId
Auth: Required (domain.edit)
Body: { name?, aliases?: [], is_active? }
Response: { status: "updated" }
```

**Delete Domain**
```
DELETE /api/admin/domain/:domainId
Auth: Required (domain.delete)
Response: { status: "deleted" }
```

## Admin - Calls

**Get Calls (History)**
```
GET /api/admin/calls?from=<timestamp>&to=<timestamp>&domain=<id>&agent=<id>&limit=100&offset=0
Auth: Required (call.history.read.team)
Response: { calls: [], pagination: { total, limit, offset, hasMore } }
```

**Get Active Calls**
```
GET /api/admin/calls/active
Auth: Required (call.view.all)
Response: { activeCalls: [] }
```

**Delete Call**
```
DELETE /api/admin/calls/:sessionId
Auth: Required (call.history.delete)
Response: { status: "deleted", sessionId }
```

## Admin - Queue Management (Waiting Rooms per Domain)

**Get Queues (All Domains or Filtered)**
```
GET /api/admin/queues?domain=<domainId>
Auth: Required (call.view.all)
Response: { queues: [{ 
  session_id, 
  domain_id, 
  domain_name,
  status: "queued"|"ringing",
  start_time,
  waitTime,
  visitor_token
}] }
Note: Auto-filtered by user's allowedDomains if not SuperAdmin
```

**Assign Queue to Agent**
```
POST /api/admin/queues/:sessionId/assign
Auth: Required (call.assign)
Body: { agentId }
Response: { status: "assigned" }
Note: Changes status from "queued" to "ringing"
```

**Delete Queue Entry**
```
DELETE /api/admin/queues/:sessionId
Auth: Required (call.manage)
Response: { status: "deleted" }
Note: Only works if status != "active"
```

**⚠️ Call Flow Comparison:**

**Incoming (Visitor → Agent):**
1. Visitor calls `POST /api/public/initiate_call` → Gets token
2. Visitor connects to WebSocket → Call status: "queued"
3. Agent sees call in `GET /api/admin/queues`
4. Agent connects with `mode=talk` → Automatically claims call
5. Call status changes: "queued" → "active"

**Outgoing (Agent → Visitor):**
1. Agent calls `POST /api/agent/initiate_call` → Gets visitor_token & link
2. Agent sends link to visitor (via email, SMS, chat, etc.)
3. Visitor opens link → Connects to WebSocket
4. Call status: "ringing" → "active" when visitor joins
5. Agent already pre-assigned in database

## Admin - Messages

**Get Messages**
```
GET /api/admin/messages?session=<sessionId>&limit=500
Auth: Required (call.history.read.team)
Response: { messages: [] }
```

**Delete Messages**
```
DELETE /api/admin/messages/:sessionId
Auth: Required (call.history.delete)
Response: { status: "deleted", sessionId, deletedCount }
```

## Admin - Analytics

**Get Stats Summary**
```
GET /api/admin/stats/summary
Auth: Required (analytics.read.team)
Response: {
  summary: { 
    totalCalls, 
    activeCalls, 
    callsToday, 
    totalMessages, 
    avgCallDuration,
    queuedCalls,        // NEW: Real-time queue count
    avgWaitTime,        // NEW: Average wait time in seconds
    missedToday         // NEW: Missed calls today
  },
  breakdown: { 
    byDomain: [], 
    byAgent: [], 
    last7Days: [],
    queueByDomain: []   // NEW: Queue distribution by domain
  },
  agentActivity: {      // NEW: Live agent status
    available: 0,
    busy: 0,
    break: 0,
    offline: 0
  }
}
Note: 
- Auto-filtered by user's allowedDomains if not SuperAdmin
- Agent activity based on heartbeat from last 60 seconds
- Updates every 5 seconds via frontend auto-refresh
```

## Admin - System

**Data Cleanup**
```
POST /api/admin/cleanup
Auth: Required (*)
Body: { olderThanDays, deleteCalls: true, deleteMessages: true }
Response: { status: "cleaned", deletedCalls, deletedMessages, cutoffDate }
```

## WebSocket - Call Rooms

**Connect to Call**
```
WS /call/:roomId?token=<jwt>&mode=<talk|listen|monitor>
Modes:
  - talk: Full participation (Agent/Visitor)
  - listen: Read-only (future use)
  - monitor: Silent observer (Supervisor)

Messages (Client → Server):
  → { type: "offer", data: <RTCSessionDescription> }
  → { type: "answer", data: <RTCSessionDescription> }
  → { type: "ice", data: <RTCIceCandidate> }
  → { type: "chat", text: "message" }
  → { type: "typing", isTyping: boolean }
  → { type: "call_ended", roomId: string }
  → { type: "ping" }

Messages (Server → Client):
  ← { type: "system", action: "joined", userId, role }
  ← { type: "system", action: "peer_left", userId, role }
  ← { type: "offer|answer|ice", userId, data }
  ← { type: "chat", userId, text, timestamp }
  ← { type: "typing", userId, isTyping: boolean }
  ← { type: "pong" }
```

**Room Access Control:**
- **Visitor**: Can only join room from their own token (`room_id` must match)
- **Agent**: Can join if `allowedDomains` includes domain OR is SuperAdmin
- **Monitor mode**: Agent can observe without claiming call (doesn't set `agent_id`)
- **Talk mode**: Agent claims call (sets `agent_id` in DB, status → "active")

**Direct Call Links (Agent → Visitor):**
- Visitor can join via URL: `https://yoursite.com?call=<room_id>&token=<visitor_token>`
- Widget automatically detects URL parameters and initiates connection
- Used for agent-initiated outgoing calls (from `/api/agent/initiate_call`)

**Backward Compatibility:**
- Server accepts both `{ type: "ice", data: ... }` and `{ type: "candidate", candidate: ... }`
- Server accepts both `{ type: "offer", data: ... }` and `{ type: "offer", sdp: ... }`
- Clients should use `data` property for all WebRTC messages (recommended)

## Permissions

- `*` - SuperAdmin (all access)
- `user.create`, `user.read.all`, `user.edit.team`, `user.delete`, `user.approve`, `user.assign.domains`
- `role.create`, `role.edit`, `role.delete`, `role.assign`
- `domain.create`, `domain.edit`, `domain.delete`
- `call.initiate` - **NEW: Initiate outgoing calls to visitors**
- `call.view.all`, `call.assign`, `call.manage`, `call.history.read.team`, `call.history.read.all`, `call.history.delete`
- `analytics.read.team`, `analytics.read.all`

## Permission Bundles (Assigned to Roles)

- `calls.initiate` → `[call.initiate, call.view.all]`
- `calls.handle` → `[call.view.own, call.accept, call.end.own]`
- `calls.manage` → `[call.view.all, call.assign, call.transfer, call.end.any]`
- `users.manage.all` → `[user.read.all, user.edit.all, user.create.any, user.delete, user.approve]`
- `system.admin` → `[*]`

## Rate Limits

- 100 requests/minute per IP
- 5 minute block after exceeding limit

## Error Responses

```json
{ "error": "message", "status": 400|401|403|404|409|429|500 }
```
