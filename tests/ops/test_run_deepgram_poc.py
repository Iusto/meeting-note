"""Exercise the standalone POC wrappers with a fake Java command."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("powershell.exe"), "Windows PowerShell required")
class PocWrapperTests(unittest.TestCase):
    def setUp(self):
        scratch = PROJECT / ".tools" / "poc-wrapper-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(dir=scratch))
        (self.root / "scripts").mkdir()
        for name in ("run-deepgram-poc.ps1", "run-openai-poc.ps1"):
            shutil.copyfile(PROJECT / "scripts" / name, self.root / "scripts" / name)
        jar = self.root / "apps" / "api" / "target" / "api-0.0.1-SNAPSHOT.jar"
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b"offline-test-placeholder")
        self.log = self.root / "java-args.txt"
        (self.root / "run.ps1").write_text(r"""
param([string] $Script, [Parameter(ValueFromRemainingArguments = $true)] [string[]] $RunnerArgs)
function global:java {
    [System.IO.File]::WriteAllLines($env:TEST_JAVA_ARGS, [string[]] $args)
    $global:LASTEXITCODE = 0
}
& $Script @RunnerArgs
exit $LASTEXITCODE
""", encoding="utf-8")

    def run_wrapper(self, name, arguments):
        return subprocess.run(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
             str(self.root / "run.ps1"), str(self.root / "scripts" / name), *arguments],
            env={**os.environ, "TEST_JAVA_ARGS": str(self.log)},
            capture_output=True, text=True, timeout=15,
        )

    def test_deepgram_wrapper_preserves_explicit_paid_opt_ins(self):
        arguments = ["--execute", "--synthetic-approved", "--input", "sample.pcm"]
        result = self.run_wrapper("run-deepgram-poc.ps1", arguments)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        lines = self.log.read_text().splitlines()
        self.assertEqual(lines[0], "-Dloader.main=dev.aimeeting.api.provider.deepgram.DeepgramPocRunner")
        self.assertEqual(lines[1], "-cp")
        self.assertEqual(lines[3], "org.springframework.boot.loader.launch.PropertiesLauncher")
        self.assertEqual(lines[4:], arguments)

    def test_openai_wrapper_uses_its_own_runner(self):
        result = self.run_wrapper("run-openai-poc.ps1", ["--help"])

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.log.read_text().splitlines()[0],
                         "-Dloader.main=dev.aimeeting.api.provider.openai.OpenAiPocRunner")


if __name__ == "__main__":
    unittest.main()
