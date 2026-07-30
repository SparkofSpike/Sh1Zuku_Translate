# auto_update.ps1 - 服务器自动拉取 GitHub Actions 构建产物并重启服务
# 由 Windows 计划任务定时触发（建议每 5 分钟）
# 用法: powershell -File auto_update.ps1

param(
    [string]$DeployPath = "D:\Sh1ZukuTranslate",
    [string]$RepoOwner = "SparkofSpike",
    [string]$RepoName = "Sh1Zuku_Translate",
    [string]$ArtifactName = "shizuku-deploy",
    [string]$StateFile = "$env:TEMP\shizuku_auto_update_state.txt"
)

# ===== 从 Windows Credential Manager 读取 GitHub Token =====
function Get-GitHubToken {
    # 尝试从环境变量读取
    $token = $env:GITHUB_TOKEN
    if ($token) { return $token }

    # 尝试从 credential manager 读取
    try {
        $cred = [System.Net.CredentialCache]::DefaultCredentials
        # Fallback: check gh CLI config
        $ghConfig = "$env:USERPROFILE\.config\gh\hosts.yml"
        if (Test-Path $ghConfig) {
            $content = Get-Content $ghConfig -Raw
            if ($content -match 'oauth_token:\s*(\S+)') {
                return $Matches[1]
            }
        }
    } catch {}

    return $null
}

# ===== 获取最新成功构建的 workflow run 的 artifact =====
function Get-LatestArtifactUrl {
    param([string]$Token)

    $headers = @{
        "Accept" = "application/vnd.github+json"
        "Authorization" = "Bearer $Token"
    }

    # 获取最新成功构建的 run
    $runsUrl = "https://api.github.com/repos/$RepoOwner/$RepoName/actions/runs?status=success&branch=main&per_page=1"
    try {
        $runs = Invoke-RestMethod -Uri $runsUrl -Headers $headers -TimeoutSec 15
        if (-not $runs.workflow_runs -or $runs.workflow_runs.Count -eq 0) {
            Write-Host "No successful runs found" -ForegroundColor Yellow
            return $null
        }

        $latestRun = $runs.workflow_runs[0]
        $runId = $latestRun.id
        $runCreatedAt = $latestRun.created_at

        # 检查是否已经是最新
        if (Test-Path $StateFile) {
            $lastRunId = (Get-Content $StateFile).Trim()
            if ($lastRunId -eq $runId) {
                Write-Host "Already up to date (run #$runId)" -ForegroundColor Green
                return $null, $null
            }
        }

        # 获取 artifact 下载 URL
        $artifactsUrl = "https://api.github.com/repos/$RepoOwner/$RepoName/actions/runs/$runId/artifacts"
        $artifacts = Invoke-RestMethod -Uri $artifactsUrl -Headers $headers -TimeoutSec 15
        foreach ($artifact in $artifacts.artifacts) {
            if ($artifact.name -eq $ArtifactName) {
                Write-Host "Found artifact #$runId from $runCreatedAt" -ForegroundColor Cyan
                return $artifact.archive_download_url, $runId
            }
        }
        Write-Host "Artifact '$ArtifactName' not found in run #$runId" -ForegroundColor Yellow
        return $null, $null
    } catch {
        Write-Host "API request failed: $_" -ForegroundColor Red
        return $null, $null
    }
}

# ===== 停止服务 =====
function Stop-Services {
    Write-Host "Stopping services..." -ForegroundColor Yellow
    Get-Process -Name "java" -ErrorAction SilentlyContinue | Stop-Process -Force
    Get-Process -Name "python" -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 3
    Write-Host "  [OK] Services stopped" -ForegroundColor Green
}

# ===== 部署新版本 =====
function Deploy-NewVersion {
    param([string]$ZipPath)

    Write-Host "Deploying new version..." -ForegroundColor Yellow

    # 备份当前版本
    $backupDir = "$DeployPath\backup"
    if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Path $backupDir -Force | Out-Null }
    $backupPath = "$backupDir\backup_$(Get-Date -Format 'yyyyMMdd_HHmmss').zip"
    if (Test-Path "$DeployPath\translator.jar") {
        Compress-Archive -Path "$DeployPath\translator.jar", "$DeployPath\ocr-worker", "$DeployPath\deploy.ps1" -DestinationPath $backupPath -Force
        Write-Host "  Backup saved to $backupPath" -ForegroundColor Gray
    }

    # 解压新版本到临时目录
    $tempDir = "$env:TEMP\shizuku_deploy_$(Get-Random)"
    Expand-Archive -Path $ZipPath -DestinationPath $tempDir -Force

    # 复制文件到部署目录（排除 data/）
    Copy-Item -Path "$tempDir\deploy_package\translator.jar" -Destination "$DeployPath\translator.jar" -Force
    if (Test-Path "$DeployPath\ocr-worker") {
        Remove-Item "$DeployPath\ocr-worker" -Recurse -Force
    }
    Copy-Item -Path "$tempDir\deploy_package\ocr-worker" -Destination "$DeployPath\ocr-worker" -Recurse -Force
    Copy-Item -Path "$tempDir\deploy_package\deploy.ps1" -Destination "$DeployPath\deploy.ps1" -Force

    # 清理临时文件
    Remove-Item $tempDir -Recurse -Force
    Write-Host "  [OK] Files deployed" -ForegroundColor Green
}

