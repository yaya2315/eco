package com.blackholecode.eco

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pantalla de emergencia. Aquí, y solo aquí, se mandan los mensajes a tus contactos, y solo si
 * mantienes presionado el botón 2 segundos. Un toque, un roce en la bolsa o la laptop no bastan.
 */
class EmergenciaActivity : Activity() {

    companion object {
        private const val REQ_SMS = 40
        private const val ACCION_SMS = "com.blackholecode.eco.SMS_ENVIADO"
        private const val SOSTENER_MS = 2_000L

        private const val C_BG = 0xFF0A0A12.toInt()
        private const val C_SURFACE = 0xFF14141F.toInt()
        private const val C_SURFACE_2 = 0xFF1C1C2A.toInt()
        private const val C_BORDER = 0xFF2A2A3D.toInt()
        private const val C_TEXT = 0xFFEEEEF6.toInt()
        private const val C_MUTED = 0xFFA3A3BA.toInt()
        private const val C_REC = 0xFFFF5C7A.toInt()
        private const val C_REC_DEEP = 0xFFD9364F.toInt()  // fondo del botón: el blanco se lee bien encima
        private const val C_REC_PRESS = 0xFFB82A41.toInt()
        private const val C_LIVE = 0xFF3DDC97.toInt()
        private const val C_WARN = 0xFFFFB547.toInt()
    }

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var modo = Emergencia.MODO_MANUAL
    private var alertaId = -1L
    private var quePaso = ""
    private var frase = ""
    private var hora = ""
    private var ubicacion: Location? = null
    private var envioPendiente = false
    private var enviado = false

    private lateinit var ubicacionTexto: TextView
    private lateinit var zonaBoton: View
    private lateinit var resultados: LinearLayout
    private lateinit var sostener: SostenerView
    private val estados = ArrayList<TextView>()
    private val partesOk = HashMap<Int, Int>()
    private val partesTotal = HashMap<Int, Int>()
    private var contactos: List<Emergencia.Contacto> = emptyList()

