package com.blackholecode.eco

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pantalla principal. Se construye en código (sin XML ni librerías) para que la app
 * sea liviana. Estética: espacio profundo, un solo acento violeta, todo pegado a su contenido.
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_ACTUALIZAR = "actualizar"
        private const val REQ_PERMS = 10

        // Paleta (contrastes verificados contra el fondo: texto 17:1, secundario 8:1).
        private const val C_BG = 0xFF0A0A12.toInt()
        private const val C_SURFACE = 0xFF14141F.toInt()
        private const val C_SURFACE_2 = 0xFF1C1C2A.toInt()
        private const val C_BORDER = 0xFF2A2A3D.toInt()
        private const val C_TEXT = 0xFFEEEEF6.toInt()
        private const val C_MUTED = 0xFFA3A3BA.toInt()
        private const val C_ACCENT = 0xFF6B5BF5.toInt()
        private const val C_ACCENT_DEEP = 0xFF4A3BD6.toInt()
        private const val C_ACCENT_SOFT = 0xFFB8AEFF.toInt()
        private const val C_LIVE = 0xFF3DDC97.toInt()
        private const val C_REC = 0xFFFF5C7A.toInt()
        private const val C_WARN = 0xFFFFB547.toInt()

        // Secciones de abajo: botones con ícono que despliegan su panel (una a la vez).
        private const val S_LAPTOP = 0
        private const val S_MOMENTO = 1
        private const val S_PERMISOS = 2
        private const val S_BATERIA = 3
        private val SECCIONES = arrayOf("Tu laptop", "Duración del momento", "Permisos", "Batería")

        /** "Antes" va de 15 s a 5 min y "después" de 0 a 5 min, de 15 en 15 segundos. */
        private const val PASO_SEG = 15
    }

    /** Una fila de estado: punto de color, nombre y "Listo"/"Falta". */
    private class Fila(val fila: View, val punto: View, val nombre: String, val estado: TextView)

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var autostartPending = false

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statPending: TextView
    private lateinit var statUpload: TextView
    private lateinit var halo: View
    private lateinit var saveButton: TextView
    private lateinit var saveCaption: TextView
    private lateinit var meetingButton: Button
    private lateinit var pauseButton: Button
    private lateinit var powerButton: Button
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var connResult: TextView
    private lateinit var updateCard: View
    private lateinit var updateTitle: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button

    private lateinit var scroll: ScrollView
    private val seccionBotones = ArrayList<View>()
    private val seccionIconos = ArrayList<ImageView>()
    private val seccionAvisos = ArrayList<View>()
    private val seccionPaneles = ArrayList<View>()
    private var seccionAbierta = -1
    private var seccionPintada = -2

    // Duración del momento
    private lateinit var antesSeek: SeekBar
    private lateinit var despuesSeek: SeekBar
    private lateinit var antesValor: TextView
    private lateinit var despuesValor: TextView
    private lateinit var barraAntes: View
    private lateinit var barraDespues: View
    private lateinit var volumenEstado: TextView

    // Permisos y batería
    private lateinit var filaMic: Fila
    private lateinit var filaNotif: Fila
    private lateinit var filaUbicacion: Fila
    private lateinit var permsButton: Button
    private lateinit var filaSinLimite: Fila
    private lateinit var filaCarga: Fila
    private lateinit var batteryButton: Button

    private var haloAnim: AnimatorSet? = null
    private var haloColor = 0
    private var lastUpdateCheck = 0L
    private var installWhenAllowed = false
    private var installWhenFound = false

    // ───────────────────────── Ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        // Primera vez: abre "Tu laptop", que es lo único que hay que configurar.
        if (savedInstanceState == null && prefs.server.isBlank()) seccionAbierta = S_LAPTOP
        paintSections()
        autostartPending = intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true
        installWhenFound = intent?.getBooleanExtra(EXTRA_ACTUALIZAR, false) == true
        ensurePermissions()
        // Solo la primera vez: si Android recrea la pantalla (al girarla), no volvemos a preguntar.
        if (savedInstanceState == null) intent?.data?.let { applyPairing(it.toString(), fromScanner = false) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { applyPairing(it.toString(), fromScanner = false) }
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            autostartPending = true
            tryAutostart()
        }
        if (intent?.getBooleanExtra(EXTRA_ACTUALIZAR, false) == true) {
            installWhenFound = true
            lastUpdateCheck = 0L
            checkUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        ui.post(tick)
        if (installWhenAllowed && canInstall()) {
            installWhenAllowed = false
            startUpdate()
        }
        checkUpdate()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        // Si moviste la duración y te fuiste antes de que se aplicara, se aplica ya.
        if (aplicarPendiente) {
            ui.removeCallbacks(aplicarMomento)
            applyMomentSettings()
        }
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1_000)
        }
    }

    // ───────────────────────── Permisos ─────────────────────────

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = has(Manifest.permission.RECORD_AUDIO)
    private fun hasLocation() =
        has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun hasNotifications() =
        Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)

    private fun missingPerms(): List<String> {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        return wanted.filter { !has(it) }
    }

    private fun ensurePermissions() {
        val missing = missingPerms()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS) else tryAutostart()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
        if (!hasMic()) toast("Sin el micrófono Eco no puede escuchar")
        tryAutostart()
    }

    private fun tryAutostart() {
        if (autostartPending && hasMic()) {
            autostartPending = false
            startEco()
        }
    }

    private fun startEco() {
        if (!hasMic()) {
            autostartPending = true
            ensurePermissions()
            return
        }
        EcoService.send(this, EcoService.ACTION_START)
        ui.postDelayed({ render() }, 300)
    }

    private fun sinLimiteDeBateria() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refreshPerms() {
        if (!::filaMic.isInitialized) return
        paintRow(filaMic, hasMic())
        paintRow(filaNotif, hasNotifications())
        paintRow(filaUbicacion, hasLocation(), opcional = true)
        permsButton.visibility = if (missingPerms().isEmpty()) View.GONE else View.VISIBLE
        permsButton.text = if (hasMic() && hasNotifications()) "Activar ubicación" else "Dar permisos"
        val sinLimite = sinLimiteDeBateria()
        paintRow(filaSinLimite, sinLimite)
        batteryButton.visibility = if (sinLimite) View.GONE else View.VISIBLE
        paintSections()
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Listo: Android ya no limita a Eco")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Ábrelo en Ajustes › Batería")
            }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // ───────────────────────── Render del estado ─────────────────────────

    private fun render() {
        val s = EcoState
        val error = s.micError
        val title: String
        val detail: String
        val color: Int
        val enReunion = s.reunionInicioMs > 0
        when {
            enReunion -> {
                title = "Grabando reunión"
                val seg = ((System.currentTimeMillis() - s.reunionInicioMs) / 1000).toInt()
                detail = "Llevas ${formatHms(seg)} · parte ${s.reunionParte}. Todo se guarda hasta que la termines."
                color = C_REC
            }
            !s.running -> {
                title = "Eco está apagado"
                detail = if (error != null) error else "Enciéndelo para empezar a guardar momentos."
                color = C_MUTED
            }
            s.paused && s.pausaBateria -> {
                title = "En pausa por batería"
                detail = "Tu batería está en ${s.batteryPct} %. Eco vuelve a escuchar solo cuando conectes el cargador. " +
                    "Si lo necesitas ya, toca Reanudar."
                color = C_WARN
            }
            s.paused -> {
                title = "En pausa"
                detail = "Micrófono apagado. Nada se está escuchando."
                color = C_WARN
            }
            error != null -> {
                title = "Problema con el micrófono"
                detail = error
                color = C_REC
            }
            s.savingRemainingSec >= 0 -> {
                title = "Guardando momento"
                detail = "Sigo grabando ${formatDuration(s.savingRemainingSec)} más. Toca otra vez para alargar."
                color = C_REC
            }
            else -> {
                title = "Escuchando"
                val mem = formatDuration(min(s.bufferedSec, s.beforeSec))
                detail = if (s.lastSaved.isNotEmpty()) "✓ ${s.lastSaved}" else "$mem en memoria. Se borra solo cada pocos minutos."
                color = C_LIVE
            }
        }
        statusTitle.text = title
        statusDetail.text = detail
        (statusDot.background as GradientDrawable).setColor(color)

        val pending = File(filesDir, "pendientes").listFiles()?.count { EcoService.isUploadable(it) } ?: 0
        statPending.text = if (pending == 0) "Nada pendiente" else "$pending por subir"
        statUpload.text = s.lastUpload
        statUpload.setTextColor(if (s.lastUploadOk) C_TEXT else C_WARN)

        val listening = s.running && !s.paused
        saveButton.isEnabled = listening || enReunion
        saveButton.alpha = if (listening || enReunion) 1f else 0.4f
        saveButton.text = if (enReunion) "Terminar\nreunión" else "Guardar\nmomento"
        saveButton.contentDescription = if (enReunion) "Terminar reunión" else "Guardar momento"
        saveCaption.text = when {
            enReunion -> "Cada 10 minutos se sube una parte a tu laptop (unos 15 MB por hora)." +
                (s.reunionError?.let { "\n$it" } ?: "")
            !s.running -> "Enciende Eco para usar el botón."
            s.paused -> "Reanuda para volver a guardar momentos."
            else -> "Guarda los últimos ${formatDuration(s.beforeSec)} y lo que sigue. " +
                (if (prefs.dobleVolumen) "También con dos toques a subir volumen, el widget o la notificación."
                else "También desde el widget, la notificación o el botón rápido.")
        }
        setHalo(listening || enReunion, if (s.savingRemainingSec >= 0 || enReunion) C_REC else C_ACCENT)
        meetingButton.visibility = if (enReunion) View.GONE else View.VISIBLE

        pauseButton.visibility = if (s.running && !enReunion) View.VISIBLE else View.GONE
        pauseButton.text = if (s.paused) "Reanudar" else "Pausar"
        powerButton.text = if (s.running) "Apagar Eco" else "Encender Eco"
        styleButton(powerButton, primary = !s.running)
        renderUpdate()
        renderVolumen()
        renderCarga()
        paintSections()
    }

    /** Debajo del interruptor: si el doble toque está listo, en pausa, o qué vio en la prueba. */
    private fun renderVolumen() {
        val s = EcoState
        val ahora = System.currentTimeMillis()
        val (texto, color) = when {
            !prefs.dobleVolumen -> "Apagado." to C_MUTED
            !s.running || s.paused || s.volumenEstado == "espera" -> "Se activa cuando Eco está escuchando." to C_MUTED
            s.volumenEstado == "llamada" -> "En pausa durante la llamada." to C_MUTED
            s.volumenEstado == "musica" -> "En pausa mientras suena música o un video." to C_MUTED
            s.volumenEstado == "sin_audio" -> "Este teléfono no dejó preparar el audio en silencio." to C_WARN
            ahora - s.volumenDobleMs < 60_000 ->
                "✓ Funcionó: momento guardado a las ${SimpleDateFormat("HH:mm", Locale.US).format(Date(s.volumenDobleMs))}." to C_LIVE
            ahora - s.volumenToqueMs < 6_000 -> when (s.volumenToques) {
                1 -> "Vi 1 toque. Para guardar son 2 seguidos."
                2 -> "Vi 2 toques, pero separados. Hazlos un poco más rápido."
                else -> "Vi ${s.volumenToques} toques: eso es subir el volumen. Para guardar son solo 2."
            } to C_WARN
            else -> "Listo, también con la pantalla apagada. Pruébalo: dos toques rápidos." to C_MUTED
        }
        volumenEstado.text = texto
        volumenEstado.setTextColor(color)
    }

    private fun renderCarga() {
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cargando = bm.isCharging
        if (pct !in 0..100) {
            paintRow(filaCarga, true, siTexto = "—")
            return
        }
        paintRow(filaCarga, cargando || pct > 20, siTexto = "$pct %" + if (cargando) " · cargando" else "", noTexto = "$pct %")
    }

    // ───────────────────────── Actualización desde la laptop ─────────────────────────

    private fun renderUpdate() {
        val info = EcoState.update
        val status = EcoState.updateStatus
        updateCard.visibility = if (info != null || (EcoState.updating && status != null)) View.VISIBLE else View.GONE
        if (info != null) updateTitle.text = "Eco ${info.versionName} está lista"
        updateStatus.text = status ?: "La bajas de tu laptop y se instala encima: no pierdes tus ajustes ni lo pendiente."
        updateButton.isEnabled = !EcoState.updating
        updateButton.alpha = if (EcoState.updating) 0.5f else 1f
        updateButton.text = if (EcoState.updating) "Actualizando…" else "Actualizar"
    }

    /** Pregunta a la laptop (como mucho una vez por minuto) si tiene una versión nueva. */
    private fun checkUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 60_000 || prefs.server.isBlank() || EcoState.updating) return
        lastUpdateCheck = now
        val server = prefs.server
        val token = prefs.token
        Thread {
            val info = Updater.refresh(this, server, token)
            runOnUiThread {
                render()
                if (info != null && installWhenFound) {
                    installWhenFound = false
                    onUpdateTapped()
                }
            }
        }.start()
    }

    private fun canInstall() = packageManager.canRequestPackageInstalls()

    private fun onUpdateTapped() {
        if (EcoState.update == null || EcoState.updating) return
        if (!canInstall()) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Permite que Eco se actualice")
                .setMessage("Android pide permiso una sola vez para que Eco instale su versión nueva. " +
                    "En la siguiente pantalla activa \"Permitir de esta fuente\" y vuelve con la flecha de atrás.")
                .setPositiveButton("Ir al permiso") { _, _ ->
                    installWhenAllowed = true
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        openAppSettings()
                    }
                }
                .setNegativeButton("Ahora no", null)
                .show()
            return
        }
        startUpdate()
    }

    private fun startUpdate() {
        val info = EcoState.update ?: return
        EcoState.updating = true
        EcoState.updateStatus = "Bajando la versión ${info.versionName} de tu laptop…"
        render()
        val server = prefs.server
        val token = prefs.token
        Thread {
            try {
                val apk = Updater.download(this, server, token, info) { pct ->
                    if (pct >= 0) EcoState.updateStatus = "Bajando de tu laptop… $pct %"
                }
                EcoState.updateStatus = "Abriendo el instalador…"
                Updater.install(this, apk)
            } catch (e: Exception) {
                EcoState.updating = false
                EcoState.updateStatus = "No se pudo: ${e.message ?: "revisa que la laptop esté al alcance"}"
            }
            runOnUiThread { render() }
        }.start()
    }

    private fun setHalo(active: Boolean, color: Int) {
        if (color != haloColor) {
            (halo.background as GradientDrawable).setColor(color)
            haloColor = color
        }
        if (active && haloAnim == null) {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                halo.alpha = 0.25f // movimiento reducido: halo fijo
                return
            }
            val sx = ObjectAnimator.ofFloat(halo, View.SCALE_X, 1f, 1.22f)
            val sy = ObjectAnimator.ofFloat(halo, View.SCALE_Y, 1f, 1.22f)
            val a = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.45f, 0f)
            listOf(sx, sy, a).forEach {
                it.repeatCount = ValueAnimator.INFINITE
                it.duration = 2_400
                it.interpolator = DecelerateInterpolator()
            }
            haloAnim = AnimatorSet().apply { playTogether(sx, sy, a); start() }
        } else if (!active) {
            haloAnim?.cancel()
            haloAnim = null
            halo.alpha = 0f
        }
    }

    // ───────────────────────── Acciones ─────────────────────────

    private fun onSaveTapped(v: View) {
        if (EcoState.reunionInicioMs > 0) {
            confirmStopMeeting()
            return
        }
        if (!(EcoState.running && !EcoState.paused)) return
        val feedback = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        v.performHapticFeedback(feedback)
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
        EcoService.send(this, EcoService.ACTION_SAVE)
        ui.postDelayed({ render() }, 150)
    }

    private fun startMeeting() {
        if (!hasMic()) {
            ensurePermissions()
            return
        }
        EcoService.send(this, EcoService.ACTION_REUNION_INICIAR)
        ui.postDelayed({ render() }, 400)
    }

    private fun confirmStopMeeting() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("¿Terminar la reunión?")
            .setMessage("Se deja de grabar y la última parte se sube a tu laptop para analizarla.")
            .setPositiveButton("Terminar") { _, _ ->
                EcoService.send(this, EcoService.ACTION_REUNION_TERMINAR)
                ui.postDelayed({ render() }, 400)
            }
            .setNegativeButton("Seguir grabando", null)
            .show()
    }

    private fun formatHms(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else formatDuration(s)
    }

    private fun saveConnection() {
        prefs.server = serverInput.text.toString()
        prefs.token = tokenInput.text.toString()
    }

    private fun testConnection(onOk: (() -> Unit)? = null) {
        saveConnection()
        connResult.setTextColor(C_MUTED)
        connResult.text = "Buscando tu laptop…"
        val server = prefs.server
        val token = prefs.token
        Thread {
            val r = Net.ping(server, token)
            runOnUiThread {
                connResult.setTextColor(if (r.ok) C_LIVE else C_WARN)
                connResult.text = (if (r.ok) "✓  " else "✕  ") + r.message
                if (r.ok) onOk?.invoke()
            }
        }.start()
    }

    // ───────────────────────── Conectar con código QR ─────────────────────────

    /** Abre el escáner de Google (no pide permiso de cámara: lo maneja Play Services). */
    private fun scanQr() {
        val opciones = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, opciones).startScan()
            .addOnSuccessListener { codigo -> applyPairing(codigo.rawValue, fromScanner = true) }
            .addOnFailureListener { e ->
                connResult.setTextColor(C_WARN)
                connResult.text = "✕  No se pudo abrir el escáner (${e.message ?: "sin detalles"}). " +
                    "Revisa tu internet la primera vez, o escribe la dirección a mano."
            }
    }

    /**
     * El QR del panel trae: eco://conectar?d=DIRECCIÓN&w=DIRECCIÓN_WIFI&t=TOKEN
     * Si llega desde otra app o una página (no desde nuestro escáner), se pide confirmación,
     * para que nadie pueda cambiar a escondidas a dónde se suben tus audios.
     */
    private fun applyPairing(raw: String?, fromScanner: Boolean) {
        val datos = Net.parsePairing(raw)
        openSection(S_LAPTOP)
        if (datos == null) {
            connResult.setTextColor(C_WARN)
            connResult.text = "✕  Ese código no es de Eco. Ábrelo desde el panel de tu laptop."
            return
        }
        val aplicar = {
            serverInput.setText(datos.server)
            tokenInput.setText(datos.token)
            saveConnection()
            testConnection {
                toast("Conectado con tu laptop")
                if ((File(filesDir, "pendientes").listFiles()?.count { EcoService.isUploadable(it) } ?: 0) > 0) uploadNow()
            }
        }
        if (fromScanner) {
            aplicar()
        } else {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("¿Conectar Eco con esta laptop?")
                .setMessage("Tus audios se van a subir a ${datos.server}. Acepta solo si ese código lo sacaste del panel de tu laptop.")
                .setPositiveButton("Conectar") { _, _ -> aplicar() }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun uploadNow() {
        saveConnection()
        EcoService.send(this, EcoService.ACTION_UPLOAD)
        connResult.setTextColor(C_MUTED)
        connResult.text = "Subiendo lo pendiente…"
    }

    private var aplicarPendiente = false
    private val aplicarMomento = Runnable { applyMomentSettings() }

    /** Se aplica un momento después de que sueltas el control, no en cada movimiento. */
    private fun scheduleMomentSettings() {
        aplicarPendiente = true
        ui.removeCallbacks(aplicarMomento)
        ui.postDelayed(aplicarMomento, 900)
    }

    private fun applyMomentSettings() {
        aplicarPendiente = false
        val before = Prefs.MIN_BEFORE + antesSeek.progress * PASO_SEG
        val after = despuesSeek.progress * PASO_SEG
        if (before == prefs.beforeSec && after == prefs.afterSec) return
        prefs.beforeSec = before
        prefs.afterSec = after
        // Reinicia la escucha con el tamaño nuevo (lo que había en memoria empieza de cero).
        if (EcoState.running && !EcoState.paused && EcoState.reunionInicioMs == 0L) EcoService.send(this, EcoService.ACTION_RELOAD)
        toast("Listo: ${humano(prefs.beforeSec)} antes y ${humano(prefs.afterSec)} después")
    }

    /** 45 -> "45 s", 180 -> "3 min", 90 -> "1 min 30 s" */
    private fun humano(seg: Int): String = when {
        seg < 60 -> "$seg s"
        seg % 60 == 0 -> "${seg / 60} min"
        else -> "${seg / 60} min ${seg % 60} s"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ───────────────────────── Construcción de la interfaz ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi(): View {
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(C_BG)
            clipToPadding = false
        }
        val frame = FrameLayout(this)
        scroll.addView(frame, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // En tablets la columna no pasa de 560dp; en teléfono usa todo el ancho.
        val wide = resources.configuration.screenWidthDp > 600
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        frame.addView(
            col,
            FrameLayout.LayoutParams(if (wide) dp(560) else MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
        )

        // Encabezado
        col.addView(label("BLACK HOLE CODE", 11f, C_ACCENT_SOFT, medium = true).apply { letterSpacing = 0.18f })
        col.addView(label("Eco", 34f, C_TEXT, medium = true), lp(top = 4))
        col.addView(label("Tu memoria de bolsillo.", 15f, C_MUTED), lp(top = 2))

        col.addView(buildUpdateCard(), lp(top = 20))
        col.addView(buildStatusCard(), lp(top = 12))
        col.addView(buildSaveArea(), lp(top = 8))

        // Controles
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        powerButton = button("Encender Eco", primary = true) {
            if (EcoState.running) EcoService.send(this, EcoService.ACTION_STOP) else startEco()
            ui.postDelayed({ render() }, 300)
        }
        pauseButton = button("Pausar", primary = false) {
            EcoService.send(this, EcoService.ACTION_PAUSE)
            ui.postDelayed({ render() }, 300)
        }
        row.addView(powerButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(pauseButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        col.addView(row, lp(top = 4))

        col.addView(buildSections(), lp(top = 24))
        return scroll
    }

    // ───────────────────────── Secciones con ícono ─────────────────────────

    /** Fila de cuatro íconos; cada uno abre su panel justo debajo (solo uno a la vez). */
    private fun buildSections(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C_SURFACE, 20, C_BORDER)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val iconos = intArrayOf(
            R.drawable.ic_seccion_laptop, R.drawable.ic_seccion_momento,
            R.drawable.ic_seccion_permisos, R.drawable.ic_seccion_bateria,
        )
        for (i in SECCIONES.indices) {
            dock.addView(sectionButton(i, iconos[i]), LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
        wrap.addView(dock)
        listOf(buildConnectionCard(), buildMomentCard(), buildPermsCard(), buildBatteryCard()).forEach {
            it.visibility = View.GONE
            seccionPaneles += it
            wrap.addView(it, lp(top = 10))
        }
        refreshPerms()
        return wrap
    }

    private fun sectionButton(i: Int, icono: Int): View {
        val boton = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            if (Build.VERSION.SDK_INT >= 26) tooltipText = SECCIONES[i]
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }.start()
                toggleSection(i)
            }
            // Para TalkBack: es un botón que despliega o recoge su sección.
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    info.addAction(
                        if (seccionAbierta == i) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_EXPAND || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                        toggleSection(i)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        }
        val img = ImageView(this).apply {
            setImageResource(icono)
            imageTintList = ColorStateList.valueOf(C_MUTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        boton.addView(img, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        // Puntito ámbar arriba a la derecha del ícono: "aquí falta algo".
        val aviso = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_WARN)
                setStroke(dp(2), C_SURFACE)
            }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        boton.addView(aviso, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER).apply {
            leftMargin = dp(12)
            bottomMargin = dp(12)
        })
        seccionBotones += boton
        seccionIconos += img
        seccionAvisos += aviso
        return boton
    }

    private fun toggleSection(i: Int) {
        seccionAbierta = if (seccionAbierta == i) -1 else i
        paintSections()
        if (seccionAbierta >= 0) bringIntoView(seccionPaneles[seccionAbierta])
    }

    private fun openSection(i: Int) {
        if (seccionAbierta == i) return
        seccionAbierta = i
        paintSections()
        bringIntoView(seccionPaneles[i])
    }

    /** Pinta qué sección está abierta y qué íconos llevan aviso. Se llama cada segundo: solo cambia lo necesario. */
    private fun paintSections() {
        if (seccionBotones.isEmpty()) return
        val avisos = booleanArrayOf(
            prefs.server.isBlank() || !EcoState.lastUploadOk,
            prefs.dobleVolumen && EcoState.volumenEstado == "sin_audio",
            !hasMic() || !hasNotifications(),
            !sinLimiteDeBateria(),
        )
        val cambio = seccionPintada != seccionAbierta
        for (i in seccionBotones.indices) {
            val abierta = i == seccionAbierta
            val boton = seccionBotones[i]
            seccionAvisos[i].visibility = if (avisos[i]) View.VISIBLE else View.GONE
            val descripcion = SECCIONES[i] + if (avisos[i]) ", revisa esto" else ""
            if (boton.contentDescription != descripcion) boton.contentDescription = descripcion
            if (!cambio) continue
            boton.background = ripple(
                if (abierta) rounded(0x266B5BF5, 14, 0x8C6B5BF5.toInt()) else rounded(0x00000000, 14), 14
            )
            seccionIconos[i].imageTintList = ColorStateList.valueOf(if (abierta) C_ACCENT_SOFT else C_MUTED)
            if (Build.VERSION.SDK_INT >= 30) boton.stateDescription = if (abierta) "Abierta" else "Cerrada"
            val panel = seccionPaneles[i]
            if (abierta && panel.visibility != View.VISIBLE) {
                panel.alpha = 0f
                panel.translationY = -dp(8).toFloat()
                panel.visibility = View.VISIBLE
                panel.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            } else if (!abierta && panel.visibility != View.GONE) {
                panel.animate().cancel()
                panel.visibility = View.GONE
            }
        }
        seccionPintada = seccionAbierta
    }

    /** Si el panel recién abierto queda fuera de la pantalla, baja lo justo para verlo. */
    private fun bringIntoView(panel: View) {
        panel.post {
            val r = Rect(0, 0, panel.width, panel.height)
            scroll.offsetDescendantRectToMyCoords(panel, r)
            var destino = r.bottom - scroll.height + dp(24)
            val conIconos = r.top - dp(88) // que se sigan viendo los íconos
            if (destino > conIconos) destino = conIconos
            if (destino > scroll.scrollY) scroll.smoothScrollTo(0, destino)
        }
    }

    private fun buildUpdateCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x336B5BF5, C_SURFACE)).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x66B8AEFF)
            }
            setPadding(dp(18), dp(16), dp(18), dp(18))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(label("ACTUALIZACIÓN", 11f, C_ACCENT_SOFT, medium = true).apply { letterSpacing = 0.12f })
        updateTitle = label("Hay una versión nueva", 17f, C_TEXT, medium = true)
        card.addView(updateTitle, lp(top = 4))
        updateStatus = label("", 13f, C_MUTED).apply { setLineSpacing(0f, 1.2f) }
        card.addView(updateStatus, lp(top = 4))
        updateButton = button("Actualizar", primary = true) { onUpdateTapped() }
        card.addView(updateButton, lp(top = 14))
        updateCard = card
        return card
    }

    private fun buildStatusCard(): View {
        val card = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_MUTED) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        head.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        statusTitle = label("Eco está apagado", 17f, C_TEXT, medium = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        head.addView(statusTitle)
        card.addView(head)
        statusDetail = label("", 14f, C_MUTED).apply { setLineSpacing(0f, 1.2f) }
        card.addView(statusDetail, lp(top = 6))

        card.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            topMargin = dp(14); bottomMargin = dp(14)
        })

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(overline("PENDIENTE"))
        statPending = label("Nada pendiente", 14f, C_TEXT)
        left.addView(statPending, lp(top = 4))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(overline("LAPTOP"))
        statUpload = label("", 14f, C_TEXT)
        right.addView(statUpload, lp(top = 4))
        stats.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        stats.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f).apply { marginStart = dp(12) })
        card.addView(stats)
        return card
    }

    private fun buildSaveArea(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val stage = FrameLayout(this)
        val size = dp(184)
        halo = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_ACCENT) }
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.addView(halo, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        val face = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C_ACCENT, C_ACCENT_DEEP)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), 0x33FFFFFF)
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
        saveButton = TextView(this).apply {
            text = "Guardar\nmomento"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), face, mask)
            elevation = dp(10).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Guardar momento"
            setOnClickListener { onSaveTapped(it) }
        }
        stage.addView(saveButton, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        wrap.addView(stage, LinearLayout.LayoutParams(dp(240), dp(240)))

        saveCaption = label("", 13f, C_MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        wrap.addView(saveCaption, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        meetingButton = button("Iniciar modo reunión", primary = false) { startMeeting() }
        wrap.addView(meetingButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })
        return wrap
    }

    private fun buildConnectionCard(): View {
        val card = panelCard(S_LAPTOP)
        card.addView(
            label("Escanea el QR del panel de tu laptop, o escribe los datos a mano.", 13f, C_MUTED)
                .apply { setLineSpacing(0f, 1.2f) },
            lp(top = 6)
        )
        card.addView(button("Escanear código QR", primary = true) { scanQr() }, lp(top = 14))
        card.addView(overline("DIRECCIÓN"), lp(top = 16))
        serverInput = input("192.168.1.50:8765", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(prefs.server)
        }
        card.addView(serverInput, lp(top = 6))
        card.addView(overline("TOKEN"), lp(top = 12))
        tokenInput = input("La clave de config.toml", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            setText(prefs.token)
        }
        card.addView(tokenInput, lp(top = 6))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Probar", primary = false) { testConnection() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(
            button("Subir ahora", primary = false) { uploadNow() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        )
        card.addView(row, lp(top = 14))
        connResult = label("", 13f, C_MUTED).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        card.addView(connResult, lp(top = 10))
        return card
    }

    /**
     * Cuánto audio entra en un momento. Eco siempre tiene en memoria lo último que escuchó
     * ("antes"); al guardar, se queda con eso y sigue grabando "después".
     */
    private fun buildMomentCard(): View {
        val card = panelCard(S_MOMENTO)

        // Línea de tiempo: lo que ya escuchó | el toque | lo que sigue.
        val r = dp(4).toFloat()
        val linea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        barraAntes = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF8E83F0.toInt())
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }
        val toque = View(this).apply { background = rounded(0xFFFFFFFF.toInt(), 2) }
        barraDespues = View(this).apply {
            background = GradientDrawable().apply {
                setColor(C_ACCENT)
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
        }
        linea.addView(barraAntes, LinearLayout.LayoutParams(0, dp(8), 1f))
        linea.addView(toque, LinearLayout.LayoutParams(dp(3), dp(22)).apply { marginStart = dp(3); marginEnd = dp(3) })
        linea.addView(barraDespues, LinearLayout.LayoutParams(0, dp(8), 1f))
        card.addView(linea, lp(top = 16))
        card.addView(
            label("Al guardar, Eco se queda con lo que ya escuchó y sigue grabando un poco más.", 13f, C_MUTED)
                .apply { setLineSpacing(0f, 1.2f) },
            lp(top = 12)
        )

        val pasosAntes = (Prefs.MAX_SECONDS - Prefs.MIN_BEFORE) / PASO_SEG
        val pasosDespues = Prefs.MAX_SECONDS / PASO_SEG
        antesValor = label("", 15f, C_TEXT, medium = true)
        antesSeek = slider("Lo que ya escuchó", pasosAntes, ((prefs.beforeSec - Prefs.MIN_BEFORE) / PASO_SEG.toFloat()).roundToInt())
        card.addView(sliderHead("ANTES", antesValor), lp(top = 18))
        card.addView(antesSeek, lp(top = 2))
        despuesValor = label("", 15f, C_TEXT, medium = true)
        despuesSeek = slider("Lo que sigue", pasosDespues, (prefs.afterSec / PASO_SEG.toFloat()).roundToInt())
        card.addView(sliderHead("DESPUÉS", despuesValor), lp(top = 10))
        card.addView(despuesSeek, lp(top = 2))
        paintMomentLine()

        card.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            topMargin = dp(16); bottomMargin = dp(10)
        })
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val dobleVolumen = Switch(this).apply {
            text = "Doble toque a subir volumen"
            textSize = 15f
            setTextColor(C_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isChecked = prefs.dobleVolumen
            minHeight = dp(48)
            thumbTintList = ColorStateList(estados, intArrayOf(0xFFFFFFFF.toInt(), C_MUTED))
            trackTintList = ColorStateList(estados, intArrayOf(C_ACCENT, C_BORDER))
            setOnCheckedChangeListener { _, activo ->
                prefs.dobleVolumen = activo
                render()
            }
        }
        card.addView(dobleVolumen, lp())
        volumenEstado = label("", 13f, C_MUTED).apply {
            setLineSpacing(0f, 1.2f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(volumenEstado, lp(top = 2))
        return card
    }

    private fun sliderHead(nombre: String, valor: TextView): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(overline(nombre), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fila.addView(valor)
        return fila
    }

    private fun slider(descripcion: String, pasos: Int, valor: Int) = SeekBar(this).apply {
        max = pasos
        progress = valor.coerceIn(0, pasos)
        contentDescription = descripcion
        progressTintList = ColorStateList.valueOf(C_ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(C_BORDER)
        thumbTintList = ColorStateList.valueOf(C_ACCENT_SOFT)
        minimumHeight = dp(44)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                paintMomentLine()
                if (fromUser) scheduleMomentSettings()
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun paintMomentLine() {
        if (!::despuesSeek.isInitialized) return
        val antes = Prefs.MIN_BEFORE + antesSeek.progress * PASO_SEG
        val despues = despuesSeek.progress * PASO_SEG
        antesValor.text = humano(antes)
        despuesValor.text = humano(despues)
        if (Build.VERSION.SDK_INT >= 30) {
            antesSeek.stateDescription = humano(antes)
            despuesSeek.stateDescription = humano(despues)
        }
        (barraAntes.layoutParams as LinearLayout.LayoutParams).weight = antes.toFloat()
        (barraDespues.layoutParams as LinearLayout.LayoutParams).weight = despues.toFloat()
        barraDespues.visibility = if (despues == 0) View.GONE else View.VISIBLE
        barraAntes.requestLayout()
    }

    private fun buildPermsCard(): View {
        val card = panelCard(S_PERMISOS)
        filaMic = statusRow(card, "Micrófono", top = 8)
        filaNotif = statusRow(card, "Notificaciones")
        filaUbicacion = statusRow(card, "Ubicación")
        card.addView(
            label("La ubicación es opcional: sirve para saber dónde pasó cada momento.", 13f, C_MUTED)
                .apply { setLineSpacing(0f, 1.2f) },
            lp(top = 6)
        )
        permsButton = button("Dar permisos", primary = true) {
            if (missingPerms().isEmpty()) toast("Ya tiene todos los permisos")
            else if (missingPerms().any { shouldShowRequestPermissionRationale(it) }) ensurePermissions()
            else openAppSettings()
        }
        card.addView(permsButton, lp(top = 14))
        return card
    }

    private fun buildBatteryCard(): View {
        val card = panelCard(S_BATERIA)
        filaSinLimite = statusRow(card, "Sin límite de Android", top = 8)
        filaCarga = statusRow(card, "Batería ahora")
        batteryButton = button("Quitar el límite", primary = true) { openBatterySettings() }
        card.addView(batteryButton, lp(top = 12))
        card.addView(
            label(
                "En tu Honor, además: Ajustes › Batería › Inicio de aplicaciones › Eco → apaga " +
                    "«Gestionar automáticamente» y deja las tres opciones activas.",
                13f, C_MUTED
            ).apply { setLineSpacing(0f, 1.25f) },
            lp(top = 14)
        )
        card.addView(
            label("Con 20 % o menos y sin cargador, Eco se pausa solo y vuelve al conectarlo.", 13f, C_MUTED)
                .apply { setLineSpacing(0f, 1.25f) },
            lp(top = 8)
        )
        return card
    }

    /** Panel de una sección: tarjeta con su nombre arriba, en pequeño. */
    private fun panelCard(seccion: Int) = card().apply {
        addView(overline(SECCIONES[seccion].uppercase(Locale.ROOT)).apply { setTextColor(C_ACCENT_SOFT) })
    }

    private fun statusRow(parent: LinearLayout, nombre: String, top: Int = 0): Fila {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
        }
        val punto = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_MUTED) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fila.addView(punto, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })
        fila.addView(
            label(nombre, 15f, C_TEXT).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val estado = label("", 13f, C_MUTED, medium = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        fila.addView(estado)
        parent.addView(fila, lp(top))
        return Fila(fila, punto, nombre, estado)
    }

    private fun paintRow(f: Fila, ok: Boolean, opcional: Boolean = false, siTexto: String = "Listo", noTexto: String? = null) {
        val texto = if (ok) siTexto else noTexto ?: if (opcional) "Opcional" else "Falta"
        val color = when {
            ok -> C_LIVE
            opcional -> C_MUTED
            else -> C_WARN
        }
        (f.punto.background as GradientDrawable).setColor(color)
        if (f.estado.text.toString() != texto) f.estado.text = texto
        f.estado.setTextColor(if (ok || opcional) C_MUTED else C_WARN)
        f.fila.contentDescription = "${f.nombre}: $texto"
    }

    // ───────────────────────── Piezas reutilizables ─────────────────────────

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun label(text: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun overline(text: String) = label(text, 11f, C_MUTED, medium = true).apply { letterSpacing = 0.12f }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_SURFACE, 20, C_BORDER)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun ripple(content: Drawable, radius: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(0xFFFFFFFF.toInt(), radius))

    private fun styleButton(b: Button, primary: Boolean) {
        val bg = if (primary) rounded(C_ACCENT, 14) else rounded(C_SURFACE_2, 14, C_BORDER)
        b.background = ripple(bg, 14)
        b.setTextColor(if (primary) 0xFFFFFFFF.toInt() else C_TEXT)
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        setPadding(dp(16), dp(12), dp(16), dp(12))
        styleButton(this, primary)
        setOnClickListener { onClick() }
    }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 15f
        setTextColor(C_TEXT)
        setHintTextColor(0xFF8C8CA6.toInt())
        background = rounded(C_SURFACE_2, 12, C_BORDER)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(48)
        isSingleLine = true
    }
}
