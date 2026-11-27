# Local operations and POC tools

## Windows private deployment

Use Docker Desktop's WSL 2 Linux backend with NVIDIA GPU support and sign in to Tailscale. Install Node.js and the Codex CLI, and run `codex login` with ChatGPT. For regular use, run `start-meeting.bat` from the repository root. The equivalent PowerShell command starts AI mode and opens a new meeting.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-private.ps1 -EnableAi -OpenMeeting
```

The script checks both services, detects the MagicDNS hostname, creates `.env` with random secrets only when absent, starts Compose, checks that the local API rejects unauthenticated access, and configures private Tailscale Serve. If Tailscale prompts you to enable HTTPS/Serve or approve a configuration change, follow its prompt and rerun the launcher.

To start the private demo and microphone recording without AI, run the following command from the repository root. This mode disables paid APIs. Both modes retain existing `.env` files and Docker data.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-private.ps1
```

The launcher loads Whisper large-v3 on the local GPU and waits for the model readiness check. On first start the model downloads into a Docker cache volume. The launcher starts an authenticated local Codex bridge for summaries, corrections, and glossary candidates using the ChatGPT login. Transcripts sent for generation use the Codex subscription allowance. The private bridge listens on port 8767 for Docker access.

The Usage page shows account-wide Codex remaining percentages and reset times, along with recording retention and transcription duration settings. Requests for account limits are cached for 30 seconds and do not generate a model response.

On another tailnet device, open the printed HTTPS address, sign in, and check microphone access. Use `APP_OWNER_TOKEN` from the local `.env` once to register the owner username/password, then sign in with those credentials. Remembered sessions last up to 30 days across API restarts; do not paste credentials into chat. Docker uses its own database/recording volumes; it does not import the development database. If Codex reports access denied on Docker/Tailscale named pipes, run the command outside the restricted agent session. Isolated mock checks:

```powershell
python -m unittest discover -s tests/ops -p test_start_private.py -v
```

## Bounded provider POC

These are optional diagnostic tools. ADR-021 prioritizes wiring real AI into the web application and validating that same path; a separate paid POC is no longer a prerequisite. Do not repeat paid runs unless they resolve a specific issue within the approved total budget.

These standalone commands do not start Spring or enable AI in the application. They default to **dry run**: validate input, prices, reservation and output, without reading a provider key or making a network request. Build the JAR first (`apps/api`: `./mvnw package` or `.\mvnw.cmd package`). Output parents must exist and output files must be new.

From the repository root, an OpenAI preflight using the committed fictional transcript:

```powershell
powershell.exe -NoProfile -File scripts/run-openai-poc.ps1 --input examples/openai-poc-synthetic.json --output .tools/openai-preflight.json --timeout-seconds 30 --output-token-cap 1000 --max-usd 0.05 --input-usd-per-million 2 --output-usd-per-million 10 --price-version official-2026-11-27
```

Deepgram requires approved fictional speech encoded as **raw signed little-endian PCM16, 16 kHz, mono**, with `.pcm`/`.raw` extension. A downloaded WebM/Ogg or a WAV container cannot be renamed to PCM. The following is also only preflight; the inclusive rate uses the regular-price scenario in [cost policy](../docs/09-COST.md).

```powershell
powershell.exe -NoProfile -File scripts/run-deepgram-poc.ps1 --input C:\private\synthetic.pcm --output .tools/deepgram-preflight.json --duration-seconds 300 --max-usd 0.06 --usd-per-minute 0.011 --price-version regular-2026-10-21
```

Actual execution additionally requires `--execute`, `--synthetic-approved` and process `PAID_API_TESTS_ENABLED=true`, plus the matching process environment key (`OPENAI_API_KEY` or `DEEPGRAM_API_KEY`). Do not put keys in arguments or reports. Deepgram comparisons can add `--diarization` and repeated `--keyterm VALUE` with `--keyterms-approved`; explicitly supply a rate that includes the selected options. Recheck official rates before any paid run.

Limits and reservations apply to one invocation, separately from the application DB's monthly ledger. Multiple runs share the operator-approved total budget; do not treat each invocation as a fresh allowance. Reports distinguish estimates from unknown supplier charges and never declare GO. Deepgram receive timestamps are not automatically end-to-end caption latency or CER/DER: use reference audio and human alignment as described in [integrated validation](../docs/07-TEST-AND-EVAL.md#실제-ai-통합-검증). Previous provider connectivity checks in the agent environment failed on port 443; a successful dry run does not establish current provider connectivity.

## Backup and restore

`backup_restore.py` creates a plain directory bundle containing a PostgreSQL custom dump, validated recording files, and a hash manifest. It uses only Python's standard library and PostgreSQL command-line tools.

Stop the API for the entire operation. The tool verifies that no meeting is `STARTED` and that the source database has no other sessions, but `--api-stopped` is an operator assertion, not a process-control feature. Supply passwords through existing libpq mechanisms such as `PGPASSWORD` or a password file; the tool never accepts or prints a password.

