# Run every test stage and print one PASS/FAIL line per stage. Needs Docker Desktop, a JDK 21+ and Python 3.10+.
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force .hexpath | Out-Null
if (-not (Test-Path .venv)) { python -m venv .venv }
$py = (Resolve-Path ".venv\Scripts\python.exe").Path
& $py -m pip install -q -e "geo-pipeline[test]" -r e2e\requirements.txt
$results = @(); $failed = $false
function Stage($name, [scriptblock]$body) {
    $log = ".hexpath\verify-$name.log"
    & $body *> $log
    if ($LASTEXITCODE -eq 0) { $script:results += "PASS  $name" }
    else { $script:results += "FAIL  $name  ($log)"; $script:failed = $true }
}
Stage "python-unit" { Push-Location geo-pipeline; & $py -m pytest -q -m "not db"; Pop-Location }
Stage "python-db"   { Push-Location geo-pipeline; & $py -m pytest -q -m db; Pop-Location }
Stage "java"        { Push-Location geo-service; & .\mvnw.cmd -q verify; Pop-Location }
Stage "demo-stack"  { & powershell -ExecutionPolicy Bypass -File scripts\demo.ps1 }
Stage "e2e"         {
    Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
        $n, $v = $_ -split '=', 2; [Environment]::SetEnvironmentVariable($n.Trim(), $v.Trim(), "Process") }
    & $py -m pytest -q e2e
}
$results | ForEach-Object { Write-Host $_ }
if ($failed) { exit 1 } else { exit 0 }
