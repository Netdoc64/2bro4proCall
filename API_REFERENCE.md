# 2bro4Call Server API Reference

**Version:** 2.0  
**Base URL:** `https://call-server.netdoc64.workers.dev`

---

## Table of Contents

1. [Authentication](#authentication)
2. [Public API](#public-api)
3. [Agent API](#agent-api)
4. [Admin API](#admin-api)
5. [WebSocket API](#websocket-api)
6. [Error Codes](#error-codes)
7. [Data Models](#data-models)

---

## Authentication

All authenticated endpoints require a Bearer token in the Authorization header:

```
Authorization: Bearer <JWT_TOKEN>
```

### Token Types

- **User Token**: Standard user/agent token (from `/api/login`)
- **Visitor Token**: Time-limited token for visitors (from `/api/public/initiate_room`)

### Permissions System

Permissions follow a hierarchical wildcard pattern:
- `*` = SuperAdmin (all permissions)
- `user.read.all` = Read all users
- `user.read.team` = Read team users
- `call.view.own` = View own calls only

---

## Public API

### POST `/api/login`

Authenticate user and receive JWT token.

**Request:**
```json
{
  "email": "agent@example.com",
  "password": "password123"
}
```

**Response (200):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "user": {
    "id": "uuid",
    "email": "agent@example.com",
    "displayName": "John Doe",
    "roles": [
      {
        "id": "role_agent",
        "name": "Agent",
        "level": 200
      }
    ],
    "permissions": ["call.view.own", "call.initiate"],
    "allowedDomains": ["example.com", "test.com"]
  }
}
```

**Errors:**
- `400` - Invalid email format or password too short
- `401` - Invalid credentials
- `403` - Account pending approval

**Rate Limit:** 100 req/min per IP

---

### POST `/api/register`

Register new user account (requires admin approval).

**Request:**
```json
{
  "email": "newagent@example.com",
  "password": "SecurePass123",
  "displayName": "Jane Smith"
}
```

**Response (201):**
```json
{
  "message": "Registration successful. Awaiting approval.",
  "userId": "uuid"
}
```

**Errors:**
- `400` - Invalid email or password < 8 chars
- `409` - User already exists

**Rate Limit:** 100 req/min per IP

---

### POST `/api/domain/announce`

Register or update domain presence.

**Request:**
```json
{
  "businessId": "example.com",
  "name": "Example Business"
}
```

**Response (200):**
```json
{
  "status": "announced",
  "canonical": "example.com"
}
```

**Response (200) - Alias:**
```json
{
  "status": "announced",
  "canonical": "main-domain.com",
  "note": "Alias of main-domain.com"
}
```

**Notes:**
- Domain IDs are automatically normalized (lowercase, umlauts → ae/oe/ue)
- Creates domain if not exists, updates `last_seen` if exists
- Supports alias resolution

---

### POST `/api/public/initiate_room`

Create visitor call session.

**Request:**
```json
{
  "domain_id": "example.com"
}
```

**Response (200):**
```json
{
  "room_id": "example.com__uuid",
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "canonical_domain": "example.com",
  "expires_in": 3600
}
```

**Errors:**
- `400` - Invalid domain_id format
- `403` - Domain not registered

**Rate Limit:** 100 req/min per IP

**Notes:**
- Automatically notifies agents with domain access
- Creates call record with status `queued`
- Token valid for 1 hour

---

### POST `/api/errors/report`

Report client-side error (public, no auth required).

**Request:**
```json
{
  "app_version": "1.0.0",
  "platform": "web",
  "device_info": {
    "browser": "Chrome",
    "os": "Windows 10"
  },
  "error_type": "network",
  "error_message": "Connection timeout",
  "stack_trace": "Error: timeout\n  at fetch...",
  "context": {
    "action": "initiate_call",
    "domain": "example.com"
  },
  "severity": "error",
  "request_id": "uuid",
  "http_status": 500,
  "endpoint": "/api/agent/calls"
}
```

**Parameters:**
- `app_version` (required): App version string
- `platform` (required): `ios` | `android` | `web` | `desktop`
- `error_type` (required): `crash` | `network` | `webrtc` | `permission` | `ui` | `other`
- `error_message` (required): Error description (max 2000 chars)
- `stack_trace` (optional): Stack trace (max 10000 chars)
- `severity` (required): `fatal` | `error` | `warning` | `info`
- `request_id` (optional): Correlate with server error

**Response (201):**
```json
{
  "status": "reported",
  "reportId": "uuid"
}
```

**Rate Limit:** 100 req/min per IP

---

## Agent API

All Agent API endpoints require authentication with agent permissions.

### POST `/api/agent/initiate_call`

Agent initiates outgoing call to visitor.

**Auth:** Required  
**Permission:** `call.initiate`

**Request:**
```json
{
  "visitorId": "visitor-123",
  "domain": "example.com",
  "message": "Hello, how can I help?"
}
```

**Response (200):**
```json
{
  "room_id": "example.com__uuid",
  "visitor_token": "eyJhbGciOiJIUzI1NiIs...",
  "visitor_link": "wss://worker.dev/call/example.com__uuid?token=...",
  "expires_in": 86400
}
```

**Errors:**
- `400` - Missing visitorId or invalid domain format
- `403` - No access to domain
- `404` - Domain not found

**Notes:**
- Call is pre-assigned to initiating agent
- Status starts as `ringing`
- Token valid for 24 hours

---

### GET `/api/agent/queues`

Get all queued/ringing calls for agent's domains.

**Auth:** Required  
**Permission:** `call.view.own`

**Response (200):**
```json
{
  "queues": [
    {
      "session_id": "example.com__uuid",
      "domain_id": "example.com",
      "domain_name": "Example Business",
      "status": "queued",
      "start_time": 1704067200000,
      "visitor_token": "192.168.1.1",
      "agent_id": null,
      "queue_time": null
    }
  ],
  "total": 1
}
```

**Notes:**
- Only shows calls for domains agent has access to
- SuperAdmin (`*` permission) sees all queues
- Status filter: `queued`, `ringing`

---

### GET `/api/agent/calls`

Get agent's call history with pagination.

**Auth:** Required  
**Permission:** `call.view.own`

**Query Parameters:**
- `status` (optional): `active` | `completed` | `all` (default: `all`)
- `limit` (optional): Max results, 1-100 (default: 50)
- `offset` (optional): Pagination offset (default: 0)

**Response (200):**
```json
{
  "calls": [
    {
      "id": 123,
      "session_id": "example.com__uuid",
      "domain_id": "example.com",
      "domain_name": "Example Business",
      "agent_id": "agent-uuid",
      "status": "completed",
      "start_time": 1704067200000,
      "end_time": 1704067500000,
      "queue_time": 1704067210000,
      "visitor_token": "192.168.1.1"
    }
  ],
  "total": 42,
  "limit": 50,
  "offset": 0
}
```

**Notes:**
- Shows calls where agent is assigned OR agent has domain access
- Ordered by `start_time DESC`

---

### GET `/api/agent/calls/:callId`

Get detailed call information including messages.

**Auth:** Required  
**Permission:** `call.view.own`

**Response (200):**
```json
{
  "call": {
    "id": 123,
    "session_id": "example.com__uuid",
    "domain_id": "example.com",
    "domain_name": "Example Business",
    "agent_id": "agent-uuid",
    "status": "completed",
    "start_time": 1704067200000,
    "end_time": 1704067500000,
    "queue_time": 1704067210000
  },
  "messages": [
    {
      "id": "msg-uuid",
      "session_id": "example.com__uuid",
      "sender_id": "agent-uuid",
      "sender_type": "agent",
      "timestamp": 1704067220000,
      "content": "Hello, how can I help?"
    }
  ]
}
```

**Errors:**
- `403` - No access to this call
- `404` - Call not found

---

### POST `/api/agent/register_device`

Register device for push notifications.

**Auth:** Required  
**Permission:** `agent.api`

**Request:**
```json
{
  "fcm_token": "FCM_TOKEN_STRING",
  "device_type": "android",
  "device_name": "Samsung Galaxy S21",
  "app_version": "1.0.0"
}
```

**Parameters:**
- `fcm_token` (required): Firebase Cloud Messaging token
- `device_type` (optional): `ios` | `android` | `web`
- `device_name` (optional): Device display name
- `app_version` (optional): App version

**Response (201):**
```json
{
  "message": "Device registered successfully",
  "device_id": "uuid"
}
```

**Response (200) - Update:**
```json
{
  "message": "Device updated",
  "device_id": "uuid"
}
```

**Notes:**
- Updates `last_active` if device already exists
- Push notifications sent to offline agents only

---

### WebSocket `/api/agent/notifications`

Real-time notifications for agents (online agents only).

**Auth:** Required (token in query param)  
**URL:** `wss://worker.dev/api/agent/notifications?token=<JWT_TOKEN>`

**Connection Message:**
```json
{
  "type": "connected",
  "userId": "agent-uuid",
  "domains": ["example.com"]
}
```

**Notification Types:**

**1. Visitor Waiting:**
```json
{
  "type": "visitor_waiting",
  "room_id": "example.com__uuid",
  "domain_id": "example.com",
  "timestamp": 1704067200000,
  "status": "queued"
}
```

**2. Incoming Call:**
```json
{
  "type": "incoming_call",
  "room_id": "example.com__uuid",
  "domain_id": "example.com",
  "from": "visitor",
  "caller_id": "visitor_uuid",
  "timestamp": 1704067200000
}
```

**3. Call Claimed:**
```json
{
  "type": "call_claimed",
  "room_id": "example.com__uuid",
  "domain_id": "example.com",
  "by_agent_id": "other-agent-uuid",
  "timestamp": 1704067200000
}
```

**4. Call Ended:**
```json
{
  "type": "call_ended",
  "room_id": "example.com__uuid",
  "domain_id": "example.com",
  "reason": "completed",
  "timestamp": 1704067200000
}
```

**Client Actions:**
```json
{
  "type": "ping"
}
```

**Server Response:**
```json
{
  "type": "pong"
}
```

**Notes:**
- Heartbeat every 30s to detect dead connections
- Only receives notifications for domains with access
- SuperAdmin receives all notifications

---

## Admin API

All Admin API endpoints require elevated permissions.

### GET `/api/admin/data`

Get all system data (users, domains, roles).

**Auth:** Required  
**Permission:** `user.read.all`

**Response (200):**
```json
{
  "users": [
    {
      "id": "uuid",
      "email": "agent@example.com",
      "displayName": "John Doe",
      "approved": true,
      "isActive": true,
      "roles": [
        {
          "id": "role_agent",
          "name": "Agent",
          "level": 200
        }
      ],
      "allowedDomains": ["example.com"],
      "lastLogin": 1704067200000,
      "createdAt": 1704000000000
    }
  ],
  "domains": [
    {
      "id": "example.com",
      "name": "Example Business",
      "aliases": ["ex.com", "example.net"],
      "manual": true,
      "isActive": true,
      "lastSeen": 1704067200000,
      "createdAt": 1704000000000
    }
  ],
  "roles": [
    {
      "id": "role_agent",
      "name": "Agent",
      "level": 200,
      "permissions": ["call.view.own", "call.initiate"],
      "canManageRoles": [],
      "description": "Standard agent role",
      "isSystem": true
    }
  ]
}
```

---

### POST `/api/admin/create`

Create new user (admin-initiated, auto-approved).

**Auth:** Required  
**Permission:** `user.create`

**Request:**
```json
{
  "email": "newuser@example.com",
  "password": "SecurePass123",
  "name": "New User"
}
```

**Response (201):**
```json
{
  "status": "created",
  "userId": "uuid"
}
```

**Errors:**
- `400` - Invalid email or password < 8 chars
- `409` - Email already exists

---

### POST `/api/admin/approve/:userId`

Approve pending user registration.

**Auth:** Required  
**Permission:** `user.approve`

**Response (200):**
```json
{
  "status": "approved"
}
```

**Errors:**
- `404` - User not found

---

### PATCH `/api/admin/users/:userId`

Edit user details.

**Auth:** Required  
**Permission:** `user.edit.team`

**Request:**
```json
{
  "email": "updated@example.com",
  "password": "NewPassword123",
  "displayName": "Updated Name",
  "isActive": true,
  "approved": true
}
```

**Response (200):**
```json
{
  "status": "updated"
}
```

**Errors:**
- `400` - No valid updates provided
- `403` - Cannot edit this user (hierarchy)
- `404` - User not found

**Notes:**
- All fields optional
- Password must be ≥8 chars if provided
- Hierarchy rules enforced (can't edit superiors)

---

### DELETE `/api/admin/users/:userId`

Delete user account.

**Auth:** Required  
**Permission:** `user.delete`

**Response (200):**
```json
{
  "status": "deleted"
}
```

**Errors:**
- `403` - Cannot delete this user
- `404` - User not found

---

### POST `/api/admin/assign-domains`

Assign domains to user.

**Auth:** Required  
**Permission:** `user.assign.domains`

**Request:**
```json
{
  "userId": "target-user-uuid",
  "domains": ["example.com", "test.com"]
}
```

**Alternate:**
```json
{
  "targetUserId": "target-user-uuid",
  "domainIds": ["example.com", "test.com"]
}
```

**Response (200):**
```json
{
  "status": "assigned"
}
```

**Errors:**
- `400` - Missing userId or domains not array
- `403` - Cannot manage this user

**Notes:**
- Replaces all existing domain assignments
- Domain IDs are normalized before assignment

---

### POST `/api/admin/assign-roles`

Assign roles to user.

**Auth:** Required  
**Permission:** `role.assign`

**Request:**
```json
{
  "userId": "target-user-uuid",
  "roleIds": ["role_agent", "role_teamlead"]
}
```

**Alternate:**
```json
{
  "targetUserId": "target-user-uuid",
  "roleIds": ["role_agent"]
}
```

**Response (200):**
```json
{
  "status": "assigned"
}
```

**Errors:**
- `400` - Missing userId or roleIds
- `403` - Cannot manage this user

**Notes:**
- Replaces all existing role assignments
- SuperAdmin role is automatically preserved if user had it

---

### POST `/api/admin/roles`

Create new custom role.

**Auth:** Required  
**Permission:** `role.create`

**Request:**
```json
{
  "name": "Customer Support",
  "description": "Support team members",
  "permissions": ["call.view.own", "call.initiate", "call.history.read.team"],
  "level": 150
}
```

**Response (201):**
```json
{
  "status": "created",
  "roleId": "role_customer_support"
}
```

**Errors:**
- `400` - Missing name or permissions
- `409` - Role ID already exists

**Notes:**
- Role ID auto-generated from name
- Level defaults to 100 if not specified

---

### PATCH `/api/admin/roles/:roleId`

Update existing role.

**Auth:** Required  
**Permission:** `role.edit`

**Request:**
```json
{
  "name": "Updated Role Name",
  "description": "Updated description",
  "permissions": ["new.permission"],
  "level": 160
}
```

**Response (200):**
```json
{
  "status": "updated"
}
```

**Errors:**
- `400` - No valid updates provided
- `403` - Cannot modify system roles
- `404` - Role not found

---

### DELETE `/api/admin/roles/:roleId`

Delete custom role.

**Auth:** Required  
**Permission:** `role.delete`

**Response (200):**
```json
{
  "status": "deleted"
}
```

**Errors:**
- `403` - Cannot delete system roles
- `404` - Role not found
- `409` - Role assigned to users

---

### POST `/api/admin/domain/add`

Add or update domain.

**Auth:** Required  
**Permission:** `domain.create`

**Request:**
```json
{
  "id": "newdomain.com",
  "name": "New Domain Business",
  "aliases": ["new.com", "nd.com"]
}
```

**Response (200):**
```json
{
  "status": "saved",
  "canonical": "newdomain.com"
}
```

**Errors:**
- `400` - Invalid domain ID format

**Notes:**
- Domain ID and aliases are normalized
- Upserts: updates if exists, creates if new
- Marked as `manual = 1`

---

### PATCH `/api/admin/domain/:domainId`

Update domain details.

**Auth:** Required  
**Permission:** `domain.edit`

**Request:**
```json
{
  "name": "Updated Business Name",
  "aliases": ["alias1.com", "alias2.com"],
  "is_active": true
}
```

**Response (200):**
```json
{
  "status": "updated"
}
```

**Errors:**
- `400` - No valid updates or invalid domain ID
- `404` - Domain not found

---

### DELETE `/api/admin/domain/:domainId`

Delete domain.

**Auth:** Required  
**Permission:** `domain.delete`

**Response (200):**
```json
{
  "status": "deleted"
}
```

**Errors:**
- `400` - Invalid domain ID
- `404` - Domain not found

---

### GET `/api/admin/calls`

Get call history with advanced filtering.

**Auth:** Required  
**Permission:** `call.history.read.team`

**Query Parameters:**
- `from` (optional): Start timestamp (default: 0)
- `to` (optional): End timestamp (default: now)
- `domain` (optional): Filter by domain (supports aliases)
- `agent` (optional): Filter by agent ID
- `limit` (optional): Max results, 1-500 (default: 100)
- `offset` (optional): Pagination offset (default: 0)

**Response (200):**
```json
{
  "calls": [
    {
      "id": 123,
      "session_id": "example.com__uuid",
      "domain_id": "example.com",
      "agent_id": "agent-uuid",
      "status": "completed",
      "start_time": 1704067200000,
      "end_time": 1704067500000,
      "queue_time": 1704067210000,
      "visitor_token": "192.168.1.1",
      "duration": 300000,
      "active": false
    }
  ],
  "pagination": {
    "total": 1523,
    "limit": 100,
    "offset": 0,
    "hasMore": true
  }
}
```

**Notes:**
- Excludes calls with status `queued`
- Permission `call.history.read.all` sees all domains
- Domain filter uses canonical domain (resolves aliases)

---

### GET `/api/admin/calls/active`

Get currently active calls.

**Auth:** Required  
**Permission:** `call.view.all`

**Response (200):**
```json
{
  "activeCalls": [
    {
      "id": 123,
      "session_id": "example.com__uuid",
      "domain_id": "example.com",
      "agent_id": "agent-uuid",
      "status": "active",
      "start_time": 1704067200000,
      "duration": 45000
    }
  ]
}
```

---

### DELETE `/api/admin/calls/:sessionId`

Delete call record and associated messages.

**Auth:** Required  
**Permission:** `call.history.delete`

**Response (200):**
```json
{
  "status": "deleted",
  "sessionId": "example.com__uuid"
}
```

**Errors:**
- `404` - Call not found

**Notes:**
- Cascades: deletes messages first, then call

---

### GET `/api/admin/messages`

Get message history.

**Auth:** Required  
**Permission:** `call.history.read.team`

**Query Parameters:**
- `session` (optional): Filter by session ID
- `limit` (optional): Max results, 1-1000 (default: 500)

**Response (200):**
```json
{
  "messages": [
    {
      "id": "msg-uuid",
      "session_id": "example.com__uuid",
      "sender_id": "agent-uuid",
      "sender_type": "agent",
      "timestamp": 1704067220000,
      "content": "Hello, how can I help?",
      "metadata": null
    }
  ]
}
```

**Notes:**
- Ordered by `timestamp DESC`
- Filtered by accessible domains if not SuperAdmin

---

### DELETE `/api/admin/messages/:sessionId`

Delete all messages for a session.

**Auth:** Required  
**Permission:** `call.history.delete`

**Response (200):**
```json
{
  "status": "deleted",
  "sessionId": "example.com__uuid",
  "deletedCount": 15
}
```

---

### GET `/api/admin/stats/summary`

Get system statistics and metrics.

**Auth:** Required  
**Permission:** `analytics.read.team`

**Response (200):**
```json
{
  "summary": {
    "totalCalls": 1523,
    "activeCalls": 3,
    "callsToday": 42,
    "totalMessages": 8456,
    "avgCallDuration": 245,
    "queuedCalls": 2,
    "avgWaitTime": 15,
    "missedToday": 5
  },
  "breakdown": {
    "byDomain": [
      { "domain_id": "example.com", "count": 523 }
    ],
    "byAgent": [
      { "agent_id": "agent-uuid", "count": 156 }
    ],
    "last7Days": [
      { "day": "Mon", "count": 45 },
      { "day": "Tue", "count": 52 }
    ],
    "queueByDomain": [
      { "domain_id": "example.com", "count": 2 }
    ]
  },
  "agentActivity": {
    "available": 5,
    "busy": 3,
    "break": 1,
    "offline": 2
  }
}
```

**Notes:**
- All times in seconds (avgCallDuration, avgWaitTime)
- Filtered by accessible domains if not SuperAdmin
- Agent activity based on last heartbeat (60s window)

---

### GET `/api/admin/queues`

Get all queued/ringing calls.

**Auth:** Required  
**Permission:** `call.view.all`

**Response (200):**
```json
{
  "queues": [
    {
      "id": 123,
      "session_id": "example.com__uuid",
      "domain_id": "example.com",
      "domain_name": "Example Business",
      "status": "queued",
      "start_time": 1704067200000,
      "agent_id": null,
      "waitTime": 45000
    }
  ]
}
```

**Notes:**
- Ordered by `start_time ASC` (oldest first)
- `waitTime` calculated as `now - start_time`

---

### POST `/api/admin/queues/:sessionId/assign`

Manually assign queued call to agent.

**Auth:** Required  
**Permission:** `call.assign`

**Request:**
```json
{
  "agentId": "agent-uuid"
}
```

**Response (200):**
```json
{
  "status": "assigned"
}
```

**Errors:**
- `400` - Missing agentId
- `404` - Call not found
- `409` - Call already active

---

### DELETE `/api/admin/queues/:sessionId`

Remove call from queue (not active calls).

**Auth:** Required  
**Permission:** `call.manage`

**Response (200):**
```json
{
  "status": "deleted"
}
```

**Errors:**
- `404` - Queue entry not found or call is active

---

### GET `/api/admin/activity`

Get user activity logs.

**Auth:** Required  
**Permission:** `analytics.read.all`

**Query Parameters:**
- `action` (optional): Filter by action type
- `user` (optional): Filter by user ID
- `type` (optional): Filter by target type (`user`, `call`, `role`, `domain`, `system`)
- `from` (optional): Start timestamp (default: 0)
- `to` (optional): End timestamp (default: now)
- `limit` (optional): Max results, 1-500 (default: 100)
- `offset` (optional): Pagination offset (default: 0)

**Response (200):**
```json
{
  "logs": [
    {
      "id": 1,
      "user_id": "admin-uuid",
      "user_email": "admin@example.com",
      "user_name": "Admin User",
      "action": "user_created",
      "target_type": "user",
      "target_id": "new-user-uuid",
      "ip": "192.168.1.1",
      "timestamp": 1704067200000,
      "metadata": {
        "email": "newuser@example.com",
        "name": "New User"
      }
    }
  ],
  "pagination": {
    "total": 1523,
    "limit": 100,
    "offset": 0,
    "hasMore": true
  }
}
```

**Common Action Types:**
- `login_success`, `login_failed`
- `user_created`, `user_updated`, `user_deleted`, `user_approved`
- `role_created`, `role_updated`, `role_deleted`, `roles_assigned`
- `domain_added`, `domain_updated`, `domain_deleted`, `domains_assigned`
- `call_assigned`, `call_deleted`, `messages_deleted`
- `data_cleanup`, `error_resolved`

---

### GET `/api/admin/errors`

Get client error reports.

**Auth:** Required  
**Permission:** `analytics.read.all`

**Query Parameters:**
- `type` (optional): Filter by error_type
- `severity` (optional): Filter by severity
- `platform` (optional): Filter by platform
- `resolved` (optional): Filter by resolved status (`true`/`false`)
- `user` (optional): Filter by user ID
- `from` (optional): Start timestamp (default: 0)
- `to` (optional): End timestamp (default: now)
- `limit` (optional): Max results, 1-500 (default: 100)
- `offset` (optional): Pagination offset (default: 0)

**Response (200):**
```json
{
  "errors": [
    {
      "id": "uuid",
      "user_id": "user-uuid",
      "display_name": "John Doe",
      "email": "user@example.com",
      "app_version": "1.0.0",
      "platform": "web",
      "device_info": {
        "browser": "Chrome",
        "os": "Windows 10"
      },
      "error_type": "network",
      "error_message": "Connection timeout",
      "stack_trace": "Error: timeout...",
      "context": {
        "action": "initiate_call"
      },
      "severity": "error",
      "timestamp": 1704067200000,
      "resolved": false,
      "resolved_by": null,
      "resolved_by_name": null,
      "resolved_at": null,
      "notes": null,
      "request_id": "req-uuid",
      "http_status": 500,
      "endpoint": "/api/agent/calls",
      "server_error_id": "srv-uuid",
      "server_endpoint": "/api/agent/calls",
      "server_method": "GET",
      "server_status": 500,
      "server_error_message": "Database timeout"
    }
  ],
  "pagination": {
    "total": 234,
    "limit": 100,
    "offset": 0,
    "hasMore": true
  }
}
```

**Notes:**
- Joins with `server_errors` table via `request_id`
- Shows correlation between client and server errors

---

### PATCH `/api/admin/errors/:errorId/resolve`

Mark error report as resolved.

**Auth:** Required  
**Permission:** `system.admin`

**Request:**
```json
{
  "notes": "Fixed in version 1.0.1"
}
```

**Response (200):**
```json
{
  "status": "resolved"
}
```

---

### GET `/api/admin/server-errors`

Get server-side error logs.

**Auth:** Required  
**Permission:** `analytics.read.all`

**Query Parameters:**
- `status` (optional): Filter by HTTP status code
- `endpoint` (optional): Filter by endpoint (partial match)
- `user` (optional): Filter by user ID
- `from` (optional): Start timestamp (default: 0)
- `to` (optional): End timestamp (default: now)
- `limit` (optional): Max results, 1-500 (default: 100)
- `offset` (optional): Pagination offset (default: 0)

**Response (200):**
```json
{
  "errors": [
    {
      "id": "uuid",
      "request_id": "req-uuid",
      "endpoint": "/api/agent/calls",
      "method": "GET",
      "status_code": 500,
      "error_message": "Database timeout",
      "stack_trace": "Error: timeout...",
      "request_body": "{\"domain\":\"example.com\"}",
      "user_id": "user-uuid",
      "display_name": "John Doe",
      "email": "user@example.com",
      "ip": "192.168.1.1",
      "timestamp": 1704067200000,
      "related_client_errors": 2
    }
  ],
  "total": 156,
  "limit": 100,
  "offset": 0
}
```

**Notes:**
- `related_client_errors` counts client errors with matching `request_id`
- Grouped by server error ID

---

### POST `/api/admin/cleanup`

Delete old call and message data.

**Auth:** Required  
**Permission:** `*` (SuperAdmin only)

**Request:**
```json
{
  "olderThanDays": 30,
  "deleteCalls": true,
  "deleteMessages": true
}
```

**Response (200):**
```json
{
  "status": "cleaned",
  "deletedCalls": 523,
  "deletedMessages": 8456,
  "cutoffDate": "2023-12-01T00:00:00.000Z"
}
```

**Errors:**
- `400` - olderThanDays must be ≥ 1
- `403` - Requires SuperAdmin

**Notes:**
- Does not delete active calls
- All fields in request are required

---

## WebSocket API

### Call Room WebSocket

**URL:** `wss://worker.dev/call/:roomId?token=<JWT_TOKEN>&mode=<MODE>`

**Query Parameters:**
- `token` (required): JWT token (visitor or agent)
- `mode` (optional): `talk` (default) | `monitor` (view-only)

#### Connection Flow

**1. Client → Server: Join**
```json
// No message needed - automatic on connect
```

**2. Server → Client: Join Confirmation**
```json
{
  "type": "system",
  "action": "joined",
  "userId": "visitor_uuid",
  "role": "visitor"
}
```

#### WebRTC Signaling

**Offer (Initiator):**
```json
{
  "type": "offer",
  "data": {
    "type": "offer",
    "sdp": "v=0\r\no=- ..."
  }
}
```

**Answer (Receiver):**
```json
{
  "type": "answer",
  "data": {
    "type": "answer",
    "sdp": "v=0\r\no=- ..."
  }
}
```

**ICE Candidate:**
```json
{
  "type": "ice",
  "data": {
    "candidate": "candidate:...",
    "sdpMLineIndex": 0,
    "sdpMid": "0"
  }
}
```

**Backward Compatibility:**
```json
// Old format (still supported):
{
  "type": "candidate",
  "candidate": { /* ... */ }
}
// Or:
{
  "type": "offer",
  "sdp": { /* ... */ }
}
```

#### Call Control

**Initiate Call:**
```json
{
  "type": "call_initiate"
}
```

**Server Response:**
```json
{
  "type": "incoming_call",
  "from": "visitor",
  "caller_id": "visitor_uuid",
  "timestamp": 1704067200000
}
// Or:
{
  "type": "call_ringing",
  "status": "ringing",
  "message": "Rufe Agents an..."
}
```

**Accept Call:**
```json
{
  "type": "call_accept"
}
```

**Server Broadcast:**
```json
{
  "type": "call_accepted",
  "by": "agent-uuid",
  "by_role": "agent",
  "timestamp": 1704067200000
}
```

**Reject Call:**
```json
{
  "type": "call_reject"
}
```

**Server Broadcast:**
```json
{
  "type": "call_rejected",
  "by": "visitor_uuid",
  "by_role": "visitor",
  "timestamp": 1704067200000
}
```

**End Call:**
```json
{
  "type": "call_ended"
}
```

#### Chat Messages

**Send Chat:**
```json
{
  "type": "chat",
  "text": "Hello, how can I help?"
}
```

**Broadcast to Others:**
```json
{
  "type": "chat",
  "text": "Hello, how can I help?"
}
```

**Typing Indicator:**
```json
{
  "type": "typing",
  "isTyping": true
}
```

#### Heartbeat

**Client → Server:**
```json
{
  "type": "heartbeat"
}
```

**No Response** (heartbeat tracked server-side)

**Notes:**
- Send heartbeat every 5-10 seconds
- Server detects stale visitors after 10s
- Automatically updates call status on timeout

#### System Messages

**Peer Left:**
```json
{
  "type": "system",
  "action": "peer_left",
  "userId": "agent-uuid",
  "role": "agent"
}
```

**Call Already Claimed:**
```json
{
  "type": "call_already_claimed",
  "status": "error",
  "message": "Ein anderer Agent hat bereits angenommen"
}
```

**Ping/Pong:**
```json
// Client → Server
{
  "type": "ping"
}

// Server → Client
{
  "type": "pong"
}
```

---

## Error Codes

### HTTP Status Codes

- `200` - OK
- `201` - Created
- `400` - Bad Request (invalid input)
- `401` - Unauthorized (missing/invalid token)
- `403` - Forbidden (insufficient permissions)
- `404` - Not Found
- `409` - Conflict (duplicate resource)
- `426` - Upgrade Required (WebSocket expected)
- `429` - Too Many Requests (rate limit)
- `500` - Internal Server Error

### Error Response Format

```json
{
  "error": "Error message",
  "status": 400,
  "request_id": "uuid"
}
```

**Notes:**
- Production mode returns sanitized errors
- `request_id` header: `X-Request-ID`
- Use `request_id` for error correlation

---

## Data Models

### User

```typescript
{
  id: string;              // UUID
  email: string;
  displayName: string;
  approved: boolean;
  isActive: boolean;
  roles: Role[];
  allowedDomains: string[];
  lastLogin: number | null;
  createdAt: number;
}
```

### Role

```typescript
{
  id: string;              // e.g., "role_agent"
  name: string;
  level: number;           // 0-1000, higher = more powerful
  permissions: string[];
  canManageRoles: string[];
  description: string;
  isSystem: boolean;
}
```

### Domain

```typescript
{
  id: string;              // Normalized domain name
  name: string;            // Display name
  aliases: string[];       // Alternative domain names
  manual: boolean;         // Manually added vs auto-announced
  isActive: boolean;
  lastSeen: number | null;
  createdAt: number;
}
```

### Call

```typescript
{
  id: number;              // Auto-increment
  session_id: string;      // "domain__uuid"
  domain_id: string;
  agent_id: string | null;
  status: "queued" | "ringing" | "active" | "completed" | "missed" | "cancelled";
  start_time: number;      // Timestamp
  end_time: number | null;
  queue_time: number | null;  // When agent accepted
  visitor_token: string;   // IP or visitor ID
  last_heartbeat: number | null;
}
```

### Message

```typescript
{
  id: string;              // UUID
  session_id: string;
  sender_id: string;
  sender_type: "visitor" | "agent" | "system";
  timestamp: number;
  content: string;
  metadata: string | null; // JSON
}
```

### ErrorReport

```typescript
{
  id: string;              // UUID
  user_id: string | null;
  app_version: string;
  platform: "ios" | "android" | "web" | "desktop";
  device_info: object;     // JSON
  error_type: "crash" | "network" | "webrtc" | "permission" | "ui" | "other";
  error_message: string;
  stack_trace: string | null;
  context: object;         // JSON
  severity: "fatal" | "error" | "warning" | "info";
  timestamp: number;
  resolved: boolean;
  resolved_by: string | null;
  resolved_at: number | null;
  notes: string | null;
  request_id: string | null;
  http_status: number | null;
  endpoint: string | null;
}
```

### ActivityLog

```typescript
{
  id: number;              // Auto-increment
  user_id: string;
  action: string;
  target_type: "user" | "call" | "role" | "domain" | "system" | "error" | "messages";
  target_id: string | null;
  ip: string | null;
  timestamp: number;
  metadata: object | null; // JSON
}
```

---

## Rate Limiting

**Window:** 60 seconds (1 minute)  
**Max Requests:** 100 per IP per endpoint  
**Blocking:** 5 minutes after exceeding limit

**Affected Endpoints:**
- `/api/login`
- `/api/register`
- `/api/public/initiate_room`
- `/api/errors/report`

**Headers:**
- `X-Request-ID`: Unique request identifier

---

## CORS Configuration

**Dynamic CORS:** Origins loaded from registered domains + development URLs

**Default Allowed:**
- `http://localhost:3000`
- `http://localhost:8787`
- All registered domain IDs: `https://{domain_id}`, `http://{domain_id}`
- All domain aliases: `https://{alias}`, `http://{alias}`

**Cache:** 5 minutes TTL

**Headers:**
- `Access-Control-Allow-Origin`
- `Access-Control-Allow-Methods`: GET, POST, PATCH, DELETE, OPTIONS
- `Access-Control-Allow-Headers`: Content-Type, Authorization
- `Access-Control-Max-Age`: 86400

---

## Domain Normalization

All domain IDs are automatically normalized:

**Rules:**
1. Lowercase conversion
2. Umlauts: `ä→ae`, `ö→oe`, `ü→ue`, `ß→ss`
3. Diacritics removed: `é→e`, `ñ→n`
4. Special chars → underscore
5. Leading/trailing `_`, `.`, `-` removed
6. Max length: 253 characters

**Example:**
- Input: `MÃ¼ller-GmbH.com`
- Output: `mueller-gmbh.com`

---

## Push Notifications (FCM)

**Requirements:**
- Environment variables: `FCM_PROJECT_ID`, `FCM_PRIVATE_KEY`, `FCM_CLIENT_EMAIL`
- Agent device registration via `/api/agent/register_device`

**Trigger Conditions:**
- Agent is offline (no WebSocket connection)
- Device `last_active` within 7 days
- Device `is_active = 1`

**Notification Types:**

**Visitor Waiting:**
```json
{
  "title": "Neuer Besucher wartet",
  "body": "Domain: example.com",
  "priority": "normal",
  "data": {
    "type": "visitor_waiting",
    "room_id": "example.com__uuid",
    "domain_id": "example.com"
  }
}
```

**Incoming Call:**
```json
{
  "title": "Eingehender Anruf",
  "body": "Von: Besucher",
  "priority": "high",
  "data": {
    "type": "incoming_call",
    "room_id": "example.com__uuid",
    "caller_id": "visitor_uuid"
  }
}
```

---

## Scheduled Tasks

### Queue Cleanup (Cron Trigger)

**Schedule:** Every 5-10 minutes (configurable in wrangler.toml)

**Function:** Marks old queued calls as `missed`

**Conditions:**
- Call status: `queued`
- Age: > 10 minutes without activity
- Limit: 100 calls per run

**Actions:**
1. Update status to `missed`
2. Set `end_time`
3. Notify agents via AgentHub
4. Broadcast `call_ended` event

---

## Security Notes

1. **JWT Tokens:**
   - HS256 algorithm
   - Store `JWT_SECRET` securely in environment
   - Visitor tokens: 1 hour expiry
   - Agent tokens: configurable (default: 24h)

2. **Password Requirements:**
   - Minimum 8 characters
   - Hashed with bcrypt (10 rounds)

3. **Rate Limiting:**
   - IP-based per endpoint
   - 100 requests/minute/IP
   - 5 minute block after exceeding

4. **Input Validation:**
   - Email format validation
   - Domain ID normalization
   - String length limits
   - Integer bounds checking

5. **Permission Hierarchy:**
   - Role levels: 0-1000
   - SuperAdmin (`*`) bypasses all checks
   - Can't edit users with equal/higher role level

6. **SQL Injection Prevention:**
   - All queries use prepared statements
   - Parameters bound separately

7. **CORS:**
   - Dynamic origin validation
   - Whitelist-based approach
   - No wildcard in production

---

## Environment Variables

**Required:**
```
JWT_SECRET=your-secret-key
```

**Optional:**
```
ADMIN_EMAIL=admin@example.com
ADMIN_PASSWORD=changeme123
FCM_PROJECT_ID=your-project-id
FCM_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----
FCM_CLIENT_EMAIL=firebase-adminsdk@project.iam.gserviceaccount.com
```

---

## Database Schema

**Tables:**
- `users` - User accounts
- `roles` - Role definitions
- `user_roles` - User-role assignments (many-to-many)
- `user_domains` - User-domain assignments (many-to-many)
- `user_hierarchy` - User management hierarchy
- `domains` - Registered domains
- `calls` - Call sessions
- `messages` - Chat messages
- `agent_devices` - FCM device tokens
- `agent_sessions` - Agent online status (optional)
- `rate_limits` - Rate limiting data
- `user_activity` - Activity logs
- `error_reports` - Client error reports
- `server_errors` - Server error logs

**Key Indexes:**
- `users(email)` - UNIQUE
- `calls(session_id)` - UNIQUE
- `calls(domain_id, status)` - Query performance
- `messages(session_id, timestamp)` - Query performance
- `user_activity(user_id, timestamp)` - Query performance
- `error_reports(request_id)` - Correlation
- `server_errors(request_id)` - Correlation

---

## Version History

**2.0 (Current)**
- Role-based access control (RBAC)
- Domain aliasing support
- Error correlation (client ↔ server)
- Push notifications (FCM)
- Hybrid notifications (WebSocket + Push)
- Domain normalization
- Queue management
- Heartbeat monitoring
- Enhanced permissions
- Activity logging
- Scheduled cleanup

**1.0**
- Initial release
- Basic call routing
- WebRTC signaling
- Admin panel
- User management

---

**End of API Reference**