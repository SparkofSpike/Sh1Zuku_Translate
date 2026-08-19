# ============================================================
# Pixiv Novel Translator - one-click install / update (Windows)
# ============================================================
# Downloads the latest release from GitHub, extracts it to
# %LOCALAPPDATA%\PixivNovelTranslator\ and prints next-step
# instructions. Run via install.cmd (double-click friendly).
#
#   install.ps1           -> install (or update if already present)
#   install.ps1 -Update   -> force update over the existing copy
# ============================================================

param([switch]$Update)

$ErrorActionPreference = 'Stop'
$repo = 'SparkofSpike/Sh1Zuku_Translate'
$rootDir = Join-Path $env:LOCALAPPDATA 'PixivNovelTranslator'
$extDir = Join-Path $rootDir 'tranShilator-plugin'
$legacyExtDir = Join-Path $rootDir 'pixiv-novel-translator'

if ((Test-Path (Join-Path $legacyExtDir 'manifest.json')) -and -not (Test-Path (Join-Path $extDir 'manifest.json'))) {
    New-Item -ItemType Directory -Path $rootDir -Force | Out-Null
    Move-Item -Path $legacyExtDir -Destination $extDir
}

Write-Host '==============================================' -ForegroundColor Cyan
Write-Host '  Pixiv Novel Translator - 安装 / 更新' -ForegroundColor Cyan
Write-Host '==============================================' -ForegroundColor Cyan

if (-not $Update -and (Test-Path (Join-Path $extDir 'manifest.json'))) {
    Write-Host ''
    Write-Host '检测到已安装。本次执行将更新到最新版本。' -ForegroundColor Yellow
    Write-Host '（如需强制更新可运行: install.cmd -Update）'
    $Update = $true
}

# ── 1. Resolve the latest release zip ──────────────────────
Write-Host ''
Write-Host '[1/3] 正在从 GitHub 获取最新版本...' -ForegroundColor Cyan
$apiUrl = "https://api.github.com/repos/$repo/releases/latest"
$zipUrl = $null
$tag = $null
try {
    $rel = Invoke-RestMethod -Uri $apiUrl -Headers @{ 'User-Agent' = 'tranShilator-plugin-installer' } -TimeoutSec 20
    $tag = $rel.tag_name
    $asset = $rel.assets | Where-Object { $_.name -match '^tranShilator-plugin-.*\.zip$' } | Select-Object -First 1
    if (-not $asset) {
        $asset = $rel.assets | Where-Object { $_.name -match '^pixiv-novel-translator-.*\.zip$' } | Select-Object -First 1
    }
    if ($asset) { $zipUrl = $asset.browser_download_url }
} catch {
    Write-Host "  无法访问 GitHub API: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host '  （请检查网络/代理；仓库需为公开状态）' -ForegroundColor Yellow
    exit 1
}
if (-not $zipUrl) {
    # Fallback: GitHub "latest download" shortcut for a
    # tranShilator-plugin.zip asset.
    $zipUrl = "https://github.com/$repo/releases/latest/download/tranShilator-plugin.zip"
}
Write-Host "  最新版本: $tag"

# ── 2. Download & extract ──────────────────────────────────
Write-Host ''
Write-Host '[2/3] 正在下载并解压...' -ForegroundColor Cyan
$tmpZip = Join-Path $env:TEMP "pnt-$([guid]::NewGuid().ToString('N')).zip"
$tmpDir = Join-Path $env:TEMP "pnt-$([guid]::NewGuid().ToString('N'))"
try {
    Invoke-WebRequest -Uri $zipUrl -OutFile $tmpZip -UseBasicParsing -TimeoutSec 60
    Expand-Archive -Path $tmpZip -DestinationPath $tmpDir -Force

    # Locate the extension folder (zip may or may not have a top-level folder).
    $candidate = Join-Path $tmpDir 'tranShilator-plugin'
    if (-not (Test-Path (Join-Path $candidate 'manifest.json'))) {
        $candidate = Join-Path $tmpDir 'pixiv-novel-translator'
    }
    if (-not (Test-Path (Join-Path $candidate 'manifest.json'))) {
        $candidate = Get-ChildItem -Path $tmpDir -Recurse -Filter manifest.json |
            Where-Object { $_.DirectoryName -like '*tranShilator-plugin*' -or $_.DirectoryName -like '*pixiv-novel-translator*' } |
            Select-Object -First 1 -ExpandProperty DirectoryName
    }
    if (-not $candidate -or -not (Test-Path (Join-Path $candidate 'manifest.json'))) {
        throw '压缩包中未找到 tranShilator-plugin 扩展目录'
    }

    New-Item -ItemType Directory -Path $rootDir -Force | Out-Null
    if (Test-Path $extDir) { Remove-Item $extDir -Recurse -Force }
    Copy-Item $candidate $extDir -Recurse
} finally {
    Remove-Item $tmpZip, $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}

# ── 3. Next steps ──────────────────────────────────────────
Write-Host ''
Write-Host '[3/3] 完成！' -ForegroundColor Green
Write-Host ''
Write-Host '扩展已安装到:' -ForegroundColor Cyan
Write-Host "  $extDir"
Write-Host ''
if ($Update) {
    Write-Host '下一步（更新）:' -ForegroundColor Yellow
    Write-Host '  1. 打开浏览器，访问 edge://extensions（Chrome 用 chrome://extensions）'
    Write-Host '  2. 找到 Pixiv Novel Translator，点击卡片上的【刷新】图标'
    Write-Host '  3. 刷新打开的 Pixiv 页面，即可使用新版本'
} else {
    Write-Host '下一步（首次安装）:' -ForegroundColor Yellow
    Write-Host '  1. 打开浏览器，访问 edge://extensions（Chrome 用 chrome://extensions）'
    Write-Host '  2. 打开右上角【开发人员模式】开关'
    Write-Host '  3. 点击【加载解压缩的扩展】，选择上面的目录'
    Write-Host '  4. 打开 Pixiv 小说页面即可使用'
}
Write-Host ''
Write-Host '之后有新版时，再次双击 install.cmd 即可一键更新。' -ForegroundColor Green
