# Starts the local Fleet PostgreSQL server (port from .env.local, default 5433).
. (Join-Path $PSScriptRoot 'env.ps1')

$logFile = Join-Path $env:PGHOME 'postgres.log'
& pg_ctl status -D $env:PGDATA *> $null
if ($LASTEXITCODE -eq 0) {
    Write-Host "PostgreSQL is already running on port $env:PGPORT."
    exit 0
}
& pg_ctl start -D $env:PGDATA -l $logFile -w
if ($LASTEXITCODE -eq 0) { Write-Host "PostgreSQL started on port $env:PGPORT (log: $logFile)." }
exit $LASTEXITCODE
