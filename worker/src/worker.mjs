const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };
const MCP_PROTOCOL = "2025-06-18";

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true, service: "music-mirror", version: "0.2.0" });
      }
      if (request.method === "POST" && url.pathname === "/v1/sync") {
        if (!authorized(request, env.INGEST_TOKEN)) return json({ error: "unauthorized" }, 401);
        return await ingest(request, env.DB);
      }
      if (request.method === "GET" && url.pathname === "/v1/export") {
        if (!authorized(request, env.READ_TOKEN)) return json({ error: "unauthorized" }, 401);
        return await exportData(env.DB, url);
      }
      if (request.method === "GET" && url.pathname === "/v1/dashboard") {
        if (!authorized(request, env.INGEST_TOKEN)) return json({ error: "unauthorized" }, 401);
        return json(await dashboardData(env.DB));
      }
      if (request.method === "POST" && url.pathname === "/v1/feedback") {
        if (!authorized(request, env.INGEST_TOKEN)) return json({ error: "unauthorized" }, 401);
        return await saveFeedback(request, env.DB);
      }
      const capabilityPath = env.READ_TOKEN ? `/mcp/${env.READ_TOKEN}` : null;
      if (request.method === "POST" && (url.pathname === "/mcp" || url.pathname === capabilityPath)) {
        if (url.pathname === "/mcp" && !authorized(request, env.READ_TOKEN)) {
          return json({ error: "unauthorized" }, 401);
        }
        return await handleMcp(request, env.DB);
      }
      return json({ error: "not_found" }, 404);
    } catch (error) {
      console.error(error);
      return json({ error: "internal_error", message: String(error?.message || error) }, 500);
    }
  }
};

function authorized(request, expected) {
  if (!expected) return false;
  const value = request.headers.get("authorization") || "";
  return value === `Bearer ${expected}`;
}

async function ingest(request, db) {
  const body = await request.json();
  const listens = Array.isArray(body.listens) ? body.listens.slice(0, 500) : [];
  const events = Array.isArray(body.events) ? body.events.slice(0, 1000) : [];
  if (body.schemaVersion !== 1) return json({ error: "unsupported_schema" }, 400);

  const statements = [];
  const now = Date.now();
  for (const item of listens) {
    validateListen(item);
    statements.push(db.prepare(`
      INSERT INTO listens (
        id, track_key, media_id, title, artist, album, started_at, ended_at,
        listened_ms, duration_ms, last_position_ms, completed, device_id, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        media_id=excluded.media_id, title=excluded.title, artist=excluded.artist,
        album=excluded.album, ended_at=excluded.ended_at, listened_ms=excluded.listened_ms,
        duration_ms=excluded.duration_ms, last_position_ms=excluded.last_position_ms,
        completed=excluded.completed, updated_at=excluded.updated_at
    `).bind(
      item.id, item.trackKey, nullable(item.mediaId), clean(item.title, 500),
      clean(item.artist, 500), nullableClean(item.album, 500), integer(item.startedAt),
      nullableInteger(item.endedAt), integer(item.listenedMs), integer(item.durationMs),
      integer(item.lastPositionMs), item.completed ? 1 : 0, clean(item.deviceId, 200), now
    ));
  }
  for (const item of events) {
    validateEvent(item);
    statements.push(db.prepare(`
      INSERT INTO playback_events (listen_id, client_id, event_type, occurred_at, position_ms, payload)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(listen_id, client_id) DO UPDATE SET
        event_type=excluded.event_type, occurred_at=excluded.occurred_at,
        position_ms=excluded.position_ms, payload=excluded.payload
    `).bind(
      item.listenId, integer(item.clientId), clean(item.type, 64), integer(item.occurredAt),
      integer(item.positionMs), nullableClean(item.payload, 1000)
    ));
  }
  if (statements.length) await db.batch(statements);
  return json({ ok: true, acceptedListens: listens.length, acceptedEvents: events.length });
}

async function handleMcp(request, db) {
  const message = await request.json();
  const id = message.id ?? null;
  if (message.method === "initialize") {
    return rpc(id, {
      protocolVersion: MCP_PROTOCOL,
      capabilities: { tools: {} },
      serverInfo: { name: "music-mirror", version: "0.2.0" }
    });
  }
  if (message.method === "ping") return rpc(id, {});
  if (message.method === "notifications/initialized") return new Response(null, { status: 202 });
  if (message.method === "tools/list") return rpc(id, { tools: toolDefinitions() });
  if (message.method === "tools/call") {
    const name = message.params?.name;
    const args = message.params?.arguments || {};
    const result = await callTool(db, name, args);
    return rpc(id, {
      content: [{ type: "text", text: JSON.stringify(result) }],
      structuredContent: Array.isArray(result) ? { items: result } : (result || {})
    });
  }
  return rpcError(id, -32601, "Method not found");
}

