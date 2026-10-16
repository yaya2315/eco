# Eco · encender solo con Windows (lo ejecuta instalar_inicio.bat como administrador)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$dir     = Split-Path -Parent $MyInvocation.MyCommand.Path
$pyw     = Join-Path $dir ".venv\Scripts\pythonw.exe"
$app     = Join-Path $dir "eco_app.pyw"
$icono   = Join-Path $dir "eco.ico"
$usuario = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$puerto  = 8765
$cfg = Join-Path $dir "config.toml"
if (Test-Path $cfg) {
    $m = Select-String -Path $cfg -Pattern '^\s*puerto\s*=\s*(\d+)' | Select-Object -First 1
    if ($m) { $puerto = [int]$m.Matches[0].Groups[1].Value }
}

function Paso($texto) { Write-Host "  $texto" }
Write-Host ""
if (-not (Test-Path $pyw)) {
    Write-Host "  No encuentro Python de Eco ($pyw). Corre primero instalar.bat." -ForegroundColor Red
    exit 1
}

Paso "1/5  Cerrando Eco si estaba abierto..."
Stop-ScheduledTask -TaskName "Eco" -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match 'eco_servidor\.py|eco_app\.pyw' } |
    ForEach-Object { Invoke-CimMethod -InputObject $_ -MethodName Terminate | Out-Null }
Start-Sleep -Seconds 2

Paso "2/5  Haciendo que Eco encienda solo al iniciar Windows..."
$accion  = New-ScheduledTaskAction -Execute $pyw -Argument "`"$app`" --inicio" -WorkingDirectory $dir
$disparo = New-ScheduledTaskTrigger -AtLogOn -User $usuario
$ajustes = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
               -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
               -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
$quien   = New-ScheduledTaskPrincipal -UserId $usuario -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName "Eco" -Description "Eco: recibe los momentos del teléfono (Black Hole Code)" `
    -Action $accion -Trigger $disparo -Settings $ajustes -Principal $quien -Force | Out-Null

Paso "3/5  Ajustando la energía: conectada a la corriente, no se duerme ni con la tapa cerrada..."
powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0 | Out-Null
powercfg /change standby-timeout-ac 0 | Out-Null
powercfg /change hibernate-timeout-ac 0 | Out-Null
powercfg /setactive SCHEME_CURRENT | Out-Null

Paso "4/5  Creando el acceso directo de Eco (escritorio y menú Inicio)..."
$shell = New-Object -ComObject WScript.Shell
foreach ($carpeta in @([Environment]::GetFolderPath("Desktop"), [Environment]::GetFolderPath("Programs"))) {
    $lnk = $shell.CreateShortcut((Join-Path $carpeta "Eco.lnk"))
    $lnk.TargetPath       = $pyw
    $lnk.Arguments        = "`"$app`""
    $lnk.WorkingDirectory = $dir
    $lnk.IconLocation     = "$icono,0"
    $lnk.Description      = "Abrir el panel de Eco"
    $lnk.Save()
}

Paso "5/5  Encendiendo Eco..."
Start-ScheduledTask -TaskName "Eco"
$listo = $false
for ($i = 0; $i -lt 20 -and -not $listo; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$puerto/" -UseBasicParsing -TimeoutSec 2
        $listo = ($r.StatusCode -eq 200)
    } catch { }
}

Write-Host ""
if ($listo) {
    Write-Host "  Listo: Eco ya corre en segundo plano." -ForegroundColor Green
    Write-Host "  - Busca su ícono de anillos junto al reloj (si no lo ves, toca la flechita ^)."
    Write-Host "  - Tu panel: abre el acceso directo 'Eco' del escritorio, o http://localhost:$puerto"
    Write-Host "  - Desde ahora enciende solo cada vez que inicias sesión en Windows."
} else {
    Write-Host "  Eco quedó instalado pero todavía no responde." -ForegroundColor Yellow
    Write-Host "  Espera un minuto y abre el acceso directo 'Eco'. Si sigue igual, revisa servidor\datos\eco.log"
}
