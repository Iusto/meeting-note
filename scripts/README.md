# Offline tools

## Windows private deployment

After starting Docker Desktop's Linux engine and signing in to Tailscale, run from a normal Windows PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\portfolio\ai-meeting-codex\scripts\start-private.ps1
```

The script checks both services, detects the MagicDNS hostname, creates `.env` with random secrets only when absent, builds/starts Compose, checks that the local API rejects unauthenticated access, and configures private Tailscale Serve. Existing `.env` files and Docker data are retained. Paid APIs are forced off for startup even if the shell or `.env` enables them; inherited shell settings are restored afterward. Provider keys are not used. First builds download container images and dependencies. If Tailscale asks to enable HTTPS/Serve, complete its displayed link and rerun. Existing Serve configuration may require an interactive confirmation; the script does not pass `--yes` or reset it.

The printed HTTPS address still needs login/microphone verification from another device on the tailnet. Read `APP_OWNER_TOKEN` from the local `.env` to log in; do not paste it into chat. Docker uses its own database/recording volumes; it does not import the development database. If Codex reports access denied on Docker/Tailscale named pipes, run the command outside the restricted agent session. Isolated mock checks:

```powershell
python -m unittest discover -s tests/ops -p test_start_private.py -v
```

## Backup and restore

`backup_restore.py` creates a plain directory bundle containing a PostgreSQL custom dump, validated recording files, and a hash manifest. It uses only Python's standard library and PostgreSQL command-line tools.

Stop the API for the entire operation. The tool verifies that no meeting is `STARTED` and that the source database has no other sessions, but `--api-stopped` is an operator assertion, not a process-control feature. Supply passwords through existing libpq mechanisms such as `PGPASSWORD` or a password file; the tool never accepts or prints a password.

```powershell
$pg = 'C:\portfolio\ai-meeting-codex\.tools\postgres\pgsql\bin'
python scripts/backup_restore.py backup --api-stopped --pg-bin $pg --host 127.0.0.1 --port 55432 --user ai_meeting --source-db ai_meeting --source-root C:\private\recordings --bundle C:\private\backups\meeting-2026-10-17

python scripts/backup_restore.py restore --api-stopped --pg-bin $pg --host 127.0.0.1 --port 55432 --user ai_meeting --maintenance-db postgres --target-db ai_meeting_restored --target-root C:\private\recordings-restored --bundle C:\private\backups\meeting-2026-10-17
```

Backup refuses existing output, links/reparse points, temporary/quarantine content, unknown filenames, active meetings, concurrent database sessions, and any mismatch between PostgreSQL recording metadata and the supplied root. The manifest excludes absolute roots, credentials, owner tokens, and content excerpts. The custom dump still contains the complete database, including transcripts and prior absolute chunk paths, and the bundle contains audio; store the whole bundle as sensitive data.

Restore accepts only a bundle produced and retained by a trusted operator. The manifest hashes detect corruption but are not a signature, and a PostgreSQL dump can contain executable SQL. Before database mutation the tool validates the bundle's exact file layout and every manifest hash. The target database and recording root must both be absent. Restore creates a new database, restores the full schema/data/Flyway history, creates a new recording root, rewrites absolute chunk paths for that root, and verifies chunk/completed-audio metadata, foreign-key validation, and the immutable-original trigger. A failure never drops an existing database or overwrites a root; newly created diagnostic artifacts may remain and are named in the error output.

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
