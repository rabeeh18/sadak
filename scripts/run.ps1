# Starts the Sadak app on http://localhost:8080 (PostgreSQL runs as a Windows service).
#   scripts/run.ps1               demo mode: synthetic Punjab data, simulator, quick sign-in
#   scripts/run.ps1 -Production   production mode: empty company, real time, first admin from FLEET_ADMIN_*
param([switch]$Production)
. (Join-Path $PSScriptRoot 'env.ps1')
$service = Get-Service 'postgresql-x64-17' -ErrorAction SilentlyContinue
if ($service -and $service.Status -ne 'Running') {
    Write-Host 'PostgreSQL service is not running. Start it from Services (postgresql-x64-17) and try again.'
    exit 1
}
if ($Production) {
    $env:SPRING_PROFILES_ACTIVE = 'prod'
    # Running locally over plain http: secure-only cookies would block sign-in. A real server uses HTTPS.
    if (-not $env:FLEET_SECURE_COOKIES) { $env:FLEET_SECURE_COOKIES = 'false' }
}
Set-Location (Join-Path $PSScriptRoot '..')
# Keep the JVM small: the app needs well under 512 MB.
$env:MAVEN_OPTS = '-Xmx256m'
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.jvmArguments=-Xms128m -Xmx384m -XX:+UseSerialGC"
