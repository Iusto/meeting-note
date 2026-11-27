[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $projectRoot '.omx\codex-bridge'
$bridgeScript = Join-Path $PSScriptRoot 'codex-bridge.cjs'
$model = 'gpt-6.2-sol'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$tokenFile = Join-Path $runtime 'token'
if (-not (Test-Path -LiteralPath $tokenFile)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($tokenFile, [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant())
}
$token = [IO.File]::ReadAllText($tokenFile).Trim()
function Get-BridgeHealth {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8767/health' -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 2
        if ($health.provider -eq 'CODEX') { return $health }
    } catch { }
}

$health = Get-BridgeHealth
if ($health.model -ne $model) {
    $node = (Get-Command node -ErrorAction Stop).Source
    $codex = (Get-Command codex -ErrorAction Stop).Source
    $env:CODEX_CLI_JS = Join-Path (Split-Path -Parent $codex) 'node_modules\@openai\codex\bin\codex.js'
    if (-not (Test-Path -LiteralPath $env:CODEX_CLI_JS)) { throw 'Codex CLI JavaScript entry point was not found.' }
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $login = & $node $env:CODEX_CLI_JS login status 2>&1 } finally { $ErrorActionPreference = $savedErrorPreference }
    if ("$login" -notmatch 'Logged in using ChatGPT') { throw 'Run codex login with your ChatGPT account first.' }

    if ($health) {
        $listener = Get-NetTCPConnection -LocalAddress '0.0.0.0' -LocalPort 8767 -State Listen
        $bridgeProcessId = $listener.OwningProcess
        $bridgeProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $bridgeProcessId"
        $scriptArgument = [regex]::Escape('"' + $bridgeScript + '"')
        if ($bridgeProcess.Name -ne 'node.exe' -or $bridgeProcess.CommandLine -notmatch "\s$scriptArgument\s*$") {
            throw 'Could not identify this project''s running Codex bridge on port 8767.'
        }
        $health = Get-BridgeHealth
        if (-not $health) { throw 'The running Codex bridge changed during startup. Run the launcher again.' }
        $activeChildren = @()
        if ($null -eq $health.busy) {
            $activeChildren = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $bridgeProcessId" |
                    Where-Object Name -ne 'conhost.exe')
        }
        if ($health.busy -or $activeChildren.Count -gt 0) {
            throw 'A Codex bridge request is running. Wait for it to finish and run the launcher again.'
        }
        Stop-Process -Id $bridgeProcessId
        Wait-Process -Id $bridgeProcessId -Timeout 5 -ErrorAction SilentlyContinue
    }

    Start-Process -FilePath $node -ArgumentList ('"' + $bridgeScript + '"') -WindowStyle Hidden -WorkingDirectory $projectRoot -RedirectStandardOutput (Join-Path $runtime 'stdout.log') -RedirectStandardError (Join-Path $runtime 'stderr.log') | Out-Null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $health = Get-BridgeHealth
        if ($health.model -eq $model) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($health.model -ne $model) { throw "Codex bridge did not become ready with model $model." }
}
$env:CODEX_BRIDGE_TOKEN = $token
$env:AI_LLM_PROVIDER = 'codex'
Write-Host "Codex subscription bridge is ready with $model."
