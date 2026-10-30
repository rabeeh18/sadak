# Dot-source to point the current PowerShell session at the project's tools:
#   . .\scripts\env.ps1
# Only affects this session; no system or user settings are changed.

$DevRoot = 'D:\Dev'
$env:JAVA_HOME = Join-Path $DevRoot 'jdk-21'
$env:PGHOME = Join-Path $DevRoot 'pgsql'
$env:PGDATA = Join-Path $env:PGHOME 'data'
$env:PATH = "$env:JAVA_HOME\bin;$env:PGHOME\bin;$env:PATH"
# Keep the Maven Wrapper's own download on D: as well (the repository is set in .mvn/maven.config).
$env:MAVEN_USER_HOME = Join-Path $DevRoot 'm2'

# Load local credentials (FLEET_DB_*, PG_SUPERUSER*) into the session.
$envFile = Join-Path $PSScriptRoot '..\.env.local'
if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
        $name, $value = $_ -split '=', 2
        Set-Item -Path "env:$($name.Trim())" -Value $value.Trim()
    }
}
$env:PGPORT = $env:FLEET_DB_PORT
$env:PGHOST = $env:FLEET_DB_HOST
