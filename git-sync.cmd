@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0git-sync.ps1" %*
endlocal
