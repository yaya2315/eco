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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
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
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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
    }

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
    private lateinit var beforeInput: EditText
    private lateinit var afterInput: EditText
    private lateinit var permsText: TextView
    private lateinit var updateCard: View
    private lateinit var updateTitle: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button

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

    private fun refreshPerms() {
        if (!::permsText.isInitialized) return
        fun line(ok: Boolean, label: String) = (if (ok) "✓  " else "✕  ") + label
        val pm = getSystemService(PowerManager::class.java)
        permsText.text = listOf(
            line(hasMic(), "Micrófono"),
            line(hasNotifications(), "Notificaciones"),
            line(hasLocation(), "Ubicación (opcional, para saber dónde pasó)"),
            line(pm.isIgnoringBatteryOptimizations(packageName), "Sin optimización de batería"),
        ).joinToString("\n")
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

    private fun applyMomentSettings() {
        val before = beforeInput.text.toString().toIntOrNull()
        val after = afterInput.text.toString().toIntOrNull()
        if (before == null || after == null) {
            toast("Escribe los segundos con números")
            return
        }
        prefs.beforeSec = before
        prefs.afterSec = after
        beforeInput.setText(prefs.beforeSec.toString())
        afterInput.setText(prefs.afterSec.toString())
        if (EcoState.running && !EcoState.paused) EcoService.send(this, EcoService.ACTION_RELOAD)
        toast("Listo: ${formatDuration(prefs.beforeSec)} antes y ${formatDuration(prefs.afterSec)} después")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ───────────────────────── Construcción de la interfaz ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
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

        col.addView(buildConnectionCard(), lp(top = 24))
        col.addView(buildMomentCard(), lp(top = 16))
        col.addView(buildPermsCard(), lp(top = 16))

        col.addView(
            label(
                "🔒  El audio vive solo en la memoria del teléfono y se sobrescribe cada pocos minutos. " +
                    "Solo se crea un archivo cuando tú tocas Guardar, y se borra del teléfono apenas llega a tu laptop.",
                13f, C_MUTED
            ).apply { setLineSpacing(0f, 1.25f) },
            lp(top = 24)
        )
        return scroll
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
        val card = card()
        card.addView(label("Tu laptop", 17f, C_TEXT, medium = true))
        card.addView(
            label("En el panel de la laptop toca \"Conectar el teléfono con un QR\" y escanéalo aquí. " +
                "También puedes escribir los datos a mano.", 13f, C_MUTED).apply { setLineSpacing(0f, 1.2f) },
            lp(top = 4)
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

    private fun buildMomentCard(): View {
        val card = card()
        card.addView(label("Duración del momento", 17f, C_TEXT, medium = true))
        card.addView(label("Entre 15 y 300 segundos. Más largo = más contexto, más batería y más proceso.", 13f, C_MUTED), lp(top = 4))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(overline("ANTES (SEG)"))
        beforeInput = input("180", InputType.TYPE_CLASS_NUMBER).apply { setText(prefs.beforeSec.toString()) }
        left.addView(beforeInput, lp(top = 6))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(overline("DESPUÉS (SEG)"))
        afterInput = input("60", InputType.TYPE_CLASS_NUMBER).apply { setText(prefs.afterSec.toString()) }
        right.addView(afterInput, lp(top = 6))
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        card.addView(row, lp(top = 16))
        card.addView(button("Aplicar", primary = false) { applyMomentSettings() }, lp(top = 14))

        card.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            topMargin = dp(18); bottomMargin = dp(14)
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
                toast(if (activo) "Listo: dos toques a subir volumen guardan un momento" else "Doble toque apagado")
            }
        }
        card.addView(dobleVolumen, lp())
        card.addView(
            label("Guarda un momento sin sacar el teléfono, aunque la pantalla esté apagada. Un toque suelto sube el " +
                "volumen como siempre. Mientras suena música, los botones son solo para la música.", 13f, C_MUTED)
                .apply { setLineSpacing(0f, 1.2f) },
            lp(top = 2)
        )
        return card
    }

    private fun buildPermsCard(): View {
        val card = card()
        card.addView(label("Permisos y batería", 17f, C_TEXT, medium = true))
        permsText = label("", 14f, C_TEXT).apply { setLineSpacing(0f, 1.45f) }
        card.addView(permsText, lp(top = 10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Permisos", primary = false) {
            if (missingPerms().isEmpty()) toast("Ya tiene todos los permisos")
            else if (Build.VERSION.SDK_INT >= 23 && missingPerms().any { shouldShowRequestPermissionRationale(it) }) ensurePermissions()
            else openAppSettings()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(
            button("Batería", primary = false) { openBatterySettings() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        )
        card.addView(row, lp(top = 14))
        card.addView(
            label(
                "En tu Honor, además: Ajustes › Batería › Inicio de aplicaciones › Eco → apaga " +
                    "\"Gestionar automáticamente\" y deja activas las tres opciones. Si no, MagicOS cierra Eco a media mañana.",
                13f, C_MUTED
            ).apply { setLineSpacing(0f, 1.25f) },
            lp(top = 14)
        )
        return card
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
