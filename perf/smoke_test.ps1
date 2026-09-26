param([string]$BaseUrl = "http://localhost:8080", [string]$FrontendUrl = "http://localhost:3000")
$ErrorActionPreference = "Stop"
function Assert-Http([string]$Path) {
  $response = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl$Path" -TimeoutSec 20
  if ($response.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($response.Content)) { throw "$Path failed" }
  Write-Host "PASS $Path"
}
Assert-Http "/"
Assert-Http "/actuator/health"
Assert-Http "/api/meta"
Assert-Http "/api/routes"
Assert-Http "/api/stops?route=1"
Assert-Http "/api/geometry"
Assert-Http "/api/dashboard?date=2025-11-08"
Assert-Http "/api/forecast?route=17&horizon=day&date=2025-11-08"
Assert-Http "/api/forecast?route=17&horizon=month&date=2025-11-08"
Assert-Http "/api/forecast?route=17&horizon=year&date=2025-11-08"
Assert-Http "/api/forecast?route=1&stop=Балаклавский%20проспект&horizon=day&date=2025-11-08"
Assert-Http "/api/external?date=2025-11-08"
$csv = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/api/export?format=csv&from=2025-11-08&to=2025-11-08"
if ($csv.StatusCode -ne 200 -or $csv.Content.Length -lt 50) { throw "CSV export failed" }
$xlsx = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/api/export?format=xlsx&from=2025-11-08&to=2025-11-08"
if ($xlsx.StatusCode -ne 200 -or $xlsx.RawContentStream.Length -lt 100) { throw "XLSX export failed" }
$frontend = Invoke-WebRequest -UseBasicParsing -Uri $FrontendUrl -TimeoutSec 20
if ($frontend.StatusCode -ne 200 -or $frontend.Content -notmatch "Прогноз") { throw "frontend failed" }
Write-Host "ALL SMOKE TESTS PASSED"
