# auto_update.ps1 - server-side auto deploy script
param([string]$DeployPath = "D:\Sh1ZukuTranslate")
$logDir = "$DeployPath\logs"
$stateFile = "$env:TEMP\shizuku_state.txt"

Write-Host "=== Shizuku Auto Update ==="
Write-Host (Get-Date -Format "yyyy-MM-dd HH:mm:ss")

if (-not $env:GITHUB_TOKEN) { Write-Host "ERROR: GITHUB_TOKEN not set" -ForegroundColor Red; exit 1 }

$headers = @{ "Authorization" = "Bearer $env:GITHUB_TOKEN"; "Accept" = "application/vnd.github+json" }

# Get latest successful run
try {
    $runs = Invoke-RestMethod -Uri "https://api.github.com/repos/SparkofSpike/Sh1Zuku_Translate/actions/runs?status=success&branch=main&per_page=1" -Headers $headers
    if (-not $runs.workflow_runs -or $runs.workflow_runs.Count -eq 0) { Write-Host "No runs found"; return }
    $runId = $runs.workflow_runs[0].id
    if ((Test-Path $stateFile) -and ((Get-Content $stateFile) -eq $runId)) { Write-Host "Already up to date"; return }
    Write-Host "Found run #$runId"
} catch { Write-Host "API error: $_" -ForegroundColor Red; return }

# Get artifact download URL
try {
    $arts = Invoke-RestMethod -Uri "https://api.github.com/repos/SparkofSpike/Sh1Zuku_Translate/actions/runs/$runId/artifacts" -Headers $headers
    $url = $null
    foreach ($a in $arts.artifacts) { if ($a.name -eq "shizuku-deploy") { $url = $a.archive_download_url; break } }
    if (-not $url) { Write-Host "Artifact not found"; return }
} catch { Write-Host "Artifact error: $_" -ForegroundColor Red; return }

# Download artifact
$zip = "$env:TEMP\shizuku.zip"
Write-Host "Downloading artifact..."
try { Invoke-WebRequest -Uri $url -Headers $headers -OutFile $zip -TimeoutSec 120; Write-Host "Downloaded" } catch { Write-Host "Download failed: $_" -ForegroundColor Red; return }

# Stop services
Write-Host "Stopping services..."
Get-Process java,python -ErrorAction SilentlyContinue | Stop-Process -Force; Start-Sleep 3

# Backup current version
$bkDir = "$DeployPath\backup"
if (-not (Test-Path $bkDir)) { mkdir $bkDir -Force | Out-Null }
if (Test-Path "$DeployPath\translator.jar") {
    Compress-Archive "$DeployPath\translator.jar","$DeployPath\ocr-worker","$DeployPath\deploy.ps1" "$bkDir\backup_$(Get-Date -Format yyyyMMdd_HHmmss).zip" -Force
}

# Extract and deploy new version
$tmp = "$env:TEMP\deploy_$(Get-Random)"
Expand-Archive $zip $tmp -Force
Copy-Item "$tmp\deploy_package\translator.jar" "$DeployPath\translator.jar" -Force
if (Test-Path "$DeployPath\ocr-worker") { Remove-Item "$DeployPath\ocr-worker" -Recurse -Force }
Copy-Item "$tmp\deploy_package\ocr-worker" "$DeployPath\ocr-worker" -Recurse -Force
Copy-Item "$tmp\deploy_package\deploy.ps1" "$DeployPath\deploy.ps1" -Force
Remove-Item $tmp -Recurse -Force; Remove-Item $zip -Force
Write-Host "Files deployed"

# Start services
Write-Host "Starting services..."
$py = "C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe"
Start-Process -FilePath $py -ArgumentList "$DeployPath\ocr-worker\ocr_server.py" -WindowStyle Hidden -RedirectStandardOutput "$logDir\ocr.log" -RedirectStandardError "$logDir\ocr.log" -NoNewWindow
Start-Sleep 5
Start-Process -FilePath "java" -ArgumentList "-jar $DeployPath\translator.jar" -WindowStyle Hidden -RedirectStandardOutput "$logDir\backend.log" -RedirectStandardError "$logDir\backend.log" -NoNewWindow
Start-Sleep 3

# Verify
$ok = $true
try { $r = Invoke-WebRequest "http://localhost:5557/health" -UseBasicParsing -TimeoutSec 5; if ($r.Content -match "ok") { Write-Host "OCR: OK" -ForegroundColor Green } else { $ok = $false } } catch { $ok = $false }
try { $r = Invoke-WebRequest "http://localhost:5566/api/v1/ocr/health" -UseBasicParsing -TimeoutSec 5; if ($r.Content -match "ok") { Write-Host "Backend: OK" -ForegroundColor Green } else { $ok = $false } } catch { $ok = $false }

$runId | Out-File $stateFile -Force
if ($ok) { Write-Host "=== Deploy OK ===" -ForegroundColor Green } else { Write-Host "=== Deploy with warnings ===" -ForegroundColor Yellow }
