@echo off
chcp 65001 >nul
cd /d "%~dp0"
if /i "%~1"=="--admin" goto admin
echo   Windows te va a pedir permiso de administrador: toca Si.
powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '--admin' -Verb RunAs"
exit /b

:admin
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0desinstalar_inicio.ps1"
echo.
pause
