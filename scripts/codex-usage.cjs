const { spawn } = require('node:child_process');
const readline = require('node:readline');

function normalizeUsage(result) {
  const snapshots = result.rateLimitsByLimitId && Object.keys(result.rateLimitsByLimitId).length
    ? Object.entries(result.rateLimitsByLimitId) : [['codex', result.rateLimits]];
  return { checkedAt: new Date().toISOString(), ordinaryUsageAllowed: result.ordinaryUsageAllowed ?? null,
    limits: snapshots.filter(([, value]) => value).map(([id, value]) => ({
      id, name: value.limitName || id, planType: value.planType ?? null,
      spendControlReached: value.spendControlReached ?? null,
      reachedType: value.rateLimitReachedType ?? null,
      windows: [value.primary, value.secondary].filter(Boolean).map(window => ({
        remainingPercent: Math.max(0, Math.min(100, 100 - window.usedPercent)),
        windowMinutes: window.windowDurationMins ?? null, resetsAt: window.resetsAt ?? null,
      })),
    })) };
}

function readUsage(cli, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cli, 'app-server', '-c', 'forced_login_method="chatgpt"'],
      { env, windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
    const lines = readline.createInterface({ input: child.stdout });
    let done = false;
    const finish = (error, result) => {
      if (done) return; done = true; clearTimeout(timer); lines.close(); child.stdin.end(); child.kill();
      if (error) reject(Object.assign(new Error('CODEX_USAGE_UNAVAILABLE'), { code: 'CODEX_USAGE_UNAVAILABLE', status: 503 }));
      else resolve(normalizeUsage(result));
    };
    const timer = setTimeout(() => finish(true), 15000);
    const send = value => child.stdin.write(JSON.stringify(value) + '\n');
    child.stdin.on('error', () => finish(true));
    child.on('error', () => finish(true));
    child.on('exit', () => { if (!done) finish(true); });
    lines.on('line', line => {
      try {
        const message = JSON.parse(line);
        if (message.id === 1) {
          if (message.error) return finish(true);
          send({ method: 'initialized', params: {} });
          send({ id: 2, method: 'account/rateLimits/read', params: {} });
        } else if (message.id === 2) finish(Boolean(message.error), message.result);
      } catch { finish(true); }
    });
    send({ id: 1, method: 'initialize', params: { clientInfo: { name: 'ai_meeting_usage', version: '1.0.0' } } });
  });
}
module.exports = { readUsage, normalizeUsage };