function toolDefinitions() {
  const days = { type: "integer", minimum: 1, maximum: 365, default: 30 };
  return [
    withReadOnly({
      name: "recent_listens",
      description: "Get the user's most recent mobile YouTube Music listening sessions.",
      inputSchema: { type: "object", properties: { limit: { type: "integer", minimum: 1, maximum: 100, default: 20 } }, additionalProperties: false }
    }),
    withReadOnly({
      name: "listening_summary",
      description: "Summarize listening time, unique tracks, completion rate, and skip count for a period.",
      inputSchema: { type: "object", properties: { days }, additionalProperties: false }
    }),
    withReadOnly({
      name: "top_tracks",
      description: "Rank tracks by total listening time and show plays and completion rate.",
      inputSchema: { type: "object", properties: { days, limit: { type: "integer", minimum: 1, maximum: 50, default: 10 } }, additionalProperties: false }
    }),
    withReadOnly({
      name: "listening_patterns",
      description: "Analyze listening sessions by Korean local hour and day of week.",
      inputSchema: { type: "object", properties: { days }, additionalProperties: false }
    }),
    withReadOnly({
      name: "taste_snapshot",
      description: "Get top artists and tracks with strong repeat and completion signals.",
      inputSchema: { type: "object", properties: { days }, additionalProperties: false }
    }),
    withReadOnly({
      name: "music_now",
      description: "Get the latest currently open playback session, if recent enough.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false }
    }),
    withWrite({
      name: "save_recommendation_mix",
      description: "Save a personalized music recommendation mix so it appears in the user's Music Mirror Android app.",
      inputSchema: {
        type: "object",
        properties: {
          name: { type: "string", minLength: 1, maxLength: 120 },
          summary: { type: "string", maxLength: 1000 },
          items: {
            type: "array",
            minItems: 1,
            maxItems: 25,
            items: {
              type: "object",
              properties: {
                title: { type: "string", minLength: 1, maxLength: 300 },
                artist: { type: "string", minLength: 1, maxLength: 300 },
                album: { type: "string", maxLength: 300 },
                reason: { type: "string", maxLength: 800 },
                youtubeUrl: { type: "string", maxLength: 1000 }
              },
              required: ["title", "artist"],
              additionalProperties: false
            }
          }
        },
        required: ["name", "items"],
        additionalProperties: false
      }
    }),
    withReadOnly({
      name: "current_recommendation_mix",
      description: "Read the latest recommendation mix currently shown in the Music Mirror Android app.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false }
    }),
    withReadOnly({
      name: "recommendation_feedback",
      description: "Read the user's likes, dislikes, and plays for prior Music Mirror recommendations.",
      inputSchema: {
        type: "object",
        properties: { limit: { type: "integer", minimum: 1, maximum: 200, default: 100 } },
        additionalProperties: false
      }
    })
  ];
}

function withReadOnly(tool) {
  return {
    ...tool,
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false }
  };
}

function withWrite(tool) {
  return {
    ...tool,
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false, idempotentHint: false }
  };
}

