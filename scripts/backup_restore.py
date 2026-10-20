#!/usr/bin/env python3
"""Offline PostgreSQL and recording backup/restore for AI Meeting.

The API must be stopped for the whole operation. PostgreSQL credentials are
read by libpq (for example PGPASSWORD or a password file) and are never accepted
as command-line arguments or written to the manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from typing import Iterable
from uuid import UUID, uuid4


FORMAT_VERSION = 1
DUMP_NAME = "database.dump"
MANIFEST_NAME = "manifest.json"
RECORDINGS_NAME = "recordings"
CHUNK_RE = re.compile(r"chunk-[0-9]{8}\.bin\Z")
DB_NAME_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,62}\Z")
REPARSE_POINT = 0x400


class OperationError(RuntimeError):
    pass


def is_link_or_reparse(path: Path) -> bool:
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & REPARSE_POINT)


def require_plain_directory(path: Path, label: str) -> Path:
    if not path.exists() or not path.is_dir() or is_link_or_reparse(path):
        raise OperationError(f"{label} must be an existing plain directory: {path}")
    return path.resolve(strict=True)


def sha256_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            digest.update(block)
    return size, digest.hexdigest()


def recording_relative_path(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if path.is_absolute() or len(path.parts) != 2 or str(path) != value:
        raise OperationError(f"unsafe recording manifest path: {value!r}")
    try:
        meeting_id = UUID(path.parts[0])
    except ValueError as error:
        raise OperationError(f"recording path has an invalid meeting UUID: {value!r}") from error
    if str(meeting_id) != path.parts[0]:
        raise OperationError(f"recording path UUID is not canonical: {value!r}")
    if path.parts[1] != "recording.bin" and not CHUNK_RE.fullmatch(path.parts[1]):
        raise OperationError(f"recording path has an unexpected file name: {value!r}")
    return path


def enumerate_recordings(root: Path) -> list[tuple[str, Path]]:
    root = require_plain_directory(root, "recording root")
    found: list[tuple[str, Path]] = []
    for meeting_dir in sorted(root.iterdir(), key=lambda item: item.name):
        if is_link_or_reparse(meeting_dir) or not meeting_dir.is_dir():
            raise OperationError(f"unexpected entry in recording root: {meeting_dir.name}")
        try:
            meeting_id = UUID(meeting_dir.name)
        except ValueError as error:
            raise OperationError(f"unexpected recording directory: {meeting_dir.name}") from error
        if str(meeting_id) != meeting_dir.name:
            raise OperationError(f"recording directory UUID is not canonical: {meeting_dir.name}")
        if meeting_dir.resolve(strict=True).parent != root:
            raise OperationError(f"recording directory escapes its root: {meeting_dir}")
        for item in sorted(meeting_dir.iterdir(), key=lambda child: child.name):
            if is_link_or_reparse(item) or not item.is_file():
                raise OperationError(f"unexpected non-file recording entry: {item}")
            if item.name != "recording.bin" and not CHUNK_RE.fullmatch(item.name):
                raise OperationError(f"unexpected recording file: {item}")
            if item.resolve(strict=True).parent != meeting_dir.resolve(strict=True):
                raise OperationError(f"recording file escapes its meeting directory: {item}")
            found.append((f"{meeting_dir.name}/{item.name}", item))
    return found


def executable(pg_bin: Path, name: str) -> str:
    suffix = ".exe" if os.name == "nt" else ""
    candidate = pg_bin / f"{name}{suffix}"
    if not candidate.is_file():
        raise OperationError(f"PostgreSQL executable was not found: {candidate}")
    return str(candidate)


def run(command: list[str], *, input_text: str | None = None) -> str:
    try:
        completed = subprocess.run(command, input=input_text, text=True, capture_output=True, check=False)
    except OSError as error:
        raise OperationError(f"could not launch {Path(command[0]).name}: {error}") from error
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or f"exit code {completed.returncode}"
        raise OperationError(f"{Path(command[0]).name} failed: {detail}")
    return completed.stdout


def connection_args(args: argparse.Namespace, database: str) -> list[str]:
    return ["--host", args.host, "--port", str(args.port), "--username", args.user, "--dbname", database]


def psql(args: argparse.Namespace, database: str, sql: str, *, tuples: bool = True) -> str:
    command = [executable(args.pg_bin, "psql"), *connection_args(args, database), "--no-psqlrc", "--set", "ON_ERROR_STOP=1"]
    if tuples:
        command.extend(["--tuples-only", "--no-align", "--field-separator", "\t"])
    command.extend(["--command", sql])
    return run(command).strip()


def validate_database_name(name: str) -> str:
    if not DB_NAME_RE.fullmatch(name):
        raise OperationError("database names must be unquoted PostgreSQL identifiers")
    return name


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def ensure_separate_paths(root: Path, bundle: Path) -> None:
    root = root.resolve(strict=True)
    bundle_candidate = bundle.parent.resolve(strict=True) / bundle.name
    if bundle_candidate == root or root in bundle_candidate.parents or bundle_candidate in root.parents:
        raise OperationError("bundle and recording root must not contain one another")


def assert_api_stopped(args: argparse.Namespace) -> None:
    if not args.api_stopped:
        raise OperationError("refusing operation without explicit --api-stopped acknowledgement")


def assert_source_quiescent(args: argparse.Namespace) -> str:
    started = psql(args, args.source_db, "SELECT COUNT(*) FROM meetings WHERE status='STARTED'")
    if started != "0":
        raise OperationError("source database has a STARTED meeting")
    sessions = psql(args, args.source_db,
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()")
    if sessions != "0":
        raise OperationError("source database has other active sessions; stop the API and retry")
    version = psql(args, args.source_db,
                   "SELECT version::text FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                   "ORDER BY installed_rank DESC LIMIT 1")
    if not version:
        raise OperationError("source database has no successful versioned Flyway migration")
    return version


def safe_cleanup_owned(path: Path, parent: Path, prefix: str) -> None:
    parent = parent.resolve(strict=True)
    candidate = path.resolve(strict=False)
    if candidate.parent != parent or not candidate.name.startswith(prefix):
        raise OperationError(f"refusing to clean an unowned path: {candidate}")
    if candidate.exists():
        shutil.rmtree(candidate)


def database_audio_rows(args: argparse.Namespace, database: str) -> tuple[list[list[str]], list[list[str]]]:
    chunks = query_rows(args, database, """
        SELECT r.meeting_id::text,rc.sequence_no::text,rc.bytes::text,rc.sha256,rc.path
          FROM recording_chunks rc JOIN recordings r ON r.id=rc.recording_id
         ORDER BY r.meeting_id,rc.sequence_no
    """, 5)
    completed = query_rows(args, database, """
        SELECT meeting_id::text,bytes::text,sha256 FROM recordings WHERE state='COMPLETED' ORDER BY meeting_id
    """, 3)
    return chunks, completed


def verify_database_audio(args: argparse.Namespace, database: str, root: Path,
                          actual_relative_paths: set[str], require_stored_paths: bool) -> None:
    expected_relative_paths: set[str] = set()
    chunks, completed = database_audio_rows(args, database)
    for meeting_text, sequence_text, byte_text, expected_hash, stored_path in chunks:
        try:
            meeting_id = UUID(meeting_text)
            sequence = int(sequence_text)
            byte_count = int(byte_text)
        except ValueError as error:
            raise OperationError("database contains invalid recording chunk metadata") from error
        if str(meeting_id) != meeting_text or sequence < 0 or byte_count < 0:
            raise OperationError("database contains invalid recording chunk metadata")
        relative = f"{meeting_text}/chunk-{sequence:08d}.bin"
        recording_relative_path(relative)
        expected_relative_paths.add(relative)
        expected = root / meeting_text / f"chunk-{sequence:08d}.bin"
        if require_stored_paths and Path(stored_path).resolve(strict=False) != expected.resolve(strict=False):
            raise OperationError(f"database chunk path does not belong to the supplied recording root: {stored_path}")
        if not expected.is_file() or is_link_or_reparse(expected):
            raise OperationError(f"recording chunk is missing or unsafe: {expected}")
        size, digest = sha256_file(expected)
        if size != byte_count or digest != expected_hash:
            raise OperationError(f"recording chunk metadata mismatch: {expected}")
    for meeting_text, byte_text, expected_hash in completed:
        try:
            meeting_id = UUID(meeting_text)
            byte_count = int(byte_text)
        except ValueError as error:
            raise OperationError("database contains invalid completed recording metadata") from error
        if str(meeting_id) != meeting_text or byte_count < 0:
            raise OperationError("database contains invalid completed recording metadata")
        relative = f"{meeting_text}/recording.bin"
        expected_relative_paths.add(relative)
        audio = root / meeting_text / "recording.bin"
        if not audio.is_file() or is_link_or_reparse(audio):
            raise OperationError(f"completed recording is missing or unsafe: {audio}")
        size, digest = sha256_file(audio)
        if size != byte_count or digest != expected_hash:
            raise OperationError(f"completed recording metadata mismatch: {audio}")
    if actual_relative_paths != expected_relative_paths:
        raise OperationError(
            f"recording root differs from database metadata: {sorted(actual_relative_paths ^ expected_relative_paths)}")


def backup(args: argparse.Namespace) -> None:
    assert_api_stopped(args)
    validate_database_name(args.source_db)
    source_root = require_plain_directory(args.source_root, "source recording root")
    bundle = args.bundle.absolute()
    if bundle.exists():
        raise OperationError(f"bundle already exists: {bundle}")
    if not bundle.parent.is_dir():
        raise OperationError(f"bundle parent does not exist: {bundle.parent}")
    ensure_separate_paths(source_root, bundle)
    schema_version = assert_source_quiescent(args)
    recordings = enumerate_recordings(source_root)
    verify_database_audio(args, args.source_db, source_root, {relative for relative, _ in recordings}, True)

    prefix = f".{bundle.name}.tmp-"
    staging = Path(tempfile.mkdtemp(prefix=prefix, dir=bundle.parent))
    published = False
    try:
        dump = staging / DUMP_NAME
        run([executable(args.pg_bin, "pg_dump"), *connection_args(args, args.source_db),
             "--format=custom", "--no-owner", "--no-acl", "--file", str(dump)])
        dump_size, dump_hash = sha256_file(dump)
        audio_manifest: list[dict[str, object]] = []
        audio_root = staging / RECORDINGS_NAME
        audio_root.mkdir()
        for relative, source in recordings:
            rel = recording_relative_path(relative)
            destination = audio_root.joinpath(*rel.parts)
            destination.parent.mkdir(exist_ok=True)
            shutil.copyfile(source, destination)
            size, digest = sha256_file(destination)
            audio_manifest.append({"path": relative, "size": size, "sha256": digest})
        manifest = {
            "formatVersion": FORMAT_VERSION,
            "schemaVersion": schema_version,
            "createdAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "databaseDump": {"path": DUMP_NAME, "size": dump_size, "sha256": dump_hash},
            "recordings": audio_manifest,
        }
        (staging / MANIFEST_NAME).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        second_version = assert_source_quiescent(args)
        if second_version != schema_version:
            raise OperationError("source schema changed while backup was being created")
        if bundle.exists():
            raise OperationError(f"bundle appeared while backup was running: {bundle}")
        os.rename(staging, bundle)
        published = True
    finally:
        if not published and staging.exists():
            safe_cleanup_owned(staging, bundle.parent, prefix)
    print(f"backup created: {bundle}")


def load_and_validate_bundle(bundle_path: Path) -> tuple[Path, dict[str, object], list[dict[str, object]]]:
    bundle = require_plain_directory(bundle_path, "bundle")
    expected_top = {DUMP_NAME, MANIFEST_NAME, RECORDINGS_NAME}
    actual_top = {entry.name for entry in bundle.iterdir()}
    if actual_top != expected_top:
        raise OperationError(f"bundle has unexpected or missing top-level entries: {sorted(actual_top ^ expected_top)}")
    for name in expected_top:
        if is_link_or_reparse(bundle / name):
            raise OperationError(f"bundle entry is a link or reparse point: {name}")
    if not (bundle / DUMP_NAME).is_file() or not (bundle / MANIFEST_NAME).is_file() or not (bundle / RECORDINGS_NAME).is_dir():
        raise OperationError("bundle entry types are invalid")
    try:
        manifest = json.loads((bundle / MANIFEST_NAME).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise OperationError(f"manifest is unreadable: {error}") from error
    if not isinstance(manifest, dict):
        raise OperationError("manifest root must be an object")
    if set(manifest) != {"formatVersion", "schemaVersion", "createdAtUtc", "databaseDump", "recordings"}:
        raise OperationError("manifest fields do not match the supported format")
    if manifest["formatVersion"] != FORMAT_VERSION:
        raise OperationError(f"unsupported bundle format: {manifest['formatVersion']!r}")
    if not isinstance(manifest["schemaVersion"], str) or not isinstance(manifest["createdAtUtc"], str):
        raise OperationError("manifest schema version or creation time is invalid")
    dump_info = manifest["databaseDump"]
    if not isinstance(dump_info, dict) or set(dump_info) != {"path", "size", "sha256"} or dump_info["path"] != DUMP_NAME:
        raise OperationError("database dump manifest entry is invalid")
    if (not isinstance(dump_info["size"], int) or dump_info["size"] < 0
            or not isinstance(dump_info["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", dump_info["sha256"])):
        raise OperationError("database dump manifest entry types are invalid")
    dump_size, dump_hash = sha256_file(bundle / DUMP_NAME)
    if dump_info["size"] != dump_size or dump_info["sha256"] != dump_hash:
        raise OperationError("database dump hash or size does not match the manifest")
    rows = manifest["recordings"]
    if not isinstance(rows, list):
        raise OperationError("recordings manifest must be a list")
    expected_files: set[str] = set()
    validated: list[dict[str, object]] = []
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"path", "size", "sha256"}:
            raise OperationError("recording manifest entry is invalid")
        if (not isinstance(row["path"], str) or not isinstance(row["size"], int) or row["size"] < 0
                or not isinstance(row["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"])):
            raise OperationError("recording manifest entry types are invalid")
        rel = recording_relative_path(row["path"])
        if row["path"] in expected_files:
            raise OperationError(f"duplicate recording manifest path: {row['path']}")
        expected_files.add(row["path"])
        source = bundle.joinpath(RECORDINGS_NAME, *rel.parts)
        if not source.is_file() or is_link_or_reparse(source):
            raise OperationError(f"recording bundle file is missing or unsafe: {row['path']}")
        size, digest = sha256_file(source)
        if row["size"] != size or row["sha256"] != digest:
            raise OperationError(f"recording hash or size mismatch: {row['path']}")
        validated.append(row)
    actual_files = {relative for relative, _ in enumerate_recordings(bundle / RECORDINGS_NAME)}
    if actual_files != expected_files:
        raise OperationError(f"recording tree has unexpected or missing files: {sorted(actual_files ^ expected_files)}")
    return bundle, manifest, validated


def database_exists(args: argparse.Namespace, database: str) -> bool:
    value = psql(args, args.maintenance_db,
                 f"SELECT COUNT(*) FROM pg_database WHERE datname={sql_literal(database)}")
    return value != "0"


def copy_validated_recordings(bundle: Path, rows: Iterable[dict[str, object]], staging: Path) -> None:
    staging.mkdir()
    for row in rows:
        rel = recording_relative_path(str(row["path"]))
        source = bundle.joinpath(RECORDINGS_NAME, *rel.parts)
        destination = staging.joinpath(*rel.parts)
        destination.parent.mkdir(exist_ok=True)
        shutil.copyfile(source, destination)
        size, digest = sha256_file(destination)
        if size != row["size"] or digest != row["sha256"]:
            raise OperationError(f"copied recording failed verification: {row['path']}")


def query_rows(args: argparse.Namespace, database: str, sql: str, columns: int) -> list[list[str]]:
    output = psql(args, database, sql)
    if not output:
        return []
    rows = [line.split("\t") for line in output.splitlines()]
    if any(len(row) != columns for row in rows):
        raise OperationError("unexpected PostgreSQL verification output")
    return rows


def verify_original_text_immutability(args: argparse.Namespace) -> None:
    meeting_id = str(uuid4())
    segment_id = str(uuid4())
    try:
        psql(args, args.target_db, f"""
            BEGIN;
            INSERT INTO meetings(id,title,status,input_source,revision)
            VALUES ('{meeting_id}','restore verification','STOPPED','MOCK',0);
            INSERT INTO transcript_segments(
                id,meeting_id,provider_event_id,sequence_no,start_ms,end_ms,
                original_text,display_text,transcript_status,speaker_status
            ) VALUES (
                '{segment_id}','{meeting_id}','restore-verification',0,0,0,
                'verification-original','verification-display','FINAL','UNKNOWN'
            );
            DO $verify$
            BEGIN
                BEGIN
                    UPDATE transcript_segments
                       SET original_text='verification-changed'
                     WHERE id='{segment_id}';
                    RAISE EXCEPTION 'immutability verification update was accepted';
                EXCEPTION WHEN OTHERS THEN
                    IF SQLERRM <> 'original_text is immutable' THEN
                        RAISE;
                    END IF;
                END;
            END
            $verify$;
            ROLLBACK;
        """, tuples=False)
    except OperationError as error:
        raise OperationError("restored immutable original-text behavior failed verification") from error


def rewrite_and_verify(args: argparse.Namespace, root: Path, manifest: dict[str, object]) -> None:
    portable_root = root.resolve(strict=True).as_posix()
    root_literal = sql_literal(portable_root + "/")
    psql(args, args.target_db, f"""
        UPDATE recording_chunks rc
           SET path={root_literal} || r.meeting_id::text || '/chunk-' || lpad(rc.sequence_no::text,8,'0') || '.bin'
          FROM recordings r
         WHERE rc.recording_id=r.id;
    """, tuples=False)
    actual = {relative for relative, _ in enumerate_recordings(root)}
    verify_database_audio(args, args.target_db, root, actual, True)
    schema_version = psql(args, args.target_db,
                          "SELECT version::text FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                          "ORDER BY installed_rank DESC LIMIT 1")
    if schema_version != str(manifest["schemaVersion"]):
        raise OperationError("restored Flyway schema version does not match the manifest")
    disabled = psql(args, args.target_db,
                    "SELECT COUNT(*) FROM pg_trigger WHERE tgname='transcript_original_text_immutable' AND tgenabled<>'D'")
    if disabled != "1":
        raise OperationError("immutable original-text trigger is missing or disabled")
    verify_original_text_immutability(args)
    unvalidated = psql(args, args.target_db,
                       "SELECT COUNT(*) FROM pg_constraint WHERE contype='f' AND NOT convalidated")
    if unvalidated != "0":
        raise OperationError("restored database contains unvalidated foreign keys")


def restore(args: argparse.Namespace) -> None:
    assert_api_stopped(args)
    validate_database_name(args.target_db)
    bundle, manifest, rows = load_and_validate_bundle(args.bundle)
    target_root = args.target_root.absolute()
    if target_root.exists():
        raise OperationError(f"target recording root already exists: {target_root}")
    if not target_root.parent.is_dir():
        raise OperationError(f"target recording parent does not exist: {target_root.parent}")
    ensure_separate_paths(bundle, target_root)
    if database_exists(args, args.target_db):
        raise OperationError(f"target database already exists: {args.target_db}")

    prefix = f".{target_root.name}.restore-"
    staging = target_root.parent / f"{prefix}{uuid4()}"
    copy_validated_recordings(bundle, rows, staging)
    root_published = False
    db_created = False
    try:
        run([executable(args.pg_bin, "createdb"), "--host", args.host, "--port", str(args.port),
             "--username", args.user, "--maintenance-db", args.maintenance_db, args.target_db])
        db_created = True
        run([executable(args.pg_bin, "pg_restore"), *connection_args(args, args.target_db),
             "--exit-on-error", "--no-owner", "--no-acl", str(bundle / DUMP_NAME)])
        if target_root.exists():
            raise OperationError(f"target recording root appeared while restore was running: {target_root}")
        os.rename(staging, target_root)
        root_published = True
        rewrite_and_verify(args, target_root, manifest)
    except Exception:
        if not root_published and staging.exists():
            safe_cleanup_owned(staging, target_root.parent, prefix)
        diagnostic = []
        if db_created:
            diagnostic.append(f"database {args.target_db}")
        if root_published:
            diagnostic.append(f"recording root {target_root}")
        if diagnostic:
            print("restore failed; diagnostic artifacts were retained: " + ", ".join(diagnostic), file=sys.stderr)
        raise
    print(f"restore completed: database={args.target_db} recordingRoot={target_root}")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--pg-bin", type=Path, required=True, help="directory containing PostgreSQL CLI executables")
    common.add_argument("--host", default="127.0.0.1")
    common.add_argument("--port", type=int, default=5432)
    common.add_argument("--user", required=True)
    common.add_argument("--api-stopped", action="store_true", help="acknowledge that the API is stopped")
    commands = result.add_subparsers(dest="command", required=True)
    backup_parser = commands.add_parser("backup", parents=[common])
    backup_parser.add_argument("--source-db", required=True)
    backup_parser.add_argument("--source-root", type=Path, required=True)
    backup_parser.add_argument("--bundle", type=Path, required=True)
    backup_parser.set_defaults(handler=backup)
    restore_parser = commands.add_parser("restore", parents=[common])
    restore_parser.add_argument("--maintenance-db", default="postgres")
    restore_parser.add_argument("--target-db", required=True)
    restore_parser.add_argument("--target-root", type=Path, required=True)
    restore_parser.add_argument("--bundle", type=Path, required=True)
    restore_parser.set_defaults(handler=restore)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    args.pg_bin = args.pg_bin.resolve(strict=True)
    try:
        args.handler(args)
        return 0
    except OperationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
