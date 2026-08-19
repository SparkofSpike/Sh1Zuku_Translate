@echo off
rem Pixiv Novel Translator - one-click install / update (Windows)
rem Prefer the standalone updater bundled inside tranShilator-plugin.
rem Usage: double-click, or run "install.cmd --force".
chcp 65001 >nul
if exist "%~dp0tranShilator-plugin\CheckUpdate.exe" (
  "%~dp0tranShilator-plugin\CheckUpdate.exe" %*
  exit /b %errorlevel%
)
rem Keep compatibility with packages that placed the updater beside this script.
if exist "%~dp0CheckUpdate.exe" (
  "%~dp0CheckUpdate.exe" %*
  exit /b %errorlevel%
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
pause
