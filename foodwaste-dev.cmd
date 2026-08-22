@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0foodwaste-dev.ps1" %*
endlocal
