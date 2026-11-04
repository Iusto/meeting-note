[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $projectRoot '.omx\codex-bridge'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$tokenFile = Join-Path $runtime 'token'
if (-not (Test-Path -LiteralPath $tokenFile)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($tokenFile, [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant())
}
$token = [IO.File]::ReadAllText($tokenFile).Trim()
$ready = $false
try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8767/health' -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 2
    $ready = $health.provider -eq 'CODEX'
} catch { }
if (-not $ready) {
    $node = (Get-Command node -ErrorAction Stop).Source
    $codex = (Get-Command codex -ErrorAction Stop).Source
    $env:CODEX_CLI_JS = Join-Path (Split-Path -Parent $codex) 'node_modules\@openai\codex\bin\codex.js'
    if (-not (Test-Path -LiteralPath $env:CODEX_CLI_JS)) { throw 'Codex CLI JavaScript entry point was not found.' }
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $login = & $node $env:CODEX_CLI_JS login status 2>&1 } finally { $ErrorActionPreference = $savedErrorPreference }
    if ("$login" -notmatch 'Logged in using ChatGPT') { throw 'Run codex login with your ChatGPT account first.' }
    Start-Process -FilePath $node -ArgumentList ('"' + (Join-Path $PSScriptRoot 'codex-bridge.cjs') + '"') -WindowStyle Hidden -WorkingDirectory $projectRoot -RedirectStandardOutput (Join-Path $runtime 'stdout.log') -RedirectStandardError (Join-Path $runtime 'stderr.log') | Out-Null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8767/health' -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 2
            if ($health.provider -eq 'CODEX') { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $ready) { throw 'Codex bridge did not become ready.' }
}
$env:CODEX_BRIDGE_TOKEN = $token
$env:AI_LLM_PROVIDER = 'codex'
Write-Host 'Codex subscription bridge is ready. No OpenAI API key is used.'
