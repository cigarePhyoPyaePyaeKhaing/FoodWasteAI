@echo off
setlocal
echo Starting FoodWaste AI with PowerShell launcher...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-foodwasteai.ps1" %*
endlocal