# ===== 启动服务 =====
function Start-Services {
    param([string]$logDir)

    Write-Host "Starting services..." -ForegroundColor Yellow
    $pythonPath = "C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe"
    if (-not (Test-Path $pythonPath)) { $pythonPath = "python" }

    # OCR
    Start-Process -FilePath $pythonPath -ArgumentList "$DeployPath\ocr-worker\ocr_server.py" -WindowStyle Hidden -RedirectStandardOutput "$logDir\ocr.log" -RedirectStandardError "$logDir\ocr.log" -NoNewWindow
    Write-Host "  OCR service started" -ForegroundColor Green

    Start-Sleep -Seconds 5

    # Backend
    Start-Process -FilePath "java" -ArgumentList "-jar $DeployPath\translator.jar" -WindowStyle Hidden -RedirectStandardOutput "$logDir\backend.log" -RedirectStandardError "$logDir\backend.log" -NoNewWindow
    Write-Host "  Backend started" -ForegroundColor Green

    Start-Sleep -Seconds 3

    # Verify
    $verified = $true
    try {
        $ocr = Invoke-WebRequest -Uri "http://localhost:5557/health" -UseBasicParsing -TimeoutSec 5
        if ($ocr.Content -match 'ok') { Write-Host "  [OK] OCR health check passed" -ForegroundColor Green }
        else { Write-Host "  [WARN] OCR unusual response" -ForegroundColor Yellow; $verified = $false }
    } catch { Write-Host "  [WARN] OCR not ready yet" -ForegroundColor Yellow; $verified = $false }

    try {
        $be = Invoke-WebRequest -Uri "http://localhost:5566/api/v1/ocr/health" -UseBasicParsing -TimeoutSec 5
        if ($be.Content -match 'ok') { Write-Host "  [OK] Backend health check passed" -ForegroundColor Green }
        else { Write-Host "  [WARN] Backend unusual response" -ForegroundColor Yellow; $verified = $false }
    } catch { Write-Host "  [WARN] Backend not ready yet" -ForegroundColor Yellow; $verified = $false }

    return $verified
}

# ===================== Main =====================
Write-Host "===========================================" -ForegroundColor Cyan
Write-Host " ShizukuTranslate Auto Update" -ForegroundColor Cyan
Write-Host " $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
Write-Host "===========================================" -ForegroundColor Cyan
Write-Host ""

# 确保日志目录存在
$logDir = "$DeployPath\logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

# 获取 Token
$token = Get-GitHubToken
if (-not $token) {
    Write-Host "[ERROR] GitHub token not found" -ForegroundColor Red
    Write-Host "Set GITHUB_TOKEN env var or run: gh auth login" -ForegroundColor Yellow
    exit 1
}

# 检查更新
$downloadUrl, $runId = Get-LatestArtifactUrl -Token $token
if (-not $downloadUrl) {
    exit 0
}

# 下载 artifact
$zipPath = "$env:TEMP\shizuku_deploy_latest.zip"
Write-Host "Downloading artifact..." -ForegroundColor Yellow
try {
    $headers = @{ "Authorization" = "Bearer $token" }
    Invoke-WebRequest -Uri $downloadUrl -Headers $headers -OutFile $zipPath -TimeoutSec 120
    Write-Host "  [OK] Downloaded ($([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MB)" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Download failed: $_" -ForegroundColor Red
    exit 1
}

# 部署
Stop-Services
Deploy-NewVersion -ZipPath $zipPath
$success = Start-Services -logDir $logDir

# 清理
Remove-Item $zipPath -Force -ErrorAction SilentlyContinue

# 记录状态
$runId | Out-File -FilePath $StateFile -Force

Write-Host ""
if ($success) {
    Write-Host "===========================================" -ForegroundColor Cyan
    Write-Host " Update complete! Run #$runId" -ForegroundColor Cyan
    Write-Host " Open: http://localhost:5566" -ForegroundColor Cyan
    Write-Host "===========================================" -ForegroundColor Cyan
} else {
    Write-Host "===========================================" -ForegroundColor Yellow
    Write-Host " Update applied but health checks incomplete" -ForegroundColor Yellow
    Write-Host " Check logs in: $logDir" -ForegroundColor Yellow
    Write-Host "===========================================" -ForegroundColor Yellow
}
