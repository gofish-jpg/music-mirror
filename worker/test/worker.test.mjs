import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/worker.mjs";

const env = { INGEST_TOKEN: "ingest-secret", READ_TOKEN: "read-secret", DB: {} };

test("health endpoint is public", async () => {
  const response = await worker.fetch(new Request("https://example.test/health"), env);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).service, "music-mirror");
});

test("MCP rejects missing read token", async () => {
  const response = await worker.fetch(new Request("https://example.test/mcp", { method: "POST", body: "{}" }), env);
  assert.equal(response.status, 401);
});

test("MCP initializes", async () => {
  const request = new Request("https://example.test/mcp", {
    method: "POST",
    headers: { authorization: "Bearer read-secret", "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} })
  });
  const response = await worker.fetch(request, env);
  const body = await response.json();
  assert.equal(body.result.serverInfo.name, "music-mirror");
  assert.ok(body.result.capabilities.tools);
});

test("MCP capability URL initializes without an Authorization header", async () => {
  const request = new Request("https://example.test/mcp/read-secret", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 11, method: "initialize", params: {} })
  });
  const response = await worker.fetch(request, env);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).result.serverInfo.version, "0.2.0");
});

test("MCP lists analytics and AI DJ tools", async () => {
  const request = new Request("https://example.test/mcp", {
    method: "POST",
    headers: { authorization: "Bearer read-secret", "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} })
  });
  const body = await (await worker.fetch(request, env)).json();
  assert.equal(body.result.tools.length, 9);
  assert.ok(body.result.tools.every((tool) => tool.inputSchema.type === "object"));
  assert.equal(body.result.tools.find((tool) => tool.name === "save_recommendation_mix").annotations.readOnlyHint, false);
});

test("recent listens returns object-shaped structured content", async () => {
  const fakeDb = {
    prepare() {
      return {
        bind() { return this; },
        async all() { return { results: [{ title: "Song", artist: "Artist" }] }; }
      };
    }
  };
  const request = new Request("https://example.test/mcp/read-secret", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      jsonrpc: "2.0", id: 12, method: "tools/call",
      params: { name: "recent_listens", arguments: { limit: 5 } }
    })
  });
  const body = await (await worker.fetch(request, { ...env, DB: fakeDb })).json();
  assert.ok(Array.isArray(body.result.structuredContent.listens));
  assert.equal(body.result.structuredContent.listens[0].title, "Song");
});

test("ChatGPT can save a recommendation mix for the Android app", async () => {
  let batchSize = 0;
  const fakeDb = {
    prepare(sql) {
      return { sql, bind(...values) { this.values = values; return this; } };
    },
    async batch(statements) { batchSize = statements.length; return []; }
  };
  const request = new Request("https://example.test/mcp/read-secret", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      jsonrpc: "2.0", id: 13, method: "tools/call",
      params: {
        name: "save_recommendation_mix",
        arguments: {
          name: "오늘의 믹스",
          summary: "청취 기록 기반 추천",
          items: [
            { title: "Song A", artist: "Artist A", reason: "완주율이 높은 곡과 비슷함" },
            { title: "Song B", artist: "Artist B", youtubeUrl: "https://music.youtube.com/watch?v=test" }
          ]
        }
      }
    })
  });
  const body = await (await worker.fetch(request, { ...env, DB: fakeDb })).json();
  assert.equal(body.result.structuredContent.savedCount, 2);
  assert.equal(batchSize, 3);
});

test("sync accepts a valid offline batch and prepares both writes", async () => {
  const prepared = [];
  const fakeDb = {
    prepare(sql) {
      return { bind(...values) { const statement = { sql, values }; prepared.push(statement); return statement; } };
    },
    async batch(statements) { assert.equal(statements.length, 2); return []; }
  };
  const request = new Request("https://example.test/v1/sync", {
    method: "POST",
    headers: { authorization: "Bearer ingest-secret", "content-type": "application/json" },
    body: JSON.stringify({
      schemaVersion: 1,
      listens: [{
        id: "listen-1", trackKey: "song|artist|120000", mediaId: null,
        title: "Song", artist: "Artist", album: null, startedAt: 1,
        endedAt: 121000, listenedMs: 120000, durationMs: 120000,
        lastPositionMs: 120000, completed: true, deviceId: "phone"
      }],
      events: [{ clientId: 1, listenId: "listen-1", type: "complete", occurredAt: 121000, positionMs: 120000, payload: null }]
    })
  });
  const response = await worker.fetch(request, { ...env, DB: fakeDb });
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.acceptedListens, 1);
  assert.equal(body.acceptedEvents, 1);
  assert.equal(prepared.length, 2);
});
