# Dot-source to prepare the current PowerShell session:
#   . .\scripts\env.ps1
# Finds the installed JDK 21 and PostgreSQL 17 and loads .env.local. Changes nothing outside this session.

# JAVA_HOME is set system-wide by the JDK installer; pick it up even in a shell opened before the install.
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine') }
if ($env:JAVA_HOME) { $env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH" }

# PostgreSQL's installer does not add psql to PATH.
$pgBin = 'C:\Program Files\PostgreSQL\17\bin'
if (Test-Path $pgBin) { $env:PATH = "$pgBin;$env:PATH" }

# Load local credentials (FLEET_DB_*, PG_SUPERUSER*, FLEET_DEMO_PASSWORD) into the session.
$envFile = Join-Path $PSScriptRoot '..\.env.local'
if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
        $name, $value = $_ -split '=', 2
        Set-Item -Path "env:$($name.Trim())" -Value $value.Trim()
    }
}
$env:PGHOST = $env:FLEET_DB_HOST
$env:PGPORT = $env:FLEET_DB_PORT
