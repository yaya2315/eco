@echo off
rem Ejecutar UNA vez como administrador (clic derecho > Ejecutar como administrador).
rem Deja que el telefono hable con Eco por el puerto 8765, solo en redes privadas (tu Wi-Fi de casa).
netsh advfirewall firewall add rule name="Eco servidor" dir=in action=allow protocol=TCP localport=8765 profile=private
pause
