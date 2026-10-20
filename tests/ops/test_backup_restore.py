import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from uuid import uuid4


SCRIPT = Path(__file__).parents[2] / "scripts" / "backup_restore.py"
SPEC = importlib.util.spec_from_file_location("backup_restore", SCRIPT)
ops = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(ops)


class BackupRestoreValidationTest(unittest.TestCase):
    def test_rejects_malicious_manifest_paths(self):
        for value in ("../recording.bin", "/tmp/recording.bin", "id/../../x", "not-a-uuid/recording.bin"):
            with self.subTest(value=value), self.assertRaises(ops.OperationError):
                ops.recording_relative_path(value)

    def test_rejects_unexpected_source_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            meeting = root / str(uuid4())
            meeting.mkdir()
            (meeting / "notes.txt").write_text("unexpected", encoding="utf-8")
            with self.assertRaisesRegex(ops.OperationError, "unexpected recording file"):
                ops.enumerate_recordings(root)

    def test_rejects_symlink_when_supported(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            meeting = root / str(uuid4())
            meeting.mkdir()
            target = root / "outside.bin"
            target.write_bytes(b"audio")
            link = meeting / "recording.bin"
            try:
                link.symlink_to(target)
            except OSError:
                self.skipTest("symlink creation is unavailable")
            with self.assertRaises(ops.OperationError):
                ops.enumerate_recordings(root)

    def _bundle(self, root: Path):
        bundle = root / "bundle"
        recordings = bundle / ops.RECORDINGS_NAME
        meeting = recordings / str(uuid4())
        meeting.mkdir(parents=True)
        audio = meeting / "recording.bin"
        audio.write_bytes(b"audio")
        dump = bundle / ops.DUMP_NAME
        dump.write_bytes(b"dump")
        audio_size, audio_hash = ops.sha256_file(audio)
        dump_size, dump_hash = ops.sha256_file(dump)
        manifest = {
            "formatVersion": ops.FORMAT_VERSION,
            "schemaVersion": "6",
            "createdAtUtc": "2026-01-01T00:00:00Z",
            "databaseDump": {"path": ops.DUMP_NAME, "size": dump_size, "sha256": dump_hash},
            "recordings": [{"path": f"{meeting.name}/recording.bin", "size": audio_size, "sha256": audio_hash}],
        }
        (bundle / ops.MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
        return bundle, audio

    def test_rejects_corrupt_recording_before_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, audio = self._bundle(Path(temporary))
            audio.write_bytes(b"tampered")
            with self.assertRaisesRegex(ops.OperationError, "hash or size mismatch"):
                ops.load_and_validate_bundle(bundle)

    def test_rejects_extra_bundle_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle, _ = self._bundle(Path(temporary))
            (bundle / "secret.env").write_text("PGPASSWORD=x", encoding="utf-8")
            with self.assertRaisesRegex(ops.OperationError, "top-level"):
                ops.load_and_validate_bundle(bundle)

    def test_restore_refuses_existing_target_before_database_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle, _ = self._bundle(root)
            target = root / "existing"
            target.mkdir()
            args = mock.Mock(api_stopped=True, target_db="new_db", bundle=bundle,
                             target_root=target, maintenance_db="postgres")
            with mock.patch.object(ops, "database_exists") as exists, self.assertRaisesRegex(ops.OperationError, "already exists"):
                ops.restore(args)
            exists.assert_not_called()

    def test_restore_refuses_existing_database_before_creating_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle, _ = self._bundle(root)
            target = root / "new-root"
            args = mock.Mock(api_stopped=True, target_db="existing_db", bundle=bundle,
                             target_root=target, maintenance_db="postgres")
            with mock.patch.object(ops, "database_exists", return_value=True), self.assertRaisesRegex(ops.OperationError, "database already exists"):
                ops.restore(args)
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