async function callTool(db, name, args) {
  const days = clampInt(args.days, 30, 1, 365);
  const since = Date.now() - days * 86_400_000;
  if (name === "recent_listens") {
    const limit = clampInt(args.limit, 20, 1, 100);
    return { listens: rows(await db.prepare(`
      SELECT id, title, artist, album, started_at AS startedAt, ended_at AS endedAt,
             listened_ms AS listenedMs, duration_ms AS durationMs,
             last_position_ms AS lastPositionMs, completed, device_id AS deviceId
      FROM listens ORDER BY started_at DESC LIMIT ?
    `).bind(limit).all()) };
  }
  if (name === "listening_summary") {
    const summary = first(await db.prepare(`
      SELECT COUNT(*) AS sessions, COUNT(DISTINCT track_key) AS uniqueTracks,
             COUNT(DISTINCT artist) AS uniqueArtists,
             COALESCE(SUM(listened_ms), 0) AS listenedMs,
             ROUND(100.0 * AVG(completed), 1) AS completionRate
      FROM listens WHERE started_at >= ?
    `).bind(since).all());
    const skips = first(await db.prepare(`
      SELECT COUNT(*) AS skipCount FROM playback_events
      WHERE occurred_at >= ? AND event_type = 'skip_next'
    `).bind(since).all());
    return { periodDays: days, ...summary, ...skips };
  }
  if (name === "top_tracks") {
    const limit = clampInt(args.limit, 10, 1, 50);
    return { periodDays: days, tracks: rows(await db.prepare(`
      SELECT title, artist, album, COUNT(*) AS sessions,
             SUM(listened_ms) AS listenedMs,
             ROUND(100.0 * AVG(completed), 1) AS completionRate
      FROM listens WHERE started_at >= ?
      GROUP BY track_key, title, artist, album
      ORDER BY listenedMs DESC LIMIT ?
    `).bind(since, limit).all()) };
  }
  if (name === "listening_patterns") {
    const hourly = rows(await db.prepare(`
      SELECT CAST(strftime('%H', started_at / 1000, 'unixepoch', '+9 hours') AS INTEGER) AS hour,
             COUNT(*) AS sessions, SUM(listened_ms) AS listenedMs
      FROM listens WHERE started_at >= ? GROUP BY hour ORDER BY hour
    `).bind(since).all());
    const weekdays = rows(await db.prepare(`
      SELECT CAST(strftime('%w', started_at / 1000, 'unixepoch', '+9 hours') AS INTEGER) AS weekday,
             COUNT(*) AS sessions, SUM(listened_ms) AS listenedMs
      FROM listens WHERE started_at >= ? GROUP BY weekday ORDER BY weekday
    `).bind(since).all());
    return { periodDays: days, timezone: "Asia/Seoul", hourly, weekdays };
  }
  if (name === "taste_snapshot") {
    const artists = rows(await db.prepare(`
      SELECT artist, COUNT(*) AS sessions, COUNT(DISTINCT track_key) AS uniqueTracks,
             SUM(listened_ms) AS listenedMs, ROUND(100.0 * AVG(completed), 1) AS completionRate
      FROM listens WHERE started_at >= ? GROUP BY artist ORDER BY listenedMs DESC LIMIT 15
    `).bind(since).all());
    const stickyTracks = rows(await db.prepare(`
      SELECT title, artist, COUNT(*) AS sessions, SUM(listened_ms) AS listenedMs,
             ROUND(100.0 * AVG(completed), 1) AS completionRate
      FROM listens WHERE started_at >= ? GROUP BY track_key, title, artist
      HAVING COUNT(*) >= 2 ORDER BY completionRate DESC, sessions DESC LIMIT 15
    `).bind(since).all());
    return { periodDays: days, artists, stickyTracks };
  }
  if (name === "music_now") {
    const result = first(await db.prepare(`
      SELECT title, artist, album, started_at AS startedAt, listened_ms AS listenedMs,
             duration_ms AS durationMs, last_position_ms AS lastPositionMs, updated_at AS updatedAt
      FROM listens WHERE ended_at IS NULL AND updated_at >= ?
      ORDER BY updated_at DESC LIMIT 1
    `).bind(Date.now() - 10 * 60_000).all());
    return result ? { playing: true, ...result } : { playing: false };
  }
  if (name === "save_recommendation_mix") {
    return await saveRecommendationMix(db, args);
  }
  if (name === "current_recommendation_mix") {
    return await latestRecommendationMix(db, 25);
  }
  if (name === "recommendation_feedback") {
    const limit = clampInt(args.limit, 100, 1, 200);
    return {
      feedback: rows(await db.prepare(`
        SELECT f.feedback, f.created_at AS createdAt,
               r.title, r.artist, r.reason, s.name AS mixName
        FROM recommendation_feedback f
        JOIN recommendations r ON r.id = f.recommendation_id
        JOIN recommendation_sets s ON s.id = r.set_id
        ORDER BY f.created_at DESC LIMIT ?
      `).bind(limit).all())
    };
  }
  throw new Error(`Unknown tool: ${name}`);
}

async function dashboardData(db) {
  const days = 30;
  const since = Date.now() - days * 86_400_000;
  const summary = first(await db.prepare(`
    SELECT COUNT(*) AS sessions, COUNT(DISTINCT track_key) AS uniqueTracks,
           COALESCE(SUM(listened_ms), 0) AS listenedMs,
           ROUND(100.0 * AVG(completed), 1) AS completionRate
    FROM listens WHERE started_at >= ?
  `).bind(since).all()) || {};
  const skips = first(await db.prepare(`
    SELECT COUNT(*) AS skipCount FROM playback_events
    WHERE occurred_at >= ? AND event_type = 'skip_next'
  `).bind(since).all()) || {};
  return {
    periodDays: days,
    summary: { ...summary, ...skips },
    recommendationMix: await latestRecommendationMix(db, 25)
  };
}

