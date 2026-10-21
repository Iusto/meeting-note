param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $RunnerArgs
)

$jar = Join-Path $PSScriptRoot '..\apps\api\target\api-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    Write-Error 'Build apps/api before running the OpenAI POC.'
    exit 2
}

& java '-Dloader.main=dev.aimeeting.api.provider.openai.OpenAiPocRunner' `
    -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher @RunnerArgs
exit $LASTEXITCODE
