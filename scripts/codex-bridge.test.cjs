const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createBridge, childEnvironment } = require('./codex-bridge.cjs');
const { normalizeUsage } = require('./codex-usage.cjs');

test('Usage preserves unknown windows and account restrictions without exposing account identity', () => {
  const result = normalizeUsage({ accountId: 'private', ordinaryUsageAllowed: false, rateLimitsByLimitId: {
    codex: { primary: { usedPercent: 23, windowDurationMins: 300, resetsAt: 123 }, secondary: null },
  } });
  assert.equal(result.ordinaryUsageAllowed, false);
  assert.equal(result.limits[0].windows[0].remainingPercent, 77);
  assert.equal(result.limits[0].windows.length, 1);
  assert.equal(result.accountId, undefined);
});

test('bridge requires a token, rejects overlapping jobs and exposes only safe errors', async () => {
  let release;
  let calls = 0;
  const server = createBridge({ token: 'test-token', execute: () => { calls++; return new Promise(resolve => { release = resolve; }); } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const headers = { Authorization: 'Bearer test-token' };
  try {
    assert.equal((await fetch(`${url}/health`)).status, 401);
    assert.equal((await fetch(`${url}/health`, { headers })).status, 200);
    const first = fetch(`${url}/generate`, { method: 'POST', headers, body: '{}' });
    while (!release) await new Promise(resolve => setTimeout(resolve, 10));
    const second = await fetch(`${url}/generate`, { method: 'POST', headers, body: '{}' });
    assert.equal(second.status, 429);
    assert.equal((await second.json()).code, 'AI_BUSY');
    release({ outputJson: '{"summary":"synthetic"}' });
    assert.equal((await first).status, 200);
    assert.equal(calls, 1);
    assert.equal((await fetch(`${url}/generate`, { method: 'POST', headers, body: 'invalid' })).status, 400);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

test('Codex child environment strips API credentials and bridge secrets', () => {
  process.env.CODEX_API_KEY = 'test-api'; process.env.OPENAI_API_KEY = 'test-openai';
  process.env.DEEPGRAM_API_KEY = 'test-speech'; process.env.CODEX_BRIDGE_TOKEN = 'test-bridge';
  const env = childEnvironment();
  for (const name of ['CODEX_API_KEY', 'OPENAI_API_KEY', 'DEEPGRAM_API_KEY', 'CODEX_BRIDGE_TOKEN']) assert.equal(env[name], undefined);
});
