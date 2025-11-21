import { jwtVerify, SignJWT } from 'jose'; 

// ============================================================================
// TEIL 1: KONFIGURATION & SICHERHEIT
// ============================================================================

/**
 * CORS Header Helper
 * Erlaubt Zugriff von überall (für Dev). 
 * In Produktion sollten Sie "Access-Control-Allow-Origin" auf Ihre Domain setzen.
 */
const corsHeaders = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

/**
 * HILFSFUNKTION: Sicheres Antwort-Objekt mit CORS
 */
function jsonResponse(body, status = 200) {
    return new Response(JSON.stringify(body), {
        status: status,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
}

/**
 * HILFSFUNKTION: Error Response
 */
function errorResponse(message, status = 500) {
    return jsonResponse({ error: message }, status);
}

// --- CRYPTO HELPER ---

/**
 * Simuliertes Hashing. 
 * HINWEIS: Für maximale Sicherheit in Produktion empfehlen wir die WebCrypto API (PBKDF2)
 * oder einen externen Auth-Provider.
 */
async function hashPassword(password) {
    return "HASH_" + password; 
}

async function checkPassword(provided, stored) {
    return stored === ("HASH_" + provided);
}

// --- JWT HELPER ---

async function verifyToken(token, secret) {
    try {
        const key = new TextEncoder().encode(secret);
        const { payload } = await jwtVerify(token, key, { algorithms: ['HS256'] });
        return payload;
    } catch (e) {
        throw new Error("Invalid Token");
    }
}

async function signToken(payload, secret) {
    const key = new TextEncoder().encode(secret);
    return new SignJWT(payload)
        .setProtectedHeader({ alg: 'HS256' })
        .setIssuedAt()
        .setExpirationTime('8h') // Token ist 8 Stunden gültig
        .sign(key);
}



// ============================================================================
// TEIL 2: PUBLIC API ENDPUNKTE (Login, Register, Domains)
// ============================================================================
/**
 * POST /api/login
 * Prüft Credentials gegen D1 Datenbank und gibt JWT zurück.
 */
async function handleLogin(request, env) {
    try {
        const { email, password } = await request.json();
        
        // 1. User in D1 suchen
        const user = await env.DB.prepare('SELECT * FROM users WHERE email = ?')
            .bind(email)
            .first();

        // 2. Passwort prüfen
        if (!user || !(await checkPassword(password, user.password_hash))) {
            return errorResponse("Ungültige Zugangsdaten", 401);
        }

        // 3. Status prüfen
        if (!user.approved) {
            return errorResponse("Account wartet auf Freischaltung durch Administrator.", 403);
        }

        // 4. JSON Strings parsen (verwenden jetzt 'allowed_domains')
        // ACHTUNG: Der Datenbank-Spaltenname MUSS in der DB auf 'allowed_domains' umbenannt werden!
        const allowedDomains = user.allowed_domains ? JSON.parse(user.allowed_domains) : [];

        // 5. Token erstellen
        const payload = {
            userId: user.id,
            email: user.email,
            role: user.role,
            allowed_domains: allowedDomains // Payload Key angepasst
        };

        const token = await signToken(payload, env.JWT_SECRET);

        return jsonResponse({ 
            token, 
            role: user.role,
            domains: allowedDomains // Hier den Antwort-Key von 'rooms' auf 'domains' angepasst
        });

    } catch (e) {
        return errorResponse("Login Fehler: " + e.message);
    }
}

/**
 * POST /api/public/initiate_call
 * Erstellt den initialen Eintrag in der D1 'calls' Tabelle, um eine Warteschlangen-Session zu starten.
 * * @param {Request} request 
 * @param {Env} env 
 * @returns {Response} Die generierte Room ID.
 */
async function handleInitiateCall(request, env) {
    try {
        const { domain_id } = await request.json();

        if (!domain_id) {
            return errorResponse("Fehlender Parameter: domain_id", 400);
        }
        
        // 1. Session ID generieren
        const sessionId = crypto.randomUUID();
        // 2. Volle Room ID erstellen
        const roomId = `${domain_id}__${sessionId}`;

        // 3. Eintrag in die 'calls' Tabelle erstellen (agent_id bleibt NULL)
        await env.DB.prepare(
            'INSERT INTO calls (session_id, domain_id, start_time, agent_id) VALUES (?, ?, ?, NULL)'
        ).bind(roomId, domain_id, Date.now()).run();

        // 4. Room ID an den Kunden zurückgeben
        return jsonResponse({
            message: "Call Session erstellt und in die Warteschlange gestellt.",
            room_id: roomId
        });

    } catch (e) {
        // Fehler, falls die Domain ID ungültig ist oder die DB fehlschlägt.
        return errorResponse("Fehler beim Starten der Call Session: " + e.message);
    }
}


/**
 * POST /api/register
 * Erstellt neuen User in D1 (Status: approved = 0)
 */
async function handleRegister(request, env) {
    try {
        const { email, password, role = 'agent' } = await request.json();

        // 1. Prüfen ob User existiert
        const existing = await env.DB.prepare('SELECT id FROM users WHERE email = ?')
            .bind(email)
            .first();

        if (existing) {
            return errorResponse("Benutzer existiert bereits", 409);
        }

        // 2. Neuen User anlegen
        const newId = crypto.randomUUID();
        const passHash = await hashPassword(password);
        const approved = 0; // false
        const emptyDomains = JSON.stringify([]); // Leeres Array als String (für allowed_domains)

        await env.DB.prepare(
            // Spalte  wurde hier auf 'allowed_domains' umbenannt
            'INSERT INTO users (id, email, password_hash, role, allowed_domains, approved) VALUES (?, ?, ?, ?, ?, ?)'
        ).bind(newId, email, passHash, role, emptyDomains, approved).run();

        return jsonResponse({ message: "Registrierung erfolgreich. Warten auf Freischaltung." }, 201);

    } catch (e) {
        return errorResponse("Registrierung Fehler: " + e.message);
    }
}

/**
 * POST /api/domain/announce
 * Öffentlicher Endpunkt für Business-Seiten, um sich anzumelden.
 */
async function handleDomainAnnounce(request, env) {
    try {
        const { businessId, name } = await request.json();
        if (!businessId) return errorResponse("ID Missing", 400);

        const now = Date.now();

        // Prüfen ob Domain existiert
        const existing = await env.DB.prepare('SELECT manual FROM domains WHERE id = ?').bind(businessId).first();
        
        if (existing) {
             // Nur "Zuletzt gesehen" aktualisieren
             await env.DB.prepare('UPDATE domains SET lastSeen = ? WHERE id = ?').bind(now, businessId).run();
        } else {
             // Neu anlegen (Automatisch -> manual = 0)
             await env.DB.prepare(
                 'INSERT INTO domains (id, name, aliases, manual, lastSeen) VALUES (?, ?, ?, 0, ?)'
             ).bind(businessId, name || businessId, '[]', now).run();
        }
        
        return jsonResponse({ status: "announced" });
    } catch (e) { return errorResponse("Error: " + e.message, 500); }
}



// ============================================================================
// TEIL 3: ADMIN MANAGEMENT API (Geschützt)
// ============================================================================

/**
 * Middleware: Prüft JWT auf 'superadmin' Rolle
 */
async function requireSuperAdmin(request, env) {
    const authHeader = request.headers.get('Authorization');
    if (!authHeader) throw new Error("Missing Auth");
    
    const token = authHeader.replace('Bearer ', '');
    const payload = await verifyToken(token, env.JWT_SECRET);
    
    if (payload.role !== 'superadmin') throw new Error("Forbidden");
    return payload;
}

/**
 * GET /api/admin/data
 * Lädt alle User und Domains aus D1
 */
async function handleAdminData(request, env) {
    try {
        await requireSuperAdmin(request, env);

        // Alle User laden
        const { results: usersRaw } = await env.DB.prepare('SELECT id, email, role, allowed_domains, approved FROM users').all();
        const users = usersRaw.map(u => ({
            ...u,
            allowed_domains: u.allowed_domains ? JSON.parse(u.allowed_domains) : [],
            approved: u.approved === 1
        }));

        // Alle Domains laden
        const { results: domainsRaw } = await env.DB.prepare('SELECT * FROM domains').all();
        const domains = domainsRaw.map(d => ({
            ...d,
            aliases: d.aliases ? JSON.parse(d.aliases) : [],
            manual: d.manual === 1
        }));

        return jsonResponse({ users, domains });
    } catch (e) {
        return errorResponse("Unauthorized: " + e.message, 401);
    }
}

/**
 * POST /api/admin/approve/:userId
 * Schaltet User frei (approved = 1)
 */
async function handleAdminApprove(request, env, userId) {
    try {
        await requireSuperAdmin(request, env);

        const res = await env.DB.prepare('UPDATE users SET approved = 1 WHERE id = ?')
            .bind(userId)
            .run();

        if (res.meta.changes === 0) return errorResponse("User nicht gefunden", 404);

        return jsonResponse({ status: "approved" });
    } catch (e) { return errorResponse("Error: " + e.message, 500); }
}

/**
 * POST /api/admin/assign
 * Speichert zugewiesene Räume für einen User
 */
async function handleAdminAssign(request, env) {
    try {
        await requireSuperAdmin(request, env);
        const { targetUserId, allowed_domains } = await request.json();

        const roomsJson = JSON.stringify(allowed_domains || []);

        const res = await env.DB.prepare('UPDATE users SET allowed_domains = ? WHERE id = ?')
            .bind(roomsJson, targetUserId)
            .run();

        if (res.meta.changes === 0) return errorResponse("User nicht gefunden", 404);

        return jsonResponse({ status: "assigned" });
    } catch (e) { return errorResponse("Error: " + e.message, 500); }
}

/**
 * POST /api/admin/domain/add
 * Manuelles Hinzufügen/Editieren einer Domain durch Admin
 */
async function handleAdminAddDomain(request, env) {
    try {
        await requireSuperAdmin(request, env);
        const { id, name, aliases } = await request.json();

        if (!id) return errorResponse("ID fehlt", 400);
        
        const aliasesJson = JSON.stringify(Array.isArray(aliases) ? aliases : []);
        const now = Date.now();

        // Upsert Logik (Insert oder Update wenn ID existiert)
        await env.DB.prepare(
            `INSERT INTO domains (id, name, aliases, manual, lastSeen) VALUES (?, ?, ?, 1, ?)
             ON CONFLICT(id) DO UPDATE SET name=excluded.name, aliases=excluded.aliases, manual=1`
        ).bind(id, name || id, aliasesJson, now).run();

        return jsonResponse({ status: "saved" });
    } catch (e) { return errorResponse("Error: " + e.message, 500); }
}





// ============================================================================
// TEIL 4: ROUTING & DURABLE OBJECT (WebSocket)
// ============================================================================

var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// --- MAIN WORKER ---
var server_default = {
    async fetch(request, env) {
        
 

        // CORS Handling
        if (request.method === "OPTIONS") {
            return new Response(null, { headers: corsHeaders });
        }

        const url = new URL(request.url);
        const path = url.pathname.split("/").filter((p) => p);

        // 1. API ROUTING
        if (path[0] === "api") {
            // Public
            if (path[1] === "login") return handleLogin(request, env);
            if (path[1] === "register") return handleRegister(request, env);
            if (path[1] === "domain" && path[2] === "announce") return handleDomainAnnounce(request, env);
            if (path[1] === "public" && path[2] === "initiate_call") return handleInitiateCall(request, env);
            // Admin Protected
            if (path[1] === "admin") {
                if (path[2] === "data") return handleAdminData(request, env);
                if (path[2] === "assign") return handleAdminAssign(request, env);
                if (path[2] === "approve" && path[3]) return handleAdminApprove(request, env, path[3]);
                if (path[2] === "domain" && path[3] === "add") return handleAdminAddDomain(request, env);
            }
            
            return errorResponse("Endpoint not found", 404);
        }

        // 2. WEBSOCKET ROUTING (/call/<ROOM_ID>)
        if (path[0] === "call" && path[1]) {
            const roomId = path[1];
            const id = env.CALL_ROOM.idFromName(roomId);
            const roomObject = env.CALL_ROOM.get(id);
            return roomObject.fetch(request);
        }

        return new Response("Call Server Running.", { status: 200 });
    }
};

// --- DURABLE OBJECT (Room Logic) ---
var CallRoom = class {
    static { __name(this, "CallRoom"); }

    constructor(state, env) {
        this.state = state;
        this.sessions = [];
        this.env = env; // <-- Wichtig: DB-Zugriff speichern
    }

    // --- NEUE LOGGING HELPER FUNKTIONEN ---

    async logCallStart(session) {
        // Erwartet: session.roomId = DOMAIN__SESSION_ID
        const [domainId] = session.roomId.split('__');
        
        await this.env.DB.prepare(
            'INSERT INTO calls (session_id, domain_id, agent_id, start_time) VALUES (?, ?, ?, ?)'
        ).bind(session.roomId, domainId, session.userId, Date.now()).run();
    }

    async logCallEnd(roomId) {
        // Protokolliert das Ende der Session
        await this.env.DB.prepare(
            'UPDATE calls SET end_time = ? WHERE session_id = ?'
        ).bind(Date.now(), roomId).run();
    }
    
    async logMessage(sessionId, senderId, content) {
        // Protokolliert jede einzelne Chat-Nachricht
        await this.env.DB.prepare(
            'INSERT INTO messages (id, session_id, sender_id, timestamp, content) VALUES (?, ?, ?, ?, ?)'
        ).bind(crypto.randomUUID(), sessionId, senderId, Date.now(), content).run();
    }

    // --- ENDE LOGGING HELPER FUNKTIONEN ---
    
  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Expected WebSocket", { status: 426 });
    }

    const url = new URL(request.url);
    const ip = request.headers.get("CF-Connecting-IP");
    const requestedRoomId = url.pathname.split("/").filter((p) => p)[1];
    const token = url.searchParams.get("token");
    
    // NEU: Modus aus den URL-Parametern lesen
    const mode = url.searchParams.get("mode") || 'talk'; 

    if (!token) return new Response("Missing Token", { status: 401 });

    let sessionData;
    try {
        // 1. Token validieren und Payload erhalten
        sessionData = await verifyToken(token, this.env.JWT_SECRET);
        
        // 2. Format und Domain-Berechtigung prüfen (wie zuvor)
        const idParts = requestedRoomId.split('__'); 
        if (idParts.length < 2) {
            return new Response("Invalid Room ID Format. Use: DOMAIN__SESSIONID", { status: 400 });
        }
        const targetDomain = idParts[0];

        // ACHTUNG: Hier muss die Supervisor-Rolle bei Bedarf ergänzt werden
        const isSuperAdmin = sessionData.role === 'superadmin';
        const hasDomainAccess = Array.isArray(sessionData.allowed_domains) && 
                                  sessionData.allowed_domains.includes(targetDomain);

        if (!isSuperAdmin && !hasDomainAccess) {
            return new Response(`Access Denied. User not assigned to domain: ${targetDomain}`, { status: 403 });
        }

        // =========================================================
        // 3. CLAIMING-PRÜFUNG
        // =========================================================

        const callRecord = await this.env.DB.prepare(
            'SELECT agent_id FROM calls WHERE session_id = ?'
        ).bind(requestedRoomId).first();

        if (callRecord && callRecord.agent_id) {
            
            // Nur der Supervisor oder der beanspruchende Agent darf rein
            if (mode !== 'monitor' && callRecord.agent_id !== sessionData.userId) {
                return new Response("Call already claimed by another agent.", { status: 403 });
            }
        }
        
    } catch (e) {
        return new Response(`Invalid Token: ${e.message}`, { status: 401 });
    }

    // Wenn alle Checks bestanden:
    const { 0: client, 1: server } = new WebSocketPair();
    this.handleSession(server, ip, requestedRoomId, sessionData, mode); 
    
    return new Response(null, { status: 101, webSocket: client });
}

  async handleSession(webSocket, ip, roomId, userData, mode = 'talk') {
    webSocket.accept();
    
    const session = {
        socket: webSocket,
        ip,
        roomId,
        userId: userData.userId,
        role: userData.role,
        id: crypto.randomUUID(),
        mode: mode, // Modus speichern
        alive: true
    };
    
    // Protokollierung des Call-Starts entfernt, da dies nun über /api/public/initiate_call geschieht.

    this.sessions.push(session);

    // --- Message Listener ---
    webSocket.addEventListener("message", async (msg) => {
        try {
            const messageData = JSON.parse(msg.data);
            
            // Nachrichten protokollieren nur, wenn der Modus NICHT monitor ist.
            if (session.mode !== 'monitor' && messageData.type === 'chat' && messageData.text) {
                 await this.logMessage(session.roomId, session.userId, messageData.text);
            }

            // Broadcast an alle im Raum (auch WebRTC Signaling)
            this.broadcast(session, msg.data);
        } catch (err) { console.error(err); }
    });

    const closeHandler = () => this.cleanup(session);
    webSocket.addEventListener("close", closeHandler);
    webSocket.addEventListener("error", closeHandler);
}
    
    // ... (broadcast Methode bleibt unverändert) ...
    broadcast(senderSession, message) {
        this.sessions = this.sessions.filter((s) => {
            try {
                if (s.socket.readyState !== 1) return false;
                if (s.id !== senderSession.id) {
                    s.socket.send(message);
                }
                return true;
            } catch (err) { return false; }
        });
    }

    // ... (cleanup Methode mit Logik-Update) ...
    cleanup(session) {
        if (!session.alive) return;
        session.alive = false;
        
        // Zuerst die Session entfernen
        this.sessions = this.sessions.filter((s) => s.id !== session.id);
        
        // NEU: Wenn der letzte Agent geht, Call-Ende protokollieren
        const lastAgentLeft = session.role === 'agent' && 
                              !this.sessions.some(s => s.role === 'agent');
        
        if (lastAgentLeft) {
            this.logCallEnd(session.roomId);
        }

        const hangupMsg = JSON.stringify({ 
            type: "system", 
            action: "peer_left", 
            userId: session.userId,
            role: session.role 
        });
        
        this.sessions.forEach((s) => {
            try { s.socket.send(hangupMsg); } catch (e) { }
        });
    }
};

export { CallRoom, server_default as default };