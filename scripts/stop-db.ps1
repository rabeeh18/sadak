# Stops the local Fleet PostgreSQL server.
. (Join-Path $PSScriptRoot 'env.ps1')

& pg_ctl status -D $env:PGDATA *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'PostgreSQL is not running.'
    exit 0
}
& pg_ctl stop -D $env:PGDATA -m fast -w
exit $LASTEXITCODE
