@echo off
rem Pixiv Novel Translator - one-click install / update (Windows)
rem Usage: double-click to install, or run "install.cmd -Update" to update.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
pause
