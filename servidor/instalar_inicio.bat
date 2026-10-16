@echo off
chcp 65001 >nul
cd /d "%~dp0"
if /i "%~1"=="--admin" goto admin

echo.
echo   Instalando lo que necesita la app de Eco...
".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -q -r requirements.txt
if errorlevel 1 (
    echo.
    echo   No se pudo instalar. Revisa tu internet y vuelve a intentarlo.
    pause
    exit /b 1
)
echo   Ahora Windows te va a pedir permiso de administrador: toca Si.
powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '--admin' -Verb RunAs" || (
    echo.
    echo   No se dio el permiso. Vuelve a abrir instalar_inicio.bat y toca Si.
    pause
)
exit /b

:admin
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar_inicio.ps1"
echo.
pause
