@echo off
chcp 65001 >nul
rem Clic derecho > Ejecutar como administrador. Se puede correr las veces que quieras.
rem Abre el puerto 8765 de Eco solo para: tu Wi-Fi privado y tus dispositivos de Tailscale.
net session >nul 2>&1
if not %errorlevel%==0 (
    echo.
    echo   Hay que ejecutarlo como administrador: clic derecho ^> Ejecutar como administrador.
    echo.
    pause
    exit /b 1
)
netsh advfirewall firewall delete rule name="Eco servidor" >nul 2>&1
netsh advfirewall firewall delete rule name="Eco servidor (Tailscale)" >nul 2>&1
netsh advfirewall firewall add rule name="Eco servidor" dir=in action=allow protocol=TCP localport=8765 profile=private >nul
netsh advfirewall firewall add rule name="Eco servidor (Tailscale)" dir=in action=allow protocol=TCP localport=8765 remoteip=100.64.0.0/10 profile=any >nul
echo.
echo   Listo: Eco abierto en tu Wi-Fi privado y por Tailscale.
echo.
pause