```powershell
$pg = 'C:\portfolio\ai-meeting-codex\.tools\postgres\pgsql\bin'
python scripts/backup_restore.py backup --api-stopped --pg-bin $pg --host 127.0.0.1 --port 55432 --user ai_meeting --source-db ai_meeting --source-root C:\private\recordings --bundle C:\private\backups\meeting-2026-10-17

python scripts/backup_restore.py restore --api-stopped --pg-bin $pg --host 127.0.0.1 --port 55432 --user ai_meeting --maintenance-db postgres --target-db ai_meeting_restored --target-root C:\private\recordings-restored --bundle C:\private\backups\meeting-2026-10-17
```

Backup refuses existing output, links/reparse points, temporary/quarantine content, unknown filenames, active meetings, concurrent database sessions, and any mismatch between PostgreSQL recording metadata and the supplied root. The manifest excludes absolute roots, credentials, owner tokens, and content excerpts. The custom dump still contains the complete database, including transcripts and prior absolute chunk paths, and the bundle contains audio; store the whole bundle as sensitive data.

Restore accepts only a bundle produced and retained by a trusted operator. The manifest hashes detect corruption but are not a signature, and a PostgreSQL dump can contain executable SQL. Before database mutation the tool validates the bundle's exact file layout and every manifest hash. The target database and recording root must both be absent. Restore creates a new database, restores the full schema/data/Flyway history, keeps the owner account but clears all restored login sessions, creates a new recording root, rewrites absolute chunk paths for that root, and verifies chunk/completed-audio metadata, foreign-key validation, and the immutable-original trigger. Sign in again after restore. A failure never drops an existing database or overwrites a root; newly created diagnostic artifacts may remain and are named in the error output.

Run unit checks with:

```powershell
python -m unittest discover -s tests/ops -p 'test_backup_restore.py' -v
```

The real PostgreSQL test is opt-in and uses random new databases plus temporary roots. It refuses the application database name:

```powershell
$env:AI_MEETING_OPS_PG_TEST='1'
$env:AI_MEETING_OPS_PG_BIN='C:\portfolio\ai-meeting-codex\.tools\postgres\pgsql\bin'
$env:AI_MEETING_OPS_PG_PORT='55432'
$env:AI_MEETING_OPS_PG_USER='ai_meeting'
python -m unittest discover -s tests/ops -p 'test_backup_restore_pg.py' -v
```

## Offline POC evaluation

`evaluate_poc.py` scores a human-aligned JSON export without capturing audio or calling a provider. It reports NFC character error rate (CER), whitespace-token word error rate (WER), explicitly annotated keyterm occurrence precision/recall, aligned speaker-label disagreement and coverage, and nearest-rank p50/p95 over latency samples supplied by the caller. CER is case-sensitive and includes whitespace and punctuation; WER is case-sensitive and keeps punctuation attached. Each term annotation supplies reference, predicted, and human-matched occurrence counts, so the tool never infers keyterms from transcript text. Speaker labels must first be human-mapped into one shared label namespace; the reported disagreement is not DER.

The synthetic example demonstrates the input contract only. It is not evidence from approved audio or a real provider:

```powershell
python scripts/evaluate_poc.py examples/poc-evaluation-synthetic.json
python scripts/evaluate_poc.py examples/poc-evaluation-synthetic.json --output C:\private\poc-evaluation.json
python -m unittest discover -s tests/evaluation -p 'test_*.py' -v
```

An output path must have an existing parent and must not already exist. For a future real POC, export stable aligned segment IDs, human reference and provider hypothesis text, explicit term occurrence and matched counts, optional aligned speaker labels, and separately measured non-negative latency samples; set `measurementOrigin` to `human-aligned-poc`.

## Monthly cost scenarios

`estimate_cost.py` calculates 36/48/60-hour scenarios from explicit USD rates and hourly summary/correction token assumptions. It makes no network requests and does not enforce the application's budget. The example uses synthetic prices, not current provider prices. Set verified rates, a price version/date, and optionally `budgetUsd` before using it for planning.

```powershell
python scripts/estimate_cost.py examples/cost-scenario-assumptions.json
```

Results include the input rates, STT/LLM subtotals, budget excess, and affordable hours. Costs round up to six decimal places; remaining budget and affordable hours round down. Inputs must be finite and nonnegative, at most 1,000,000,000 with at most 12 decimal places. The evaluation test command above covers both tools.

## 회의 시작 바로가기

서버 PC에서 루트의 `start-meeting.bat`을 더블클릭한다. `start-private.ps1 -EnableAi -OpenMeeting`이 로컬 Whisper와 Codex bridge를 시작하고 비공개 HTTPS 새 회의 화면을 연다. 브라우저에 로그인한 뒤 로컬 Whisper 전사 또는 녹음 파일 업로드를 선택한다. 배포가 컨테이너를 교체할 수 있으므로 실행 전 진행 중인 녹음을 종료한다.
