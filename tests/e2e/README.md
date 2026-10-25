# Mock + local recording browser regression

The suite exercises the live web and API processes with Chrome already installed on the host. It never calls a speech or summary provider.

`recording.spec.ts` uses Chrome's synthetic microphone device, never the host microphone. It verifies playable stored audio and its SHA-256, failed-upload recovery through IndexedDB after reload, and exclusive recording across tabs. The runner uses one worker because the product intentionally permits one active meeting. Use a synthetic test database/recording directory.

Feature tests configure the fixed synthetic owner account (`owner` / `synthetic-owner-password-2026`) once when needed, then share a worker-scoped authenticated cookie to avoid unnecessary login requests; each test retains its own browser context and IndexedDB. `phase0.spec.ts` independently tests credential login/logout, remembered sessions, and rejection of legacy token login after setup. An immediate repeat of the whole suite can encounter the production login limit (10/minute). Wait for its minute window before repeating; the limit is intentionally not disabled in E2E.

Start PostgreSQL 17.11 and the API on `http://localhost:8080` with `APP_OWNER_TOKEN=phase0-e2e-owner-token-000000000000`, `APP_ALLOWED_ORIGINS=http://localhost:5173`, `APP_FAKE_EVENT_DELAY_MS=500`, and `PAID_API_TESTS_ENABLED=false`. Start the Vite app on `http://localhost:5173`, then run:

```powershell
cd tests/e2e
npm ci
$env:E2E_OWNER_TOKEN='phase0-e2e-owner-token-000000000000'
npm test
```

Override `E2E_BASE_URL` or `E2E_CHROME_EXECUTABLE` when the local ports or Chrome path differ.

The `owner-setup` project runs first on a fresh test database and checks account registration through the browser, including password confirmation. It skips registration when an owner already exists; the dependent `regression` project still runs. Use Vite preview for these tests so the development server's HMR WebSocket is not counted as application STT traffic:

```powershell
cd apps/web
npm run build
.\node_modules\.bin\vite.cmd preview --host 127.0.0.1 --port 5173 --strictPort
```

## Opt-in sustained recording

Use the same synthetic database and running local processes. This separate configuration never runs as part of `npm test`. Set an integer duration from 1 to 240 minutes explicitly:

```powershell
$env:OFFLINE_STRESS_MINUTES='15'
.\node_modules\.bin\playwright.cmd test --config stress.config.ts
```

Chrome's fake microphone records for the requested duration using the browser's monotonic clock, matching recording metadata. The test interrupts chunk uploads briefly, keeps recording, stops and reloads, checks the completed file's SHA-256/bytes/chunk count/duration and playback, and confirms that no transcript or STT WebSocket was created. Metadata checkpoints and both browser elapsed and diagnostic wall-clock times are attached to `stress-report` before final assertions; audio stays in the synthetic recording root. Failed tests stop their own meeting and retain recoverable files. The test does not simulate host sleep, storage exhaustion or external STT reconnection. A short run does not prove a longer duration or lossless audio quality. Tracing is disabled to avoid collecting hours of network payloads.

## Opt-in browser storage and API restart fault tests

These tests own a UUID-named PostgreSQL database, temporary recording root, API process on port 18082, and Vite preview on port 5182. They configure and use the same fixed synthetic owner credentials as the main suite. The restart scenario kills only the API process it started, keeps synthetic microphone recording during the outage, verifies that the database-backed session survives the restart, and checks recovery without logging in again. The quota scenario uses Chrome DevTools Protocol to lower only the test origin's browser storage quota after one server-acknowledged chunk, verifies enforcement with a real IndexedDB transaction, and then checks safe automatic microphone stop, recovery, and completion. Both scenarios check hash, playback, empty IndexedDB state, and absence of STT traffic. Paid providers stay disabled.

```powershell
cd tests/e2e
$env:OFFLINE_RESTART_TEST_ENABLED='true'
.\node_modules\.bin\playwright.cmd test --config restart.config.ts
```

The restart command leaves the browser quota scenario skipped unless its additional opt-in is set. To run both fault scenarios:

```powershell
$env:OFFLINE_RESTART_TEST_ENABLED='true'
$env:OFFLINE_BROWSER_QUOTA_TEST_ENABLED='true'
.\node_modules\.bin\playwright.cmd test --config restart.config.ts
```

PostgreSQL must already listen on `127.0.0.1:55432` for the local `ai_meeting` role. Google Chrome and effective CDP `Storage.overrideQuotaForOrigin` enforcement are required. If Chrome reports the override but still accepts the capability probe, the scenario is explicitly skipped with that limitation and does not claim quota recovery passed. The test refuses ambiguous cleanup targets and only drops the database and recording directory it created. The quota scenario targets Chrome's origin quota policy, not physical host disk exhaustion. Host sleep, physical disk exhaustion, browser crashes, and external STT reconnection remain separate validation gaps.
