@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-windows.ps1" %*
exit /b %ERRORLEVEL%
