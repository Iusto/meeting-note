[CmdletBinding()]
param([switch]$EnablePaidAi, [switch]$OpenMeeting)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$savedEnvironment = @{}
foreach ($name in @('PAID_API_TESTS_ENABLED', 'DEEPGRAM_API_KEY', 'OPENAI_API_KEY',
        'TAILSCALE_HOST', 'WEB_PORT', 'APP_OWNER_TOKEN', 'POSTGRES_PASSWORD')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

Push-Location $projectRoot
try {
    $deepgramKey = ''
    $openAiKey = ''
    if ($EnablePaidAi) {
        $deepgramKey = [string]$savedEnvironment['DEEPGRAM_API_KEY']
        if ([string]::IsNullOrWhiteSpace($deepgramKey)) {
            $deepgramKey = [string][Environment]::GetEnvironmentVariable('DEEPGRAM_API_KEY', 'User')
        }
        $openAiKey = [string]$savedEnvironment['OPENAI_API_KEY']
        if ([string]::IsNullOrWhiteSpace($openAiKey)) {
            $openAiKey = [string][Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'User')
        }
        if ([string]::IsNullOrWhiteSpace($deepgramKey) -or [string]::IsNullOrWhiteSpace($openAiKey)) {
            throw 'Paid AI requires DEEPGRAM_API_KEY and OPENAI_API_KEY in the process or Windows user environment.'
        }
    }

    $docker = (Get-Command docker -ErrorAction Stop).Source
    $tailscale = Get-Command tailscale -ErrorAction SilentlyContinue
    if ($tailscale) { $tailscale = $tailscale.Source }
    else { $tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe' }
    if (-not (Test-Path -LiteralPath $tailscale)) { throw 'Tailscale executable not found.' }

    $engine = & $docker info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0 -or "$engine".Trim() -ne 'linux') {
        throw 'Docker Linux engine is not accessible. Open Docker Desktop and wait for the engine.'
    }
    $statusJson = & $tailscale status --json
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale status failed. Run this script from your normal Windows PowerShell.' }
    $status = $statusJson | ConvertFrom-Json
    if ($status.BackendState -ne 'Running') { throw 'Sign in to Tailscale before deployment.' }
    $hostname = ([string]$status.Self.DNSName).TrimEnd('.')
    if ($hostname -notmatch '^[a-zA-Z0-9-]+\.[a-zA-Z0-9-]+\.ts\.net$') {
        throw 'An exact Tailscale MagicDNS hostname is required.'
    }

    $envFile = Join-Path $projectRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        $content = "APP_OWNER_TOKEN=$(New-LocalSecret)`nPOSTGRES_PASSWORD=$(New-LocalSecret)`nWEB_PORT=8088`nTAILSCALE_HOST=$hostname`nPAID_API_TESTS_ENABLED=false`n"
        $stream = [IO.File]::Open($envFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $writer = New-Object IO.StreamWriter($stream, (New-Object Text.UTF8Encoding($false)))
        try { $writer.Write($content) } finally { $writer.Dispose() }
        Write-Host 'Created .env with random application and database secrets. Provider keys are not needed.'
    }
    $portLine = @(Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^WEB_PORT=' })
    $port = 8088
    if ($portLine.Count -gt 0) {
        if ($portLine.Count -ne 1 -or $portLine[0] -notmatch '^WEB_PORT=([0-9]{1,5})$') {
            throw 'Use one unquoted WEB_PORT=<number> entry in .env.'
        }
        $port = [int]$Matches[1]
    }
    if ($port -lt 1 -or $port -gt 65535) { throw 'WEB_PORT must be between 1 and 65535.' }

    # Process overrides defeat values inherited from .env; restore them on exit.
    $env:PAID_API_TESTS_ENABLED = if ($EnablePaidAi) { 'true' } else { 'false' }
    $env:DEEPGRAM_API_KEY = if ($EnablePaidAi) { $deepgramKey } else { '' }
    $env:OPENAI_API_KEY = if ($EnablePaidAi) { $openAiKey } else { '' }
    $env:TAILSCALE_HOST = $hostname
    $env:WEB_PORT = [string]$port
    # Use the project's secrets rather than unrelated inherited shell values.
    [Environment]::SetEnvironmentVariable('APP_OWNER_TOKEN', $null, 'Process')
    [Environment]::SetEnvironmentVariable('POSTGRES_PASSWORD', $null, 'Process')
    $compose = @('compose', '--env-file', $envFile, '-f', 'compose.yaml', '-f', 'compose.tailscale.yaml')
    & $docker @compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration validation failed.' }
    & $docker @compose up -d --build --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed. Containers and data are retained for diagnosis.' }

    $apiReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/api/v1/session" -TimeoutSec 5
            $apiReady = ([int]$response.StatusCode -eq 401)
        } catch {
            if ($_.Exception.Response) { $apiReady = ([int]$_.Exception.Response.StatusCode -eq 401) }
        }
        if ($apiReady) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $apiReady) { throw 'API readiness/authentication check failed; Tailscale Serve was not changed.' }

    Write-Host 'Configuring private HTTPS. If Tailscale shows an enablement link, complete it and rerun the script.'
    & $tailscale serve --bg "http://127.0.0.1:$port"
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale Serve setup failed; the local containers are retained.' }
    & $tailscale serve status
    if ($LASTEXITCODE -ne 0) { throw 'Could not read Tailscale Serve status.' }
    & $docker @compose ps
    if ($LASTEXITCODE -ne 0) { throw 'Could not read container status.' }
    Write-Host "Address: https://$hostname"
    Write-Host 'Login token: APP_OWNER_TOKEN in the project .env file. Do not paste it into chat.'
    if ($EnablePaidAi) {
        Write-Host 'Verify login, AI availability, and microphone access from another device on your tailnet.'
    } else {
        Write-Host 'Verify login and microphone access from another device on your tailnet. Paid APIs remain disabled.'
    }
    if ($OpenMeeting) {
        Write-Host 'Before recording: sign in, check /usage for remaining monthly and meeting budgets, then select LIVE and your microphone.'
        Write-Host 'Opening this page does not start recording or make a paid provider request.'
        Start-Process "https://$hostname/meetings/new"
    }
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    Pop-Location
}