async function saveRecommendationMix(db, args) {
  const name = clean(args.name, 120);
  const summary = nullableClean(args.summary, 1000);
  const items = Array.isArray(args.items) ? args.items.slice(0, 25) : [];
  if (!name || items.length === 0) throw new Error("A mix name and at least one recommendation are required");

  const mixId = crypto.randomUUID();
  const now = Date.now();
  const statements = [
    db.prepare(`
      INSERT INTO recommendation_sets (id, name, summary, created_at, source)
      VALUES (?, ?, ?, ?, 'chatgpt')
    `).bind(mixId, name, summary, now)
  ];
  items.forEach((item, index) => {
    if (!item || typeof item !== "object") throw new Error(`Invalid recommendation at position ${index + 1}`);
    const title = clean(item.title, 300);
    const artist = clean(item.artist, 300);
    const youtubeUrl = safeYouTubeUrl(item.youtubeUrl);
    statements.push(db.prepare(`
      INSERT INTO recommendations (
        id, set_id, position, title, artist, album, reason, youtube_url, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      crypto.randomUUID(), mixId, index + 1, title, artist,
      nullableClean(item.album, 300), nullableClean(item.reason, 800), youtubeUrl, now
    ));
  });
  await db.batch(statements);
  return { ok: true, mixId, name, savedCount: items.length, message: "The mix is now available in the Music Mirror app." };
}

async function latestRecommendationMix(db, limit) {
  const mix = first(await db.prepare(`
    SELECT id, name, summary, created_at AS createdAt, source
    FROM recommendation_sets ORDER BY created_at DESC LIMIT 1
  `).all());
  if (!mix) return { mix: null, items: [] };
  const items = rows(await db.prepare(`
    SELECT r.id, r.position, r.title, r.artist, r.album, r.reason,
           r.youtube_url AS youtubeUrl,
           (SELECT f.feedback FROM recommendation_feedback f
            WHERE f.recommendation_id = r.id
            ORDER BY f.created_at DESC LIMIT 1) AS feedback
    FROM recommendations r
    WHERE r.set_id = ? ORDER BY r.position LIMIT ?
  `).bind(mix.id, limit).all());
  return { mix, items };
}

async function saveFeedback(request, db) {
  const body = await request.json();
  const recommendationId = clean(body.recommendationId, 100);
  const feedback = String(body.feedback || "").trim();
  if (!["like", "dislike", "played"].includes(feedback)) {
    return json({ error: "invalid_feedback" }, 400);
  }
  const exists = first(await db.prepare(
    "SELECT id FROM recommendations WHERE id = ? LIMIT 1"
  ).bind(recommendationId).all());
  if (!exists) return json({ error: "recommendation_not_found" }, 404);
  await db.prepare(`
    INSERT INTO recommendation_feedback (recommendation_id, feedback, created_at, device_id)
    VALUES (?, ?, ?, ?)
  `).bind(recommendationId, feedback, Date.now(), nullableClean(body.deviceId, 200)).run();
  return json({ ok: true, recommendationId, feedback });
}

async function exportData(db, url) {
  const days = clampInt(url.searchParams.get("days"), 365, 1, 3650);
  const since = Date.now() - days * 86_400_000;
  const listens = rows(await db.prepare("SELECT * FROM listens WHERE started_at >= ? ORDER BY started_at").bind(since).all());
  const events = rows(await db.prepare("SELECT * FROM playback_events WHERE occurred_at >= ? ORDER BY occurred_at").bind(since).all());
  return json({ exportedAt: Date.now(), periodDays: days, listens, events });
}

function validateListen(item) {
  if (!item || typeof item !== "object") throw new Error("Invalid listen");
  if (typeof item.artist === "string" && !item.artist.trim()) item.artist = "Unknown artist";
  for (const key of ["id", "trackKey", "title", "artist", "deviceId"]) {
    if (typeof item[key] !== "string" || !item[key].trim()) throw new Error(`Invalid listen.${key}`);
  }
}

function validateEvent(item) {
  if (!item || typeof item !== "object") throw new Error("Invalid event");
  if (typeof item.listenId !== "string" || typeof item.type !== "string") throw new Error("Invalid event fields");
}

function clean(value, max) {
  if (typeof value !== "string") throw new Error("Expected string");
  return value.trim().slice(0, max);
}
function nullableClean(value, max) { return value == null ? null : clean(String(value), max); }
function nullable(value) { return value == null ? null : String(value); }
function safeYouTubeUrl(value) {
  if (value == null || String(value).trim() === "") return null;
  try {
    const url = new URL(String(value));
    const host = url.hostname.toLowerCase();
    return url.protocol === "https:" && ["youtube.com", "www.youtube.com", "music.youtube.com", "youtu.be"].includes(host)
      ? url.toString().slice(0, 1000)
      : null;
  } catch {
    return null;
  }
}
function integer(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error("Expected number");
  return Math.trunc(number);
}
function nullableInteger(value) { return value == null ? null : integer(value); }
function clampInt(value, fallback, min, max) {
  const number = Number.parseInt(value, 10);
  return Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : fallback;
}
function rows(result) { return result?.results || []; }
function first(result) { return rows(result)[0] || null; }
function json(value, status = 200) { return new Response(JSON.stringify(value), { status, headers: JSON_HEADERS }); }
function rpc(id, result) { return json({ jsonrpc: "2.0", id, result }); }
function rpcError(id, code, message) { return json({ jsonrpc: "2.0", id, error: { code, message } }); }
