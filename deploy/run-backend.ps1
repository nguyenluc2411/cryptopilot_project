# Loads deploy/.env into this shell and starts the backend.
#
# The backend reads the OS environment, not deploy/.env - docker compose is the only thing that
# reads that file on its own. Without this script the database password, the signing key and the two
# seeded password hashes have to be exported by hand every time a terminal is opened, and the hashes
# contain '$', which PowerShell expands inside double quotes and silently turns into an empty
# string. Setting them here, from the file, removes both problems.
#
#   docker compose -f deploy/docker-compose.dev.yml up -d
#   .\deploy\run-backend.ps1
#
# Local development only. It is not a deployment mechanism and staging never runs it.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $PSScriptRoot '.env'

if (-not (Test-Path $envFile)) {
    Write-Error "deploy/.env not found. Copy deploy/.env.example to deploy/.env and fill it in."
}

# KEY=VALUE, one per line. Blank lines and comments are skipped; the value is taken verbatim after
# the first '=' so that a bcrypt hash, which is mostly punctuation, survives unaltered.
$loaded = 0
foreach ($line in Get-Content $envFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }

    $split = $trimmed.IndexOf('=')
    if ($split -lt 1) { continue }

    $key = $trimmed.Substring(0, $split).Trim()
    $value = $trimmed.Substring($split + 1).Trim()

    # An inline comment after a value, as in "REDIS_PASSWORD=   # empty locally", is not part of it.
    if ($value -match '^\s*#') { $value = '' }

    # A quoted value is unwrapped. The two password hashes are single-quoted in the file because
    # docker compose interpolates '$' and a bcrypt hash is mostly '$'; the quotes are for compose,
    # not part of the value, and Spring must receive the hash without them or nothing will match.
    if ($value.Length -ge 2) {
        if (($value.StartsWith("'") -and $value.EndsWith("'")) -or
            ($value.StartsWith('"') -and $value.EndsWith('"'))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }

    Set-Item -Path "env:$key" -Value $value
    $loaded++
}

# The three the application refuses to start without, named one by one so that a missing value is
# reported here rather than as a Spring placeholder failure forty lines into a stack trace.
foreach ($required in @('JWT_SECRET', 'DB_PASSWORD')) {
    if (-not (Get-Item "env:$required" -ErrorAction SilentlyContinue).Value) {
        Write-Error "$required is empty in deploy/.env. The backend has no fallback for it."
    }
}
if ($env:JWT_SECRET.Length -lt 32) {
    Write-Error "JWT_SECRET is $($env:JWT_SECRET.Length) characters; HS256 needs at least 32."
}

Write-Host "Loaded $loaded values from deploy/.env (profile: $env:SPRING_PROFILES_ACTIVE)" -ForegroundColor Green

if (-not $env:ADMIN_PASSWORD_HASH) {
    Write-Host "ADMIN_PASSWORD_HASH is empty - the seeded accounts will exist but nothing will sign in to them." -ForegroundColor Yellow
}

Push-Location (Join-Path $root 'backend')
try {
    & .\mvnw.cmd spring-boot:run
}
finally {
    Pop-Location
}
