"""Exercise the Windows deployment script without Docker, VPN, or network access."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("powershell.exe"), "Windows PowerShell required")
class PrivateStartupTests(unittest.TestCase):
    def setUp(self):
        scratch = PROJECT / ".tools" / "deployment-script-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(dir=scratch))
        (self.root / "scripts").mkdir()
        shutil.copyfile(PROJECT / "scripts/start-private.ps1", self.root / "scripts/start-private.ps1")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        (self.bin / "docker.ps1").write_text("""
$global:LASTEXITCODE = 0
if ($args[0] -eq 'info') { Write-Output $env:TEST_ENGINE; return }
Add-Content $env:TEST_CALLS (ConvertTo-Json -Compress @{tool='docker'; args=@($args); paid=$env:PAID_API_TESTS_ENABLED; deepgram=![string]::IsNullOrWhiteSpace($env:DEEPGRAM_API_KEY); openai=![string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)})
if ($args -contains 'up' -and $env:TEST_FAIL_UP -eq '1') { $global:LASTEXITCODE = 1 }
""", encoding="utf-8")
        (self.bin / "tailscale.ps1").write_text("""
$global:LASTEXITCODE = 0
if ($args[0] -eq 'status') {
    ConvertTo-Json -Compress @{BackendState=$env:TEST_BACKEND; Self=@{DNSName='meeting.example.ts.net.'}}
    return
}
Add-Content $env:TEST_CALLS (ConvertTo-Json -Compress @{tool='tailscale'; args=@($args)})
""", encoding="utf-8")
        (self.root / "run.ps1").write_text("""
