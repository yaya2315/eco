@echo off
chcp 65001 >nul
rem Crea el Eco de otra persona (por ejemplo, tu abuelo) en esta laptop. Doble clic y sigue las preguntas.
cd /d "%~dp0"
if /i "%~1"=="--admin" goto admin
echo.
echo   Windows te va a pedir permiso de administrador (para que encienda solo y abrir su puerto): toca Si.
powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '--admin' -Verb RunAs" || (
    echo.
    echo   No se dio el permiso. Vuelve a abrir nuevo_perfil.bat y toca Si.
    pause
)
exit /b

:admin
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0nuevo_perfil.ps1"
echo.
pause
