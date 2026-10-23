# Eco · crear el Eco de otra persona en esta laptop (lo ejecuta nuevo_perfil.bat como administrador)
# Su propio puerto, sus datos, su token y su bot de Telegram. Usa la misma IA y el mismo Whisper que el tuyo.
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
[Console]::InputEncoding  = [Text.Encoding]::UTF8

$dir      = Split-Path -Parent $MyInvocation.MyCommand.Path
$pyw      = Join-Path $dir ".venv\Scripts\pythonw.exe"
$app      = Join-Path $dir "eco_app.pyw"
$icono    = Join-Path $dir "eco.ico"
$usuario  = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = Join-Path $dir "config.toml"

function Paso($texto) { Write-Host "  $texto" }
function Falla($texto) { Write-Host ""; Write-Host "  $texto" -ForegroundColor Red; exit 1 }
function Toml($texto) { return ($texto -replace '\\', '\\' -replace '"', '\"') }

Write-Host ""
Write-Host "  Eco para otra persona" -ForegroundColor Cyan
Write-Host "  Corre en esta laptop junto al tuyo, con sus propios datos y su propio Telegram."
Write-Host ""
if (-not (Test-Path $pyw)) { Falla "No encuentro Python de Eco ($pyw). Corre primero instalar.bat." }
if (-not (Test-Path $principal)) { Falla "Primero configura tu Eco (servidor\config.toml): este perfil usa su misma IA." }

# ── 1. Para quién ──
$persona = (Read-Host "  ¿Para quién es? Escribe su nombre (por ejemplo: Abuelo Juan)").Trim()
if (-not $persona) { $persona = "Abuelo" }
$id = $persona.ToLowerInvariant().Normalize([Text.NormalizationForm]::FormD)
$id = ($id -replace '\p{Mn}', '' -replace '[^a-z0-9]+', '-').Trim('-')
if ($id.Length -gt 30) { $id = $id.Substring(0, 30).Trim('-') }
if (-not $id) { $id = "abuelo" }
$carpeta = Join-Path $dir "perfiles\$id"
$cfg     = Join-Path $carpeta "config.toml"

function PuertoDe($ruta) {
    if (-not (Test-Path $ruta)) { return $null }
    $m = Select-String -Path $ruta -Pattern '^\s*puerto\s*=\s*(\d+)' | Select-Object -First 1
    if ($m) { return [int]$m.Matches[0].Groups[1].Value } else { return $null }
}

if (Test-Path $cfg) {
    Write-Host ""
    Paso "Ya existe el Eco de '$id': no toco sus datos ni su configuración, solo lo vuelvo a dejar encendiendo con Windows."
    $puerto = PuertoDe $cfg
    if (-not $puerto) { Falla "No encuentro el puerto en $cfg" }
} else {
    # ── 2. Puerto libre (el tuyo es 8765) ──
    $usados = @(8765)
    $p = PuertoDe $principal; if ($p) { $usados += $p }
    Get-ChildItem (Join-Path $dir "perfiles") -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $p = PuertoDe (Join-Path $_.FullName "config.toml"); if ($p) { $usados += $p }
    }
    $escuchando = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | ForEach-Object { $_.LocalPort })
    $puerto = 8766
    while ($usados -contains $puerto -or $escuchando -contains $puerto) { $puerto++ }

    # ── 3. Su token (nadie lo tiene que escribir: viaja en el QR o en el enlace) ──
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $token = -join ($bytes | ForEach-Object { $_.ToString("x2") })

    # ── 4. Su bot de Telegram ──
    Write-Host ""
    Paso "Su bot de Telegram: en Telegram habla con @BotFather, escribe /newbot y copia el token que te da."
    Paso "(Tiene que ser un bot NUEVO, no el tuyo. Para pegar aquí: clic derecho.)"
    $tuyo = ""
    $m = Select-String -Path $principal -Pattern '^\s*token\s*=\s*"(\d+:[^"]+)"' | Select-Object -First 1
    if ($m) { $tuyo = $m.Matches[0].Groups[1].Value }
    $tg = ""
    while ($true) {
        $tg = (Read-Host "  Token del bot (Enter para dejarlo para después)").Trim()
        if (-not $tg) { break }
        if ($tg -notmatch '^\d{5,}:[A-Za-z0-9_-]{30,}$') { Paso "Eso no parece un token de bot (se ve como 1234567890:AAH...). Prueba otra vez."; continue }
        if ($tg -eq $tuyo) { Paso "Ese es el bot de tu Eco. Crea otro con /newbot para esta persona."; continue }
        break
    }

    New-Item -ItemType Directory -Force -Path $carpeta | Out-Null
    $texto = @"
