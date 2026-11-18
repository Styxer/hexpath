# Start the whole stack locally: PostGIS, a published release of the committed OSM extract, the service.
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
}
docker compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw "docker compose failed - is Docker Desktop running?" }
if (-not (Test-Path .venv)) { python -m venv .venv }
$py = ".venv\Scripts\python.exe"
& $py -m pip install -q -e geo-pipeline
& $py -m geopipeline migrate
if ($LASTEXITCODE -ne 0) { throw "migrate failed" }
& $py -m geopipeline run --source osm
if ($LASTEXITCODE -ne 0) { throw "pipeline run failed (exit $LASTEXITCODE)" }
New-Item -ItemType Directory -Force .hexpath | Out-Null
Start-Process -FilePath "geo-service\mvnw.cmd" -ArgumentList "-q","spring-boot:run" -WorkingDirectory "geo-service" `
    -RedirectStandardOutput ".hexpath\service.log" -RedirectStandardError ".hexpath\service.err.log" -WindowStyle Hidden
Write-Host -NoNewline "waiting for the service"
for ($i = 0; $i -lt 120; $i++) {
    try {
        Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health/readiness | Out-Null
        Write-Host " ready: http://localhost:8080/"
        Start-Process "http://localhost:8080/"
        exit 0
    } catch { Write-Host -NoNewline "."; Start-Sleep 2 }
}
Write-Host ""; Write-Host "service did not become ready; see .hexpath\service.log"; exit 1