$ErrorActionPreference = 'Stop'
try {
    function Invoke-WebRequest { [pscustomobject]@{StatusCode=[int]$env:TEST_HTTP_STATUS} }
    function Start-Sleep {}
    function Start-Process { param([string]$FilePath) Add-Content $env:TEST_CALLS (ConvertTo-Json -Compress @{tool='browser'; url=$FilePath}) }
    $openMeeting = $env:TEST_OPEN_MEETING -eq '1'
    if ($env:TEST_ENABLE_PAID -eq '1') {
        & (Join-Path $PSScriptRoot 'scripts/start-private.ps1') -EnablePaidAi -OpenMeeting:$openMeeting
    } else {
        & (Join-Path $PSScriptRoot 'scripts/start-private.ps1')
    }
    if ($env:PAID_API_TESTS_ENABLED -ne 'true' -or $env:DEEPGRAM_API_KEY -ne 'caller-deepgram-secret' -or
            $env:OPENAI_API_KEY -ne 'caller-openai-secret' -or $env:WEB_PORT -ne '9191' -or
            $env:TAILSCALE_HOST -ne 'previous') {
        throw 'Caller environment was not restored.'
    }
} catch { Write-Output $_.Exception.Message; exit 1 }
""", encoding="utf-8")
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        TEST_ENGINE="linux", TEST_BACKEND="Running", TEST_FAIL_UP="0", TEST_HTTP_STATUS="401",
                        TEST_CALLS=str(self.root / "calls.jsonl"), TEST_ENABLE_PAID="0", PAID_API_TESTS_ENABLED="true",
                        DEEPGRAM_API_KEY="caller-deepgram-secret", OPENAI_API_KEY="caller-openai-secret",
                        TAILSCALE_HOST="previous", WEB_PORT="9191")

    def run_script(self):
        return subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                               "-File", str(self.root / "run.ps1")], env=self.env,
                              capture_output=True, text=True, timeout=30)

    def calls(self):
        log = self.root / "calls.jsonl"
        return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    def test_startup_creates_secrets_and_disables_paid_calls(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        content = (self.root / ".env").read_text()
        self.assertRegex(content, r"APP_OWNER_TOKEN=[0-9a-f]{64}")
        self.assertRegex(content, r"POSTGRES_PASSWORD=[0-9a-f]{64}")
        for line in content.splitlines()[:2]:
            self.assertNotIn(line.split("=", 1)[1], result.stdout + result.stderr)
        docker_calls = [call for call in self.calls() if call["tool"] == "docker"]
        self.assertTrue(all(call["paid"] == "false" for call in docker_calls))
        self.assertTrue(all(not call["deepgram"] and not call["openai"] for call in docker_calls))
        self.assertNotIn("caller-deepgram-secret", result.stdout + result.stderr + content)
        self.assertNotIn("caller-openai-secret", result.stdout + result.stderr + content)
        self.assertIn("Address: https://meeting.example.ts.net", result.stdout)
        serve = [call for call in self.calls() if "--bg" in call["args"]]
        self.assertEqual(serve[0]["args"], ["serve", "--bg", "http://127.0.0.1:8088"])

    def test_existing_configuration_is_preserved(self):
        content = "APP_OWNER_TOKEN=existing\nPOSTGRES_PASSWORD=existing\nWEB_PORT=8123\nPAID_API_TESTS_ENABLED=true\n"
        (self.root / ".env").write_text(content)
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / ".env").read_text(), content)
        self.assertTrue(any("http://127.0.0.1:8123" in c["args"] for c in self.calls()))

    def test_meeting_launcher_opens_browser_only_after_success(self):
        self.env.update(TEST_ENABLE_PAID="1", TEST_OPEN_MEETING="1")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.calls()[-1], {"tool": "browser", "url": "https://meeting.example.ts.net/meetings/new"})

    def test_meeting_launcher_does_not_open_browser_after_startup_failure(self):
        self.env.update(TEST_ENABLE_PAID="1", TEST_OPEN_MEETING="1", TEST_FAIL_UP="1")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c["tool"] == "browser" for c in self.calls()))

    def test_explicit_paid_start_passes_existing_keys_without_persisting_or_printing_them(self):
        self.env["TEST_ENABLE_PAID"] = "1"
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        docker_calls = [call for call in self.calls() if call["tool"] == "docker"]
        self.assertTrue(all(call["paid"] == "true" for call in docker_calls))
        self.assertTrue(all(call["deepgram"] and call["openai"] for call in docker_calls))
        output_and_file = result.stdout + result.stderr + (self.root / ".env").read_text()
        self.assertNotIn("caller-deepgram-secret", output_and_file)
        self.assertNotIn("caller-openai-secret", output_and_file)

    def test_explicit_paid_start_requires_both_provider_keys(self):
        self.env["TEST_ENABLE_PAID"] = "1"
        self.env["OPENAI_API_KEY"] = ""
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("requires DEEPGRAM_API_KEY and OPENAI_API_KEY", result.stdout + result.stderr)
        self.assertFalse((self.root / ".env").exists())
        self.assertEqual(self.calls(), [])

    def test_inaccessible_engine_stops_before_configuration(self):
        self.env["TEST_ENGINE"] = "windows"
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertFalse((self.root / ".env").exists())
        self.assertEqual(self.calls(), [])

    def test_logged_out_vpn_stops_before_configuration(self):
        self.env["TEST_BACKEND"] = "NeedsLogin"
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertFalse((self.root / ".env").exists())
        self.assertEqual(self.calls(), [])

    def test_failed_startup_does_not_publish(self):
        self.env["TEST_FAIL_UP"] = "1"
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertFalse(any(c["tool"] == "tailscale" for c in self.calls()))

    def test_invalid_port_stops_without_rewriting_configuration(self):
        content = "WEB_PORT=99999\n"
        (self.root / ".env").write_text(content)
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertEqual((self.root / ".env").read_text(), content)
        self.assertEqual(self.calls(), [])

    def test_unprotected_or_unready_api_does_not_publish(self):
        self.env["TEST_HTTP_STATUS"] = "200"
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertFalse(any(c["tool"] == "tailscale" for c in self.calls()))


if __name__ == "__main__":
    unittest.main()