# Eco de $persona · lo creó nuevo_perfil.bat. Este archivo NUNCA se sube a GitHub.
# Usa la misma IA y el mismo Whisper que tu Eco (servidor\config.toml). Lo que pongas aquí manda.

[usuario]
nombre = "$(Toml $persona)"

[servidor]
puerto = $puerto
token = "$token"

[telegram]
token = "$(Toml $tg)"
chat_id = ""   # se llena solo cuando toca «Abrir Telegram» en su página de instalación

[funciones]
clases = false        # sin Modo clase

[google]
activo = false        # sin Classroom ni Calendar

[whisper]
liberar_min = 20      # si no manda audios en 20 min, Whisper sale de la memoria (tu laptop respira)

[salud]
tecnicas = false      # a su Telegram solo le llega lo de su teléfono, no lo de modelos

[peligro]
activo = true
"@
    [IO.File]::WriteAllText($cfg, $texto, (New-Object Text.UTF8Encoding($false)))
    Write-Host ""
    Paso "1/5  Listo su configuración: perfiles\$id (puerto $puerto)."
}

# ── 5. Que encienda solo con Windows ──
$tarea = "Eco ($id)"
Paso "2/5  Haciendo que encienda solo al iniciar Windows (tarea '$tarea')..."
Stop-ScheduledTask -TaskName $tarea -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match "eco_app\.pyw" -and $_.CommandLine -match "--perfil\s+$([regex]::Escape($id))(\s|$)" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
$accion  = New-ScheduledTaskAction -Execute $pyw -Argument "`"$app`" --inicio --perfil $id" -WorkingDirectory $dir
$disparo = New-ScheduledTaskTrigger -AtLogOn -User $usuario
$ajustes = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
               -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
               -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
$quien   = New-ScheduledTaskPrincipal -UserId $usuario -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $tarea -Description "Eco de $persona (Black Hole Code)" `
    -Action $accion -Trigger $disparo -Settings $ajustes -Principal $quien -Force | Out-Null

# ── 6. Abrir su puerto: tu Wi-Fi privado y Tailscale (su teléfono está en otra casa) ──
Paso "3/5  Abriendo el puerto $puerto para Tailscale..."
netsh advfirewall firewall delete rule name="Eco $id" | Out-Null
netsh advfirewall firewall delete rule name="Eco $id (Tailscale)" | Out-Null
netsh advfirewall firewall add rule name="Eco $id" dir=in action=allow protocol=TCP localport=$puerto profile=private | Out-Null
netsh advfirewall firewall add rule name="Eco $id (Tailscale)" dir=in action=allow protocol=TCP localport=$puerto remoteip=100.64.0.0/10 profile=any | Out-Null

# ── 7. Acceso directo a su panel ──
Paso "4/5  Creando el acceso directo 'Eco de $persona'..."
$archivo = ("Eco de $persona" -replace '[\\/:*?"<>|]', '') + ".lnk"
$shell = New-Object -ComObject WScript.Shell
foreach ($donde in @([Environment]::GetFolderPath("Desktop"), [Environment]::GetFolderPath("Programs"))) {
    $lnk = $shell.CreateShortcut((Join-Path $donde $archivo))
    $lnk.TargetPath       = $pyw
    $lnk.Arguments        = "`"$app`" --perfil $id"
    $lnk.WorkingDirectory = $dir
    $lnk.IconLocation     = "$icono,0"
    $lnk.Description      = "Abrir el panel de Eco de $persona"
    $lnk.Save()
}

# ── 8. Encender y abrir su panel en «Conectar el teléfono» ──
Paso "5/5  Encendiendo su Eco..."
Start-ScheduledTask -TaskName $tarea
$listo = $false
for ($i = 0; $i -lt 30 -and -not $listo; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$puerto/" -UseBasicParsing -TimeoutSec 2
        $listo = ($r.StatusCode -eq 200)
    } catch { }
}

Write-Host ""
if ($listo) {
    Write-Host "  Listo: el Eco de $persona ya corre (puerto $puerto)." -ForegroundColor Green
    Write-Host "  Se abre su panel. En 'Conectar el teléfono' tienes el QR y el enlace para mandárselo por WhatsApp."
    Start-Process "http://localhost:$puerto/#conectar"
} else {
    Write-Host "  Quedó instalado pero todavía no responde." -ForegroundColor Yellow
    Write-Host "  Espera un minuto y abre el acceso directo 'Eco de $persona'. Si sigue igual, revisa perfiles\$id\datos\eco.log"
}
Write-Host "  Para su teléfono: README, sección «Eco para otra persona»."
