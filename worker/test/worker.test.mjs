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

test("MCP lists six read-only analytics tools", async () => {
  const request = new Request("https://example.test/mcp", {
    method: "POST",
    headers: { authorization: "Bearer read-secret", "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} })
  });
  const body = await (await worker.fetch(request, env)).json();
  assert.equal(body.result.tools.length, 6);
  assert.ok(body.result.tools.every((tool) => tool.inputSchema.type === "object"));
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
