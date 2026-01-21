@echo off
setlocal
set ROOT=%~dp0
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%InstallVoiceger.ps1" -Root "%ROOT%"
exit /b %ERRORLEVEL%
