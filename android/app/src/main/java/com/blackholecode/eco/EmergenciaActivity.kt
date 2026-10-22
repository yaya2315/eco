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
        /** El servicio de WhatsApp trae de vuelta esta pantalla después de cada envío. */
        const val EXTRA_VOLVER = "volver"
        private const val ACCION_SMS = "com.blackholecode.eco.SMS_ENVIADO"
        private const val SOSTENER_MS = 2_000L
        private const val SMS_REINTENTOS = 10
        private const val SMS_ESPERA_MS = 30_000L

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
        private const val C_WHATSAPP = 0xFF25D366.toInt()
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
    private lateinit var resumen: TextView
    private lateinit var siguiente: Button
    private lateinit var salirBoton: Button
    private lateinit var sostener: SostenerView
    private var contactos: List<Emergencia.Contacto> = emptyList()

    /** Cómo va cada contacto: su SMS (con reintentos si no hay señal) y si ya le abriste WhatsApp. */
    private inner class Envio(val contacto: Emergencia.Contacto) {
        var intento = 0
        var partesOk = 0
        var partesTotal = 1
        var smsListo = false
        var esperando = false
        var fallido = false
        var reintentos = 0
        var whatsappAbierto = false
        var whatsappListo = false
        var whatsappFallo = false
        lateinit var estado: TextView
    }

    private val envios = ArrayList<Envio>()
    private var usarSms = false
    private var whatsapp: String? = null
    private var conInternet = false
    /** WhatsApp sin tocar nada: el servicio de accesibilidad de Eco toca Enviar en cada chat. */
    private var automatico = false
    private var codigo = (System.currentTimeMillis() % 100_000).toInt() * 10

    private val receptorSms = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val i = intent.getIntExtra("c", -1)
            if (i !in envios.indices) return
            val e = envios[i]
            if (intent.getIntExtra("a", -1) != e.intento || e.smsListo) return
            if (resultCode == RESULT_OK) {
                e.partesOk++
                if (e.partesOk >= e.partesTotal) {
                    e.smsListo = true
                    pintar(i)
                }
            } else {
                falloSms(i)
            }
        }
    }

    // ───────────────────────── Ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_VOLVER, false) == true) {
            finish() // Android cerró la pantalla mientras estabas en WhatsApp: no hay nada que retomar
            return
        }
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent) // volver de WhatsApp no cambia nada: la alerta sigue siendo la misma
    }

    override fun onResume() {
        super.onResume()
        if (!enviado) return
        // Volviste de WhatsApp: qué se envió solo y qué no.
        envios.forEachIndexed { i, e ->
            if (i in EnvioWhatsapp.enviados) e.whatsappListo = true
            if (i in EnvioWhatsapp.fallidos) e.whatsappFallo = true
            pintar(i)
        }
        actualizarSiguiente()
        if (automatico && EnvioWhatsapp.armado < 0) ui.postDelayed({ siguienteAutomatico() }, 700)
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

    private fun mensaje(whatsapp: Boolean = false) =
        Emergencia.mensaje(modo, prefs.nombre, quePaso, ubicacion, bateria(), whatsapp)

    @Suppress("DEPRECATION")
    private fun sms(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** Por dónde va a salir, para decírtelo antes de mantener el botón. */
    private fun canales(): String {
        val wa = prefs.usarWhatsapp && Emergencia.paqueteWhatsapp(this) != null
        return when {
            wa && prefs.smsSiempre -> "por SMS y WhatsApp"
            wa -> "por WhatsApp (o por SMS si no tienes internet)"
            else -> "por SMS"
        }
    }

    /** Solo se llega aquí después de mantener presionado el botón (o confirmar con TalkBack). */
    private fun enviar() {
        if (enviado || contactos.isEmpty()) return
        conInternet = Emergencia.hayInternet(this)
        whatsapp = if (prefs.usarWhatsapp) Emergencia.paqueteWhatsapp(this) else null
        // Sin internet (o sin WhatsApp), el SMS es lo único que llega: entonces va siempre.
        usarSms = !conInternet || whatsapp == null || prefs.smsSiempre
        if (usarSms && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            envioPendiente = true
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQ_SMS)
            return
        }
        arrancarEnvio()
    }

    private fun arrancarEnvio() {
        enviado = true
        salirBoton.text = "Cerrar"
        envios.clear()
        contactos.forEach { envios += Envio(it) }
        mostrarResultados()
        if (usarSms) envios.indices.forEach { mandarSms(it) }
        Emergencia.quitarAviso(this)
        if (modo != Emergencia.MODO_PRUEBA) responder("enviada", envios.size)
        // WhatsApp: con el envío automático activado, Eco abre cada chat y toca Enviar solo, uno tras otro.
        // Sin él, el primer chat se abre con el mensaje listo y los demás con un toque cada uno.
        EnvioWhatsapp.reiniciar()
        automatico = whatsapp != null && EnvioWhatsapp.activo(this)
        if (whatsapp != null) {
            pedirDesbloqueo()
            ui.postDelayed({ if (automatico) siguienteAutomatico() else abrirWhatsapp(0) }, 500)
        }
    }

    private fun mandarSms(i: Int) {
        val e = envios[i]
        e.esperando = false
        e.fallido = false
        e.intento++
        e.partesOk = 0
        val manager = try { sms() } catch (_: Exception) { null }
        if (manager == null) {
            falloSms(i)
            return
        }
        try {
            val partes = manager.divideMessage(mensaje())
            e.partesTotal = partes.size
            val avisos = ArrayList<PendingIntent>(partes.size)
            for (p in partes.indices) {
                val aviso = Intent(ACCION_SMS).setPackage(packageName).putExtra("c", i).putExtra("a", e.intento)
                avisos += PendingIntent.getBroadcast(this, codigo++, aviso, PendingIntent.FLAG_IMMUTABLE)
            }
            manager.sendMultipartTextMessage(e.contacto.numero, null, partes, avisos, null)
        } catch (_: Exception) {
            falloSms(i)
        }
        pintar(i)
    }

    /** Sin señal o sin saldo: se reintenta cada 30 s (hasta 10 veces) mientras esta pantalla siga abierta. */
    private fun falloSms(i: Int) {
        val e = envios[i]
        if (e.esperando || e.smsListo) return
        if (e.reintentos < SMS_REINTENTOS) {
            e.reintentos++
            e.esperando = true
            ui.postDelayed({ if (!isDestroyed) mandarSms(i) }, SMS_ESPERA_MS)
        } else {
            e.fallido = true
        }
        pintar(i)
    }

    /** WhatsApp no abre con el teléfono bloqueado: Android te pide el PIN una vez y sigue solo. */
    private fun pedirDesbloqueo() {
        val km = getSystemService(android.app.KeyguardManager::class.java) ?: return
        if (km.isKeyguardLocked) km.requestDismissKeyguard(this, null)
    }

    private fun siguienteAutomatico() {
        if (!automatico || EnvioWhatsapp.armado >= 0 || isFinishing) return
        val i = envios.indexOfFirst { !it.whatsappAbierto }
        if (i < 0) {
            resumen.text = resumenTexto()
            return
        }
        EnvioWhatsapp.armar(i)
        abrirWhatsapp(i)
    }

    private fun abrirWhatsapp(i: Int) {
        if (i !in envios.indices) return
        val e = envios[i]
        if (Emergencia.abrirWhatsapp(this, e.contacto.numero, mensaje(whatsapp = true))) {
            e.whatsappAbierto = true
        }
        pintar(i)
        actualizarSiguiente()
    }

    private fun llamar(numero: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numero))))
        } catch (_: Exception) {
        }
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
            arrancarEnvio() // ya lo habías confirmado manteniendo el botón; Android solo pidió el permiso
        } else if (whatsapp != null) {
            usarSms = false
            arrancarEnvio()
        } else {
            if (::sostener.isInitialized) sostener.reiniciar()
            compartir()
        }
    }

    /** El mismo texto por la app que elijas (Telegram, Messenger…). */
    private fun compartir() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, mensaje(whatsapp = true))
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
        if (!enviado) {
            Emergencia.quitarAviso(this)
            if (modo == Emergencia.MODO_DETECCION) responder("descartada", null)
        }
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
                " algo que suena a peligro. Si necesitas ayuda, avisa a tus contactos ${canales()}."
            Emergencia.MODO_PRUEBA -> "Tus contactos reciben ${canales()} un mensaje que dice que es una prueba. Sirve " +
                "para que sepan que los pusiste y para comprobar que sale."
            else -> "Les llega ${canales()} tu ubicación y que necesitas ayuda."
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
        salirBoton = boton(salir, C_SURFACE_2, C_TEXT, C_BORDER) { estoyBien() }
        col.addView(salirBoton, lp(top = 24))
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
        resultados.visibility = View.VISIBLE
        val prueba = modo == Emergencia.MODO_PRUEBA
        resultados.addView(label(if (prueba) "Prueba en camino" else "Avisando a tus contactos", 17f, C_TEXT, medium = true))
        resumen = label(resumenTexto(), 14f, C_MUTED).apply { setLineSpacing(0f, 1.2f) }
        resultados.addView(resumen, lp(top = 4))
        envios.forEachIndexed { i, e ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C_SURFACE, 14, C_BORDER)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            fila.addView(label(e.contacto.nombre, 16f, C_TEXT, medium = true))
            e.estado = label("", 13f, C_MUTED, medium = true)
            fila.addView(e.estado, lp(top = 2))
            val acciones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (whatsapp != null) {
                acciones.addView(boton("WhatsApp", C_WHATSAPP, 0xFF0B2E17.toInt()) { abrirWhatsapp(i) }.apply {
                    contentDescription = "Abrir WhatsApp de ${e.contacto.nombre} con el mensaje listo"
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            acciones.addView(boton("Llamar", C_SURFACE_2, C_TEXT, C_BORDER) { llamar(e.contacto.numero) }.apply {
                contentDescription = "Llamar a ${e.contacto.nombre}"
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (whatsapp != null) marginStart = dp(10) })
            fila.addView(acciones, lp(top = 10))
            resultados.addView(fila, lp(top = 10))
            pintar(i)
        }
        siguiente = boton("", C_WHATSAPP, 0xFF0B2E17.toInt()) {
            envios.indexOfFirst { !it.whatsappAbierto }.takeIf { it >= 0 }?.let { abrirWhatsapp(it) }
        }
        siguiente.visibility = View.GONE
        resultados.addView(siguiente, lp(top = 14))
        resultados.addView(boton("Mandarlo por otra app…", C_SURFACE_2, C_TEXT, C_BORDER) { compartir() }, lp(top = 10))
    }

    private fun resumenTexto(): String {
        val terminado = automatico && envios.all { it.whatsappListo || it.whatsappFallo }
        return when {
            terminado && envios.all { it.whatsappListo } -> "Listo: Eco envió el WhatsApp a todos" +
                (if (usarSms) " y también un SMS." else ".")
            terminado -> "Eco envió lo que pudo. Si a alguien no le salió por WhatsApp, usa Llamar o su SMS."
            automatico && usarSms -> "Ya sale un SMS a cada uno, y Eco está mandando el WhatsApp de cada uno solo. No toques nada."
            automatico -> "Eco está mandando el WhatsApp de cada uno solo. No toques nada."
            !conInternet && whatsapp != null -> "Sin internet: les llega un SMS. Si también lo mandas por WhatsApp, " +
                "sale en cuanto vuelva la conexión."
            !conInternet -> "Sin internet: les llega un SMS. Si no hay señal, Eco lo reintenta cada 30 segundos."
            whatsapp != null && usarSms -> "Ya sale un SMS a cada uno. En WhatsApp toca Enviar en cada chat."
            whatsapp != null -> "En WhatsApp toca Enviar en cada chat."
            else -> "Les llega un SMS. Si no hay señal, Eco lo reintenta cada 30 segundos."
        }
    }

    /** Después de volver de WhatsApp: botón grande para el siguiente contacto que falta. */
    private fun actualizarSiguiente() {
        if (!::siguiente.isInitialized) return
        if (::resumen.isInitialized) resumen.text = resumenTexto()
        val falta = envios.firstOrNull { !it.whatsappAbierto }
        if (whatsapp == null || falta == null || automatico) {
            siguiente.visibility = View.GONE
            return
        }
        siguiente.text = "Siguiente: WhatsApp de ${falta.contacto.nombre}"
        siguiente.visibility = if (envios.any { it.whatsappAbierto }) View.VISIBLE else View.GONE
    }

    private fun pintar(i: Int) {
        if (i !in envios.indices) return
        val e = envios[i]
        if (!::resumen.isInitialized) return
        val partes = mutableListOf<String>()
        if (usarSms) {
            partes += when {
                e.smsListo -> "SMS enviado ✓"
                e.esperando -> "Sin señal: reintento en 30 s"
                e.fallido -> "El SMS no salió"
                else -> "Enviando SMS…"
            }
        }
        if (whatsapp != null) {
            partes += when {
                e.whatsappListo -> "WhatsApp enviado ✓"
                e.whatsappFallo -> "WhatsApp no salió"
                e.whatsappAbierto && automatico -> "Enviando WhatsApp…"
                e.whatsappAbierto -> "WhatsApp abierto"
                automatico -> "WhatsApp en espera"
                else -> "Falta WhatsApp"
            }
        }
        e.estado.text = partes.joinToString(" · ")
        e.estado.setTextColor(
            when {
                e.smsListo || e.whatsappListo -> C_LIVE
                e.esperando || e.fallido -> C_WARN
                else -> C_MUTED
            }
        )
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
