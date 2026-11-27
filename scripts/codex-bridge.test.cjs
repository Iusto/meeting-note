const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
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
    const health = await fetch(`${url}/health`, { headers });
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { status: 'UP', provider: 'CODEX', model: 'gpt-6.2-sol', busy: false });
    const first = fetch(`${url}/generate`, { method: 'POST', headers, body: '{}' });
    while (!release) await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal((await (await fetch(`${url}/health`, { headers })).json()).busy, true);
    const second = await fetch(`${url}/generate`, { method: 'POST', headers, body: '{}' });
    assert.equal(second.status, 429);
    assert.equal((await second.json()).code, 'AI_BUSY');
    release({ outputJson: '{"summary":"synthetic"}' });
    assert.equal((await first).status, 200);
    assert.equal(calls, 1);
    assert.equal((await fetch(`${url}/generate`, { method: 'POST', headers, body: 'invalid' })).status, 400);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

test('bridge runs GPT-6.2 Sol for requests from both API image versions', async (t) => {
  const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'meeting-bridge-test-'));
  const cli = path.join(scratch, 'fake-codex.cjs');
  t.after(async () => { await fs.rm(cli, { force: true }); await fs.rmdir(scratch); });
  await fs.writeFile(cli, `
    const fs = require('node:fs');
    const args = process.argv.slice(2);
    if (args[0] === 'login') {
      console.log('Logged in using ChatGPT');
    } else {
      const model = args[args.indexOf('--model') + 1];
      fs.writeFileSync(args[args.indexOf('--output-last-message') + 1], JSON.stringify({ model }));
    }
  `);
  const server = createBridge({ token: 'test-token', cli });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  const url = `http://127.0.0.1:${server.address().port}/generate`;
  const headers = { Authorization: 'Bearer test-token' };
  for (const model of ['gpt-6.2-sol', 'gpt-6.1-sol']) {
    const body = JSON.stringify({ model, instructions: 'Summarize.', input: [], text: { format: { schema: {} } } });
    const response = await fetch(url, { method: 'POST', headers, body });
    const result = await response.json();
    assert.equal(response.status, 200, result.code);
    assert.deepEqual(JSON.parse(result.outputJson), { model: 'gpt-6.2-sol' });
  }
});

test('Codex child environment strips API credentials and bridge secrets', () => {
  process.env.CODEX_API_KEY = 'test-api'; process.env.OPENAI_API_KEY = 'test-openai';
  process.env.DEEPGRAM_API_KEY = 'test-speech'; process.env.CODEX_BRIDGE_TOKEN = 'test-bridge';
  const env = childEnvironment();
  for (const name of ['CODEX_API_KEY', 'OPENAI_API_KEY', 'DEEPGRAM_API_KEY', 'CODEX_BRIDGE_TOKEN']) assert.equal(env[name], undefined);
});
