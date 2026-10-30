# Starts the database (if needed) and the Fleet Operations app on http://localhost:8080
. (Join-Path $PSScriptRoot 'env.ps1')
& (Join-Path $PSScriptRoot 'start-db.ps1')
Set-Location (Join-Path $PSScriptRoot '..')
# Keep the JVM small: the app needs well under 512 MB.
$env:MAVEN_OPTS = '-Xmx256m'
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.jvmArguments=-Xms128m -Xmx384m -XX:+UseSerialGC"
