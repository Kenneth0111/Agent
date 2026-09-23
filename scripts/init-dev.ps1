$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    throw '.env already exists. Keep its credentials; do not regenerate passwords for existing volumes.'
}
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}
$settings = @(
    'SERVER_ADDRESS=127.0.0.1'
    'SERVER_PORT=18080'
    ('MYSQL_ROOT_PASSWORD=' + (New-LocalSecret))
    ('DB_PASSWORD=' + (New-LocalSecret))
    ('REDIS_PASSWORD=' + (New-LocalSecret))
)
[IO.File]::WriteAllLines($envPath, $settings, (New-Object System.Text.UTF8Encoding $false))
Write-Output 'Created ignored .env with random local credentials. No secret values were printed.'
