"""Opt-in real PostgreSQL round-trip test.

Run only with AI_MEETING_OPS_PG_TEST=1. Every database name and filesystem root
is randomly generated and the test refuses the application database name.
"""

import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
import urllib.error
from uuid import uuid4


@unittest.skipUnless(os.getenv("AI_MEETING_OPS_PG_TEST") == "1", "set AI_MEETING_OPS_PG_TEST=1")
class PostgreSqlRoundTripTest(unittest.TestCase):
    def test_synthetic_round_trip(self):
        repo = Path(__file__).parents[2]
        pg_bin = Path(os.getenv("AI_MEETING_OPS_PG_BIN", repo / ".tools/postgres/pgsql/bin"))
        host = os.getenv("AI_MEETING_OPS_PG_HOST", "127.0.0.1")
        port = os.getenv("AI_MEETING_OPS_PG_PORT", "55432")
        user = os.getenv("AI_MEETING_OPS_PG_USER", "ai_meeting")
        maintenance = os.getenv("AI_MEETING_OPS_PG_MAINTENANCE_DB", "postgres")
        jar = Path(os.getenv("AI_MEETING_OPS_API_JAR", repo / "apps/api/target/api-0.0.1-SNAPSHOT.jar"))
        token = uuid4().hex[:12]
        source_db = f"ops_source_{token}"
        target_db = f"ops_target_{token}"
        self.assertNotIn("ai_meeting", (source_db, target_db))
        exe = lambda name: str(pg_bin / (name + (".exe" if os.name == "nt" else "")))
        connection = ["--host", host, "--port", port, "--username", user]

        def run(command, sql=None):
            result = subprocess.run(command, input=sql, text=True, check=False, capture_output=True)
            if result.returncode:
                self.fail(f"command failed ({result.returncode}): {command}\nstdout={result.stdout}\nstderr={result.stderr}")

        def start_api(database, recording_root, port_number):
            environment = os.environ.copy()
            environment.update({
                "DATABASE_URL": f"jdbc:postgresql://{host}:{port}/{database}",
                "DATABASE_USER": user,
                "DATABASE_PASSWORD": os.getenv("PGPASSWORD", ""),
                "APP_OWNER_TOKEN": "ops-test-owner-token-000000000000",
                "APP_ALLOWED_ORIGINS": f"http://127.0.0.1:{port_number}",
                "APP_RECORDING_ROOT": str(recording_root),
                "SERVER_PORT": str(port_number),
                "SERVER_ADDRESS": "127.0.0.1",
                "SERVER_FORWARD_HEADERS_STRATEGY": "none",
                "PAID_API_TESTS_ENABLED": "false",
            })
            log = tempfile.TemporaryFile(mode="w+", encoding="utf-8")
            process = subprocess.Popen(["java", "-jar", str(jar)], env=environment,
                                       stdout=log, stderr=subprocess.STDOUT, text=True)
            process._ops_log = log

            def process_log():
                log.flush()
                log.seek(0)
                return log.read()[-4000:]

            deadline = time.time() + 90
            while time.time() < deadline:
                if process.poll() is not None:
                    output = process_log()
                    log.close()
                    self.fail(f"owned API process exited during startup: {output}")
                try:
                    with urllib.request.urlopen(f"http://127.0.0.1:{port_number}/actuator/health", timeout=1) as response:
                        if response.status == 200:
                            return process
                except Exception:
                    time.sleep(0.25)
            process.terminate()
            process.wait(timeout=10)
            output = process_log()
            log.close()
            self.fail(f"owned API process did not become healthy: {output}")

        def stop_api(process):
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            process._ops_log.close()

        def free_port():
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                return listener.getsockname()[1]

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            source_root = base / "source-recordings"
            target_root = base / "restored-recordings"
            bundle = base / "bundle"
            source_root.mkdir()
            meeting_completed = str(uuid4())
            meeting_partial = str(uuid4())
            meeting_empty = str(uuid4())
            completed = b"chunk-onechunk-two"
            chunks = [b"chunk-one", b"chunk-two"]
            owner_username = "ops-owner"
            owner_password = "synthetic-ops-owner-password"
            password_salt = b"synthetic-ops-password-salt"
            password_hash = hashlib.pbkdf2_hmac("sha256", owner_password.encode(), password_salt, 600_000, dklen=32)
            raw_session = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
            session_hash = hashlib.sha256(raw_session.encode("ascii")).digest()
            encoded = lambda value: base64.b64encode(value).decode("ascii")
            for meeting, files in ((meeting_completed, {"chunk-00000000.bin": chunks[0], "chunk-00000001.bin": chunks[1], "recording.bin": completed}),
                                   (meeting_partial, {"chunk-00000000.bin": b"partial"})):
                directory = source_root / meeting
                directory.mkdir()
                for name, content in files.items():
                    (directory / name).write_bytes(content)
            try:
                run([exe("createdb"), *connection, source_db])
                source_api = start_api(source_db, source_root, free_port())
                stop_api(source_api)
                rec1, rec2 = str(uuid4()), str(uuid4())
                seg, summary = str(uuid4()), str(uuid4())
                settled_usage, uncertain_usage, deleted_meeting = str(uuid4()), str(uuid4()), str(uuid4())
                billed_uncertain_usage = str(uuid4())
                q = lambda value: "'" + value.replace("'", "''") + "'"
                inserts = f"""
INSERT INTO meetings(id,title,status,input_source,revision) VALUES
 ('{meeting_completed}','completed','STOPPED','LOCAL_RECORDING',0),
 ('{meeting_partial}','partial','STOPPED','LOCAL_RECORDING',0),
 ('{meeting_empty}','empty','STOPPED','MOCK',0);
INSERT INTO recordings(id,meeting_id,state,path,chunk_count,bytes,duration_ms,sha256,mime_type) VALUES
 ('{rec1}','{meeting_completed}','COMPLETED','recording.bin',2,{len(completed)},20,'{hashlib.sha256(completed).hexdigest()}','audio/webm'),
 ('{rec2}','{meeting_partial}','UPLOADING','recording.bin',0,NULL,NULL,NULL,NULL);
INSERT INTO recording_chunks(recording_id,sequence_no,start_ms,end_ms,sha256,mime_type,bytes,path) VALUES
 ('{rec1}',0,0,10,'{hashlib.sha256(chunks[0]).hexdigest()}','audio/webm',{len(chunks[0])},{q(str(source_root / meeting_completed / 'chunk-00000000.bin'))}),
 ('{rec1}',1,10,20,'{hashlib.sha256(chunks[1]).hexdigest()}','audio/webm',{len(chunks[1])},{q(str(source_root / meeting_completed / 'chunk-00000001.bin'))}),
 ('{rec2}',0,0,10,'{hashlib.sha256(b'partial').hexdigest()}','audio/webm',7,{q(str(source_root / meeting_partial / 'chunk-00000000.bin'))});
INSERT INTO transcript_segments(id,meeting_id,provider_event_id,sequence_no,start_ms,end_ms,original_text,display_text,transcript_status,speaker_status)
 VALUES('{seg}','{meeting_completed}','ops-event',0,0,10,'original','display','FINAL','UNKNOWN');
INSERT INTO owner_account(id,username,password_salt,password_hash)
 VALUES(1,{q(owner_username)},{q(encoded(password_salt))},{q(encoded(password_hash))});
INSERT INTO auth_sessions(id_hash,expires_at)
 VALUES({q(encoded(session_hash))},CURRENT_TIMESTAMP + INTERVAL '30 days');
UPDATE app_settings SET monthly_budget_usd=25 WHERE id=1;
INSERT INTO meeting_summaries(id,meeting_id,version,provider,content_json,snapshot_json)
 VALUES('{summary}','{meeting_completed}',1,'MOCK','{{"summary":"kept","sourceSegmentIds":["{seg}"],"decisions":[],"actionItems":[],"unresolved":[]}}','[{{"segmentId":"{seg}","revision":0}}]');
INSERT INTO meetings(id,title,status,input_source,revision)
 VALUES('{deleted_meeting}','synthetic deleted expense','STOPPED','MOCK',0);
INSERT INTO ai_usage_records(id,meeting_id,provider,model,operation,price_version,state,reservation_month,
 reserved_usd,estimated_usd,maximum_audio_seconds,measured_audio_seconds,input_tokens,output_tokens,created_at,updated_at)
 VALUES('{settled_usage}','{meeting_completed}','synthetic','fixture','SUMMARY','fixture-v1','SETTLED','2026-10-01',
 2,1.234567,120,60,100,5,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO ai_usage_records(id,meeting_id,provider,model,operation,price_version,state,reservation_month,
 reserved_usd,maximum_audio_seconds,created_at,updated_at)
 VALUES('{uncertain_usage}','{deleted_meeting}','synthetic','fixture','SUMMARY','fixture-v1','UNCERTAIN','2026-10-01',
 3,120,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO ai_usage_records(id,meeting_id,provider,model,operation,price_version,state,reservation_month,
 reserved_usd,actual_usd,actual_source,maximum_audio_seconds,created_at,updated_at)
 VALUES('{billed_uncertain_usage}','{deleted_meeting}','synthetic','fixture','SUMMARY','fixture-v1','UNCERTAIN','2026-10-01',
 1,1.500001,'synthetic invoice',120,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
DELETE FROM meetings WHERE id='{deleted_meeting}';
"""
                run([exe("psql"), *connection, "--dbname", source_db, "--set", "ON_ERROR_STOP=1"], inserts)
                program = [sys.executable, str(repo / "scripts/backup_restore.py")]
                common = ["--pg-bin", str(pg_bin), "--host", host, "--port", port, "--user", user, "--api-stopped"]
                run([*program, "backup", *common, "--source-db", source_db, "--source-root", str(source_root), "--bundle", str(bundle)])
                run([*program, "restore", *common, "--maintenance-db", maintenance, "--target-db", target_db, "--target-root", str(target_root), "--bundle", str(bundle)])
                restored_sessions = subprocess.run(
                    [exe("psql"), *connection, "--dbname", target_db, "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
                     "--tuples-only", "--no-align", "--command", "SELECT COUNT(*) FROM auth_sessions"],
                    text=True, check=True, capture_output=True)
                self.assertEqual(restored_sessions.stdout.strip(), "0", "Restore must invalidate existing sessions")
                restored_port = free_port()
                restored_api = start_api(target_db, target_root, restored_port)
                try:
                    former_session = urllib.request.Request(
                        f"http://127.0.0.1:{restored_port}/api/v1/session",
                        headers={"Cookie": f"meeting_session={raw_session}"})
                    with self.assertRaises(urllib.error.HTTPError) as rejected:
                        urllib.request.urlopen(former_session, timeout=5)
                    self.assertEqual(rejected.exception.code, 401)

                    login = urllib.request.Request(
                        f"http://127.0.0.1:{restored_port}/api/v1/session",
                        data=json.dumps({"username": owner_username, "password": owner_password,
                                         "rememberMe": False}).encode("utf-8"),
                        headers={"Content-Type": "application/json",
                                 "Origin": f"http://127.0.0.1:{restored_port}"},
                        method="POST")
                    with urllib.request.urlopen(login, timeout=10) as response:
                        self.assertEqual(response.status, 200)
                        self.assertIn("meeting_session=", response.headers.get("Set-Cookie", ""))
                finally:
                    stop_api(restored_api)
                check = f"""
SELECT (SELECT monthly_budget_usd FROM app_settings WHERE id=1)::text,
       (SELECT COUNT(*) FROM meeting_summaries)::text,
       (SELECT COUNT(*) FROM meetings)::text,
       (SELECT COUNT(*) FROM recording_chunks WHERE path LIKE {q(target_root.resolve().as_posix() + '/%')})::text,
       (SELECT COUNT(*) FROM pg_constraint WHERE contype='f' AND convalidated)::text,
       (SELECT username || ':' || password_salt || ':' || password_hash FROM owner_account WHERE id=1)::text,
       (SELECT COUNT(*) FROM auth_sessions)::text;
"""
                verified = subprocess.run([exe("psql"), *connection, "--dbname", target_db, "--set", "ON_ERROR_STOP=1",
                                           "--tuples-only", "--no-align", "--field-separator", "\t"],
                                          input=check, text=True, check=True, capture_output=True)
                values = verified.stdout.strip().split("\t")
                self.assertEqual(values[:4], ["25.00", "1", "3", "3"])
                self.assertGreater(int(values[4]), 0)
                self.assertEqual(values[5], f"{owner_username}:{encoded(password_salt)}:{encoded(password_hash)}")
                self.assertEqual(values[6], "1", "Credential login must create one new restored-database session")
                source_sessions = subprocess.run(
                    [exe("psql"), *connection, "--dbname", source_db, "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
                     "--tuples-only", "--no-align", "--command", "SELECT COUNT(*) FROM auth_sessions"],
                    text=True, check=True, capture_output=True)
                self.assertEqual(source_sessions.stdout.strip(), "1", "Restore must not mutate the source database")
                ledger_sql = "SELECT row_to_json(u)::text FROM ai_usage_records u ORDER BY id"
                ledger_rows = []
                for database in (source_db, target_db):
                    result = subprocess.run([exe("psql"), *connection, "--dbname", database, "--no-psqlrc",
                                             "--set", "ON_ERROR_STOP=1", "--tuples-only", "--no-align", "--command", ledger_sql],
                                            text=True, check=True, capture_output=True)
                    ledger_rows.append(result.stdout.strip().splitlines())
                self.assertEqual(len(ledger_rows[0]), 3)
                self.assertEqual(ledger_rows[0], ledger_rows[1], "All ledger fields must survive the round trip")
                failed = subprocess.run([exe("psql"), *connection, "--dbname", target_db, "--set", "ON_ERROR_STOP=1"],
                                        input="BEGIN; UPDATE transcript_segments SET original_text='changed'; ROLLBACK;",
                                        text=True, capture_output=True)
                self.assertNotEqual(failed.returncode, 0)
                self.assertIn("immutable", failed.stderr)
                self.assertEqual((target_root / meeting_completed / "recording.bin").read_bytes(), completed)
            finally:
                for database in (target_db, source_db):
                    subprocess.run([exe("dropdb"), *connection, "--if-exists", database], check=False, capture_output=True)


if __name__ == "__main__":
    unittest.main()
