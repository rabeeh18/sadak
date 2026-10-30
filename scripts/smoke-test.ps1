# Signs in as a demo user and requests every main page; reports any that do not return 200.
param([string]$Base = 'http://localhost:8080', [string]$As = 'admin')
$ProgressPreference = 'SilentlyContinue'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$login = Invoke-WebRequest "$Base/login" -WebSession $session -UseBasicParsing
$token = [regex]::Match($login.Content, 'name="_csrf" value="([^"]+)"').Groups[1].Value
Invoke-WebRequest "$Base/demo-login" -Method Post -Body @{ as = $As; _csrf = $token } -WebSession $session -UseBasicParsing -MaximumRedirection 5 | Out-Null

Invoke-RestMethod "$Base/api/tower/snapshot" -WebSession $session | Out-Null
$tripId = (Invoke-WebRequest "$Base/trips?status=IN_PROGRESS" -WebSession $session -UseBasicParsing).Links.href |
    Where-Object { $_ -match '^/trips/\d+$' } | Select-Object -First 1 | ForEach-Object { $_ -replace '/trips/', '' }
$incidentId = (Invoke-WebRequest "$Base/incidents" -WebSession $session -UseBasicParsing).Links.href | Where-Object { $_ -match '^/incidents/\d+$' } | Select-Object -First 1
$shipmentLink = (Invoke-WebRequest "$Base/shipments" -WebSession $session -UseBasicParsing).Links.href | Where-Object { $_ -match '^/shipments/\d+$' } | Select-Object -First 1

$pages = @('/tower', '/trips', "/trips/$tripId", '/planning', '/incidents', $incidentId, '/shipments', $shipmentLink, '/shipments/new',
           '/fleet/vehicles', '/fleet/vehicles/1', '/fleet/drivers', '/fleet/drivers/1', '/fleet/warehouses', '/fleet/maintenance',
           '/analytics', '/analytics/export.csv', '/admin/settings', '/admin/users', '/admin/audit', "/api/tower/trips/$tripId", '/api/notifications')
$failed = 0
foreach ($p in $pages | Where-Object { $_ }) {
    try {
        $r = Invoke-WebRequest "$Base$p" -WebSession $session -UseBasicParsing
        $flag = if ($r.Content -match 'Something went wrong|Error 500') { ' (error page)' } else { '' }
        if ($flag) { $failed++ }
        "{0,-28} {1}{2}" -f $p, $r.StatusCode, $flag
    } catch {
        $failed++
        "{0,-28} FAILED {1}" -f $p, $_.Exception.Response.StatusCode
    }
}
"failures: $failed"