    private val receptorSms = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val i = intent.getIntExtra("c", -1)
            if (i !in estados.indices) return
            if (resultCode == RESULT_OK) {
                partesOk[i] = (partesOk[i] ?: 0) + 1
                if ((partesOk[i] ?: 0) >= (partesTotal[i] ?: 1)) marcar(i, "Enviado ✓", C_LIVE)
            } else {
                marcar(i, "No se pudo", C_WARN)
            }
        }
    }

    // ───────────────────────── Ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true) // en una emergencia no hay que desbloquear
        leerIntent(intent)
        contactos = Emergencia.contactos(prefs)
        setContentView(construir())
        val filtro = IntentFilter(ACCION_SMS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receptorSms, filtro, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receptorSms, filtro)
        buscarUbicacion()
    }

    override fun onDestroy() {
        try { unregisterReceiver(receptorSms) } catch (_: Exception) {}
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun leerIntent(i: Intent?) {
        modo = i?.getStringExtra(Emergencia.EXTRA_MODO) ?: Emergencia.MODO_MANUAL
        alertaId = i?.getLongExtra(Emergencia.EXTRA_ID, -1L) ?: -1L
        quePaso = i?.getStringExtra(Emergencia.EXTRA_QUE_PASO).orEmpty()
        frase = i?.getStringExtra(Emergencia.EXTRA_FRASE).orEmpty()
        hora = i?.getStringExtra(Emergencia.EXTRA_HORA).orEmpty()
    }

    // ───────────────────────── Ubicación ─────────────────────────

    private fun buscarUbicacion() {
        val permiso = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permiso) {
            pintarUbicacion()
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (lm == null) {
            pintarUbicacion()
            return
        }
        val conocidas = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } catch (_: Exception) { null } }
        ubicacion = conocidas.maxByOrNull { it.time }
        pintarUbicacion()
        if (Build.VERSION.SDK_INT >= 30) {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!lm.isProviderEnabled(p)) continue
                try {
                    lm.getCurrentLocation(p, null, mainExecutor) { loc -> if (loc != null) mejorUbicacion(loc) }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun mejorUbicacion(loc: Location) {
        val actual = ubicacion
        val masNueva = actual == null || loc.time > actual.time + 60_000
        val masPrecisa = actual == null || !actual.hasAccuracy() || (loc.hasAccuracy() && loc.accuracy < actual.accuracy)
        if (masNueva || masPrecisa) {
            ubicacion = loc
            pintarUbicacion()
        }
    }

    private fun pintarUbicacion() {
        if (!::ubicacionTexto.isInitialized) return
        val u = ubicacion
        ubicacionTexto.text = when {
            u == null -> "Ubicación: buscando… (si no aparece, activa la ubicación del teléfono)"
            else -> {
                val minutos = ((System.currentTimeMillis() - u.time) / 60_000).toInt()
                "Ubicación lista" + (if (u.hasAccuracy()) " · ±${u.accuracy.roundToInt()} m" else "") +
                    (if (minutos >= 2) " · de hace $minutos min" else "")
            }
        }
        ubicacionTexto.setTextColor(if (u == null) C_WARN else C_MUTED)
    }

    // ───────────────────────── Enviar ─────────────────────────

    private fun bateria(): Int = try {
        getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) {
        -1
    }

    private fun mensaje() = Emergencia.mensaje(modo, prefs.nombre, quePaso, ubicacion, bateria())

    @Suppress("DEPRECATION")
    private fun sms(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** Solo se llega aquí después de mantener presionado el botón (o confirmar con TalkBack). */
    private fun enviar() {
        if (enviado || contactos.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            envioPendiente = true
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQ_SMS)
            return
        }
        enviado = true
        val texto = mensaje()
        mostrarResultados()
        val manager = try { sms() } catch (_: Exception) { null }
        val base = (System.currentTimeMillis() % 10_000).toInt() * 10
        var intentados = 0
        contactos.forEachIndexed { i, c ->
            if (manager == null) {
                marcar(i, "No se pudo", C_WARN)
                return@forEachIndexed
            }
            try {
                val partes = manager.divideMessage(texto)
                partesTotal[i] = partes.size
                val avisos = ArrayList<PendingIntent>(partes.size)
                for (p in partes.indices) {
                    val aviso = Intent(ACCION_SMS).setPackage(packageName).putExtra("c", i)
                    avisos += PendingIntent.getBroadcast(this, base + i * 100 + p, aviso, PendingIntent.FLAG_IMMUTABLE)
                }
                manager.sendMultipartTextMessage(c.numero, null, partes, avisos, null)
                intentados++
            } catch (_: Exception) {
                marcar(i, "No se pudo", C_WARN)
            }
        }
        Emergencia.quitarAviso(this)
        if (modo != Emergencia.MODO_PRUEBA) responder("enviada", intentados)
    }

    private fun responder(estado: String, enviados: Int?) {
        if (alertaId <= 0 || prefs.server.isBlank()) return
        val server = prefs.server
        val token = prefs.token
        val id = alertaId
        Thread { Net.responderAlerta(server, token, id, estado, enviados) }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SMS || !envioPendiente) return
        envioPendiente = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            enviar() // ya lo habías confirmado manteniendo el botón; Android solo pidió el permiso
        } else {
            if (::sostener.isInitialized) sostener.reiniciar()
            compartir()
        }
    }

    /** Si el SMS no se puede (sin saldo, sin permiso), mandar el mismo texto por WhatsApp u otra app. */
    private fun compartir() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, mensaje())
        try {
            startActivity(Intent.createChooser(intent, "Enviar la alerta con…"))
        } catch (_: Exception) {
        }
    }

    private fun llamar911() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")))
        } catch (_: Exception) {
        }
    }

    private fun estoyBien() {
        Emergencia.quitarAviso(this)
        if (modo == Emergencia.MODO_DETECCION) responder("descartada", null)
        finish()
    }

    // ───────────────────────── Interfaz ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun label(text: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun boton(texto: String, fondo: Int, color: Int, borde: Int? = null, onClick: () -> Unit) = Button(this).apply {
        text = texto
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color)
        minHeight = dp(52)
        minimumHeight = dp(52)
        stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(fondo, 14, borde), rounded(0xFFFFFFFF.toInt(), 14))
        setOnClickListener { onClick() }
    }

    private fun construir(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(C_BG)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        scroll.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val prueba = modo == Emergencia.MODO_PRUEBA
        col.addView(label(if (prueba) "PRUEBA" else "EMERGENCIA", 11f, C_REC, medium = true).apply { letterSpacing = 0.18f })
        val titulo = when (modo) {
            Emergencia.MODO_DETECCION -> "¿Estás bien?"
            Emergencia.MODO_PRUEBA -> "Mensaje de prueba"
            else -> "Avisar a tus contactos"
        }
        col.addView(label(titulo, 30f, C_TEXT, medium = true).apply {
            accessibilityHeading()
        }, lp(top = 4))

        val explicacion = when (modo) {
            Emergencia.MODO_DETECCION -> "Eco escuchó" + (if (hora.isNotBlank()) " a las $hora" else "") +
                " algo que suena a peligro. Si necesitas ayuda, avisa a tus contactos."
            Emergencia.MODO_PRUEBA -> "Tus contactos reciben un SMS que dice que es una prueba. Sirve para que sepan " +
                "que los pusiste y para comprobar que el mensaje sale."
            else -> "Les llega un SMS con tu ubicación y que necesitas ayuda."
        }
        col.addView(label(explicacion, 15f, C_MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(top = 8))

        if (modo == Emergencia.MODO_DETECCION && (quePaso.isNotBlank() || frase.isNotBlank())) {
            val tarjeta = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0x26FF5C7A, 16, 0x66FF5C7A)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            if (quePaso.isNotBlank()) tarjeta.addView(label(quePaso, 16f, C_TEXT, medium = true))
            if (frase.isNotBlank()) tarjeta.addView(label("«$frase»", 14f, C_MUTED).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))
            col.addView(tarjeta, lp(top = 16))
        }

        // A quién y qué
        val para = if (contactos.isEmpty()) "Todavía no tienes contactos de emergencia."
        else "Se avisa a: " + contactos.joinToString(", ") { it.nombre }
        col.addView(label(para, 15f, if (contactos.isEmpty()) C_WARN else C_TEXT, medium = true), lp(top = 20))
        ubicacionTexto = label("", 13f, C_MUTED)
        if (!prueba) col.addView(ubicacionTexto, lp(top = 4))
        pintarUbicacion()

        // El botón que hay que mantener
        val zona = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        if (contactos.isEmpty()) {
            zona.addView(boton("Agregar contactos", C_REC, 0xFF1A0A0F.toInt()) {
                startActivity(Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SECCION, "emergencia")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }, lp(top = 24))
        } else {
            sostener = SostenerView(this, if (prueba) "Mantén para\nenviar la prueba" else "Mantén para\navisar") {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                enviar()
            }
            zona.addView(sostener, LinearLayout.LayoutParams(dp(196), dp(196)).apply { topMargin = dp(28) })
            zona.addView(label("Mantenlo presionado 2 segundos. Si lo sueltas antes, no se manda nada.", 13f, C_MUTED).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            }, lp(top = 14))
        }
        zonaBoton = zona
        col.addView(zona, lp())

        resultados = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        col.addView(resultados, lp(top = 20))

        // Salidas
        val salir = when (modo) {
            Emergencia.MODO_DETECCION -> "Estoy bien"
            else -> "Cancelar"
        }
        col.addView(boton(salir, C_SURFACE_2, C_TEXT, C_BORDER) { estoyBien() }, lp(top = 24))
        if (!prueba) {
            col.addView(boton("Llamar al 911", 0x00000000, C_REC, 0x66FF5C7A) { llamar911() }, lp(top = 10))
        }
        return scroll
    }

    private fun TextView.accessibilityHeading() {
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun mostrarResultados() {
        zonaBoton.visibility = View.GONE
        resultados.removeAllViews()
        estados.clear()
        resultados.visibility = View.VISIBLE
        resultados.addView(label(if (modo == Emergencia.MODO_PRUEBA) "Enviando la prueba…" else "Avisando a tus contactos…",
            17f, C_TEXT, medium = true))
        contactos.forEach { c ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                background = rounded(C_SURFACE, 12, C_BORDER)
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            fila.addView(label(c.nombre, 15f, C_TEXT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val estado = label("Enviando…", 13f, C_MUTED, medium = true)
            fila.addView(estado)
            estados += estado
            resultados.addView(fila, lp(top = 8))
        }
        resultados.addView(boton("Mandarlo también por WhatsApp u otra app", C_SURFACE_2, C_TEXT, C_BORDER) { compartir() }, lp(top = 16))
    }

    private fun marcar(i: Int, texto: String, color: Int) {
        if (i !in estados.indices) return
        estados[i].text = texto
        estados[i].setTextColor(color)
    }

    /**
     * Círculo rojo que hay que mantener presionado. Se va llenando; si lo sueltas (o deslizas el dedo
     * fuera) antes de 2 s, vuelve a cero y no pasa nada. El tiempo se mide con el reloj, no con
     * animaciones: aunque tengas las animaciones del teléfono apagadas, un toque nunca basta.
     */
    private inner class SostenerView(context: Context, private val texto: String, private val alCompletar: () -> Unit) : View(context) {
        private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_REC }
        private val anillo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(6).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        private val fondoAnillo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(6).toFloat()
            color = 0x40FFFFFF
        }
        private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = dp(19).toFloat()
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            color = 0xFFFFFFFF.toInt()
        }
        private val caja = RectF()
        private var progreso = 0f
        private var presionado = false
        private var inicio = 0L
        private var hecho = false

        private val tic = object : Runnable {
            override fun run() {
                if (!presionado || hecho) return
                progreso = ((SystemClock.uptimeMillis() - inicio).toFloat() / SOSTENER_MS).coerceIn(0f, 1f)
                invalidate()
                if (progreso >= 1f) {
                    hecho = true
                    presionado = false
                    alCompletar()
                } else {
                    postOnAnimation(this)
                }
            }
        }

        init {
            isClickable = true
            isFocusable = true
            contentDescription = texto.replace("\n", " ")
        }

        fun reiniciar() {
            hecho = false
            soltar()
        }

        private fun soltar() {
            presionado = false
            removeCallbacks(tic)
            progreso = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f - dp(8)
            relleno.color = if (presionado || hecho) C_REC_PRESS else C_REC_DEEP
            canvas.drawCircle(cx, cy, r, relleno)
            caja.set(cx - r - dp(4), cy - r - dp(4), cx + r + dp(4), cy + r + dp(4))
            canvas.drawArc(caja, 0f, 360f, false, fondoAnillo)
            if (progreso > 0f) canvas.drawArc(caja, -90f, 360f * progreso, false, anillo)
            val lineas = texto.split("\n")
            val alto = letra.fontSpacing
            var y = cy - alto * (lineas.size - 1) / 2f - (letra.ascent() + letra.descent()) / 2f
            for (l in lineas) {
                canvas.drawText(l, cx, y, letra)
                y += alto
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (hecho) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    presionado = true
                    inicio = SystemClock.uptimeMillis()
                    postOnAnimation(tic)
                }
                MotionEvent.ACTION_MOVE -> {
                    val fuera = event.x < 0 || event.y < 0 || event.x > width || event.y > height
                    if (fuera && presionado) soltar()
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> soltar()
                MotionEvent.ACTION_UP -> {
                    if (presionado) {
                        soltar()
                        performClick()
                    }
                }
            }
            return true
        }

        // Un toque no hace nada: hay que mantener. (performClick está para la accesibilidad.)
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Button::class.java.name
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "avisar a tus contactos"))
        }

        // Con TalkBack no se puede "mantener": se pide una confirmación aparte.
        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && !hecho) {
                AlertDialog.Builder(this@EmergenciaActivity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(if (modo == Emergencia.MODO_PRUEBA) "¿Enviar la prueba?" else "¿Avisar a tus contactos?")
                    .setMessage("Se manda un SMS a: " + contactos.joinToString(", ") { it.nombre })
                    .setPositiveButton("Sí, enviar") { _, _ ->
                        hecho = true
                        progreso = 1f
                        invalidate()
                        alCompletar()
                    }
                    .setNegativeButton("No", null)
                    .show()
                return true
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }
}
