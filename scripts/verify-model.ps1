$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Run scripts/init-dev.ps1 first.' }
$previous = @{}
try {
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $name = $Matches[1]
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $Matches[2], 'Process')
        }
    }
    if (-not [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')) {
        throw 'Set DEEPSEEK_API_KEY in .env first; without it the live test is skipped, which proves nothing.'
    }
    # Two real calls only. Every other model test uses the local stub.
    & mvn -f (Join-Path $projectRoot 'backend/pom.xml') '-Dtest=DeepSeekLiveCallTest' test
    if ($LASTEXITCODE -ne 0) { throw "Live model verification failed with code $LASTEXITCODE" }
} finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
