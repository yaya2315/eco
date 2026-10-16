# Eco · dejar de encender solo con Windows
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Write-Host ""
Stop-ScheduledTask -TaskName "Eco" -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName "Eco" -Confirm:$false -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match 'eco_app\.pyw' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
foreach ($carpeta in @([Environment]::GetFolderPath("Desktop"), [Environment]::GetFolderPath("Programs"))) {
    Remove-Item (Join-Path $carpeta "Eco.lnk") -ErrorAction SilentlyContinue
}
Write-Host "  Listo: Eco ya no enciende solo. Tus notas y datos siguen en su lugar."
Write-Host "  Puedes encenderlo a mano con iniciar.bat."
Write-Host "  (La energía se queda como está; cámbiala en Configuración > Sistema > Energía si quieres.)"
