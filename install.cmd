@echo off
rem Pixiv Novel Translator - one-click install / update (Windows)
rem Prefer the standalone updater when it is bundled beside this script.
rem Usage: double-click, or run "install.cmd --chrome" / "install.cmd --force".
chcp 65001 >nul
if exist "%~dp0CheckUpdate.exe" (
  "%~dp0CheckUpdate.exe" %*
  exit /b %errorlevel%
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
pause
