# deploy.ps1 - 在 Windows 服务器上手动重启 ShizukuTranslate 服务
# 复制到服务器部署目录后可直接运行
# 用法: powershell -File deploy.ps1

param(
    [string]$ServicePath = $PSScriptRoot
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " ShizukuTranslate Service Manager" -ForegroundColor Cyan
Write-Host " Path: $ServicePath" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Stop existing services
Write-Host "[1/4] Stopping existing services..." -ForegroundColor Yellow
Get-Process -Name "java" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "python" -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3
Write-Host "  [OK] Services stopped" -ForegroundColor Green

# Start OCR service
Write-Host "[2/4] Starting OCR service (port 5557)..." -ForegroundColor Yellow
$ocrLog = Join-Path $ServicePath "logs\ocr.log"
$ocrExe = Join-Path $ServicePath "ocr-worker\ocr_server.py"

# Find Python 3.12
$pythonPath = "C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe"
if (-not (Test-Path $pythonPath)) {
    # Fallback: try python in PATH
    $pythonPath = "python"
}

$ocrProc = Start-Process -FilePath $pythonPath -ArgumentList $ocrExe -WindowStyle Hidden -RedirectStandardOutput $ocrLog -RedirectStandardError $ocrLog -PassThru -NoNewWindow
Start-Sleep -Seconds 5
Write-Host "  [OK] OCR service PID: $($ocrProc.Id)" -ForegroundColor Green

# Start Java backend
Write-Host "[3/4] Starting Java backend (port 5566)..." -ForegroundColor Yellow
$backendLog = Join-Path $ServicePath "logs\backend.log"
$jarFile = Join-Path $ServicePath "translator.jar"

$backendProc = Start-Process -FilePath "java" -ArgumentList "-jar $jarFile" -WindowStyle Hidden -RedirectStandardOutput $backendLog -RedirectStandardError $backendLog -PassThru -NoNewWindow
Start-Sleep -Seconds 3
Write-Host "  [OK] Java backend PID: $($backendProc.Id)" -ForegroundColor Green

# Verify
Write-Host "[4/4] Verifying services..." -ForegroundColor Yellow
Start-Sleep -Seconds 2

# Check OCR
try {
    $ocrHealth = Invoke-WebRequest -Uri "http://localhost:5557/health" -UseBasicParsing -TimeoutSec 5
    if ($ocrHealth.Content -match '"status":"ok"') {
        Write-Host "  [OK] OCR service is healthy" -ForegroundColor Green
    } else {
        Write-Host "  [WARN] OCR health check returned: $($ocrHealth.Content)" -ForegroundColor Yellow
    }
} catch {
    Write-Host "  [WARN] OCR health check failed: $_" -ForegroundColor Yellow
}

# Check Backend
try {
    $backendHealth = Invoke-WebRequest -Uri "http://localhost:5566/api/v1/ocr/health" -UseBasicParsing -TimeoutSec 5
    if ($backendHealth.Content -match '"status":"ok"') {
        Write-Host "  [OK] Backend is healthy" -ForegroundColor Green
    } else {
        Write-Host "  [WARN] Backend health check returned: $($backendHealth.Content)" -ForegroundColor Yellow
    }
} catch {
    Write-Host "  [WARN] Backend health check failed: $_" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Deployment complete!" -ForegroundColor Cyan
Write-Host " Open: http://localhost:5566" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Save PIDs for later manual kill
$pids = @{
    OCR = $ocrProc.Id
    Backend = $backendProc.Id
}
$pids | ConvertTo-Json | Set-Content (Join-Path $ServicePath "logs\.pids.json")
