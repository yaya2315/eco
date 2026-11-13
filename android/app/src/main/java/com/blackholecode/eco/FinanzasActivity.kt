package com.blackholecode.eco

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tus finanzas, en el teléfono: lo que ganas y lo que gastas, por cuenta (Personal, Empresa…) y por
 * día, semana, mes o año. Es lo mismo que el apartado Finanzas del panel de la laptop: los números se
 * piden a la laptop y aquí anotas gastos e ingresos, apartas para tus metas y marcas los pagos fijos.
 * Si la laptop no está al alcance, lo que anotas se guarda aquí y se sube solo cuando vuelva.
 * Se construye en código, como MainActivity y PendientesActivity.
 */
class FinanzasActivity : Activity() {

    companion object {
        private const val C_BG = 0xFF0A0A12.toInt()
        private const val C_SURFACE = 0xFF14141F.toInt()
        private const val C_SURFACE_2 = 0xFF1C1C2A.toInt()
        private const val C_BORDER = 0xFF2A2A3D.toInt()
        private const val C_TRACK = 0xFF2E2E44.toInt()
        private const val C_TEXT = 0xFFEEEEF6.toInt()
        private const val C_MUTED = 0xFFA3A3BA.toInt()
        private const val C_FAINT = 0xFF8C8CA6.toInt()
        private const val C_ACCENT = 0xFF6B5BF5.toInt()
        private const val C_ACCENT_SOFT = 0xFFB8AEFF.toInt()
        private const val C_WARN = 0xFFFFB547.toInt()
        private const val C_REC = 0xFFFF5C7A.toInt()
        private const val C_LIVE = 0xFF3DDC97.toInt()
        private const val C_BARRA = 0xFF3A3A55.toInt()
        private const val C_OTRAS = 0xFF5A5A72.toInt()

        private const val LOCAL = "eco_finanzas"
        private const val MOVS_CORTOS = 25

        private val PERIODOS = listOf("dia" to "Día", "semana" to "Semana", "mes" to "Mes", "anio" to "Año")
        private val DE = mapOf("dia" to "del día", "semana" to "de la semana", "mes" to "del mes", "anio" to "del año")
        private val ANTES = mapOf(
            "dia" to "el día anterior", "semana" to "la semana anterior",
            "mes" to "el mes anterior", "anio" to "el año anterior"
        )
        private val SERIE = mapOf(
            "dia" to "GASTOS · ÚLTIMOS 7 DÍAS", "semana" to "GASTOS · ÚLTIMAS 6 SEMANAS",
            "mes" to "GASTOS · ÚLTIMOS 6 MESES", "anio" to "GASTOS · MES POR MES"
        )
        private val MESES = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    }

    private lateinit var prefs: Prefs
    private lateinit var local: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var col: LinearLayout
    private lateinit var arriba: LinearLayout       // cuentas, periodo y números grandes (se repinta)
    private lateinit var abajo: LinearLayout        // consejos, gráficos, presupuestos, metas, movimientos (se repinta)
    private lateinit var tipoGasto: TextView
    private lateinit var tipoIngreso: TextView
    private lateinit var etiquetaMoneda: TextView
    private lateinit var campoMonto: EditText
    private lateinit var campoNota: EditText
    private lateinit var filaCategorias: LinearLayout
    private lateinit var chipFecha: TextView
    private lateinit var botonAgregar: Button
    private lateinit var avisoCola: TextView

    private var datos: JSONObject? = null
    private var categorias: JSONObject? = null   // se guardan en el teléfono para poder anotar sin conexión
    private var cuentas: JSONArray = JSONArray()
    private var moneda = "$"
    private var cuenta = 0L
    private var periodo = "mes"
    private var ref: String? = null              // un día del periodo que estás viendo (null = hoy)
    private var tipo = "gasto"
    private var categoria = ""
    private var fecha: String? = null            // la fecha del movimiento que estás anotando (null = hoy)
    private var verTodos = false
    private var carga = 0
    private var cargado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        local = getSharedPreferences(LOCAL, Context.MODE_PRIVATE)
        cuenta = local.getLong("cuenta", 0L)
        periodo = local.getString("periodo", "mes") ?: "mes"
        if (PERIODOS.none { it.first == periodo }) periodo = "mes"
        moneda = local.getString("moneda", "$") ?: "$"
        categorias = try { JSONObject(local.getString("categorias", "") ?: "") } catch (_: Exception) { null }
        cuentas = try { JSONArray(local.getString("cuentas", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(C_BG)
        }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        val frame = FrameLayout(this)
        val wide = resources.configuration.screenWidthDp > 600
        frame.addView(
            col,
            FrameLayout.LayoutParams(if (wide) dp(560) else MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
        )
        scroll.addView(frame, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(scroll)
        construir()
    }

    override fun onResume() {
        super.onResume()
        cargar(silencioso = cargado)
    }

    override fun onDestroy() {
        carga++
        io.shutdownNow()
        super.onDestroy()
    }

    // ───────────────────────── Estructura fija ─────────────────────────

    private fun construir() {
        col.addView(label("←  Eco", 15f, C_ACCENT_SOFT, medium = true).apply {
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Volver a Eco"
            setOnClickListener { finish() }
        })
        col.addView(label("BLACK HOLE CODE", 11f, C_ACCENT_SOFT, medium = true).apply { letterSpacing = 0.18f }, lp(top = 16))
        col.addView(label("Finanzas", 30f, C_TEXT, medium = true).apply { marcarTitulo(this) }, lp(top = 4))
        col.addView(label("Lo que ganas y lo que gastas, cuenta por cuenta.", 15f, C_MUTED), lp(top = 2))

        arriba = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(arriba, lp(top = 14))

        col.addView(construirFormulario(), lp(top = 16))

        abajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(abajo, lp(top = 4))

        pintarArriba()
        pintarTipo()
        pintarCategorias()
        pintarFecha()
        pintarCola()
    }

    /** Añadir un gasto o un ingreso: lo primero que quieres tener a mano. */
    private fun construirFormulario(): View {
        val tarjeta = tarjeta()
        tarjeta.addView(sobre("AÑADIR MOVIMIENTO"))

        val tipos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tipoGasto = conmutador("Gasto") { tipo = "gasto"; pintarTipo(); pintarCategorias() }
        tipoIngreso = conmutador("Ingreso") { tipo = "ingreso"; pintarTipo(); pintarCategorias() }
        tipos.addView(tipoGasto, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tipos.addView(tipoIngreso, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        tarjeta.addView(tipos, lp(top = 10))

        val filaMonto = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        etiquetaMoneda = label(moneda, 24f, C_FAINT, medium = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        filaMonto.addView(etiquetaMoneda)
        campoMonto = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(0xFF55556E.toInt())
            setTextColor(C_TEXT)
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            minimumHeight = dp(56)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            contentDescription = "Monto"
            setOnEditorActionListener { _, accion, _ ->
                if (accion == EditorInfo.IME_ACTION_DONE) { agregar(); true } else false
            }
        }
        filaMonto.addView(campoMonto, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tarjeta.addView(filaMonto, lp(top = 8))
        tarjeta.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))

        filaCategorias = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tarjeta.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filaCategorias)
        }, lp(top = 12))

        campoNota = EditText(this).apply {
            hint = "Nota (opcional): almuerzo con Steven"
            setHintTextColor(C_FAINT)
            setTextColor(C_TEXT)
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(C_SURFACE_2, 14, C_BORDER)
            minimumHeight = dp(48)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { _, accion, _ ->
                if (accion == EditorInfo.IME_ACTION_DONE) { agregar(); true } else false
            }
        }
        tarjeta.addView(campoNota, lp(top = 12))

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipFecha = chip("Hoy", activo = false) { elegirFecha() }
        fila.addView(chipFecha, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        botonAgregar = boton("Añadir gasto", principal = true) { agregar() }
        fila.addView(botonAgregar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        tarjeta.addView(fila, lp(top = 12))

        avisoCola = label("", 13f, C_WARN).apply {
            visibility = View.GONE
            setLineSpacing(0f, 1.2f)
        }
        tarjeta.addView(avisoCola, lp(top = 10))
        return tarjeta
    }

    // ───────────────────────── Cargar ─────────────────────────

    private fun cargar(silencioso: Boolean = false) {
        val mia = ++carga
        if (prefs.server.isBlank()) {
            pintarError("Primero conecta tu laptop: en Eco, abre la sección «Tu laptop».")
            return
        }
        if (!silencioso) {
            abajo.removeAllViews()
            abajo.addView(label("Buscando en tu laptop…", 15f, C_MUTED), lp(top = 20))
        }
        val c = cuenta
        val p = periodo
        val r = ref
        io.execute {
            subirCola()
            val resp = Net.finanzas(prefs.server, prefs.token, c, p, r)
            ui.post {
                if (mia != carga || isFinishing) return@post
                pintarCola()
                val j = resp?.json
                if (j == null || !j.optBoolean("ok", false)) {
                    val texto = when {
                        resp == null -> "No encuentro tu laptop. Puedes anotar gastos igual: se guardan aquí y se suben cuando vuelva la conexión."
                        resp.codigo == 401 -> "La laptop respondió, pero el token no coincide."
                        j != null -> "Las finanzas están apagadas en tu laptop (config.toml → [finanzas] activo)."
                        resp.codigo == 404 -> "Tu laptop tiene una versión de Eco sin Finanzas. Actualízala (git pull) y reinicia Eco."
                        else -> "La laptop respondió con error ${resp.codigo}."
                    }
                    if (silencioso && cargado) avisar("No pude actualizar: tu laptop no responde.") else pintarError(texto)
                    return@post
                }
                datos = j
                cargado = true
                cuenta = j.optLong("cuenta", cuenta)
                moneda = j.optString("moneda", "$")
                categorias = j.optJSONObject("categorias") ?: categorias
                cuentas = j.optJSONArray("cuentas") ?: cuentas
                local.edit()
                    .putLong("cuenta", cuenta)
                    .putString("periodo", periodo)
                    .putString("moneda", moneda)
                    .putString("categorias", categorias?.toString() ?: "")
                    .putString("cuentas", cuentas.toString())
                    .apply()
                etiquetaMoneda.text = moneda
                pintarArriba()
                pintarCategorias()
                pintarAbajo()
            }
        }
    }

    private fun pintarError(texto: String) {
        abajo.removeAllViews()
        abajo.addView(avisoCard(texto), lp(top = 16))
        abajo.addView(boton("Reintentar") { cargar() }, lp(top = 12))
    }

    // ───────────────────────── Sin conexión: la cola ─────────────────────────

    private fun leerCola(): JSONArray =
        try { JSONArray(local.getString("cola", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun encolar(item: JSONObject) {
        synchronized(local) {
            val cola = leerCola()
            cola.put(item)
            local.edit().putString("cola", cola.toString()).apply()
        }
    }

    /** Sube lo que anotaste sin conexión (en el hilo de red). Se detiene si la laptop sigue sin responder. */
    private fun subirCola() {
        while (true) {
            val item: JSONObject = synchronized(local) {
                val cola = leerCola()
                if (cola.length() == 0) null else cola.optJSONObject(0)
            } ?: return
            val r = Net.finanzasCambio(prefs.server, prefs.token, "movimiento", item) ?: return
            if (r.codigo == 401) return
            // 200 = guardado (la laptop no lo duplica: lleva un uid); otro código = dato que nunca va a pasar.
            synchronized(local) {
                val cola = leerCola()
                val resto = JSONArray()
                for (i in 0 until cola.length()) {
                    val x = cola.optJSONObject(i) ?: continue
                    if (x.optString("uid") != item.optString("uid")) resto.put(x)
                }
                local.edit().putString("cola", resto.toString()).apply()
            }
        }
    }

    private fun pintarCola() {
        val n = leerCola().length()
        avisoCola.visibility = if (n == 0) View.GONE else View.VISIBLE
        avisoCola.text = if (n == 1) "1 movimiento guardado aquí: se sube solo cuando tu laptop esté al alcance."
        else "$n movimientos guardados aquí: se suben solos cuando tu laptop esté al alcance."
    }

    // ───────────────────────── Arriba: cuentas, periodo y números ─────────────────────────

    private fun pintarArriba() {
        arriba.removeAllViews()

        // Cuentas: solo separan lo que anotas (lo tuyo y lo de tu negocio).
        val filaCuentas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var primero = true
        for (i in 0 until cuentas.length()) {
            val c = cuentas.optJSONObject(i) ?: continue
            val id = c.optLong("id")
            val nombre = c.optString("nombre")
            val v = chip(nombre, activo = id == cuenta, relleno = true) {
                if (id != cuenta) {
                    cuenta = id
                    verTodos = false
                    pintarArriba()
                    cargar()
                }
            }
            v.contentDescription = "Cuenta $nombre" + if (id == cuenta) ", seleccionada" else ""
            filaCuentas.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = if (primero) 0 else dp(8) })
            primero = false
        }
        if (cargado) {
            filaCuentas.addView(
                chip("+ Cuenta", activo = false) { nuevaCuenta() }.apply { setTextColor(C_ACCENT_SOFT) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = if (primero) 0 else dp(8) }
            )
        }
        arriba.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filaCuentas)
        })

        // Día, semana, mes o año
        val filaPeriodos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C_SURFACE, 24, C_BORDER)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((clave, nombre) in PERIODOS) {
            val activo = clave == periodo
            val v = label(nombre, 14f, if (activo) C_TEXT else C_MUTED, medium = true).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                background = ripple(rounded(if (activo) C_SURFACE_2 else C_SURFACE, 20, if (activo) C_BORDER else null), 20)
                isClickable = true
                isFocusable = true
                contentDescription = "Ver por $nombre" + if (activo) ", seleccionado" else ""
                setOnClickListener {
                    if (periodo != clave) {
                        periodo = clave
                        verTodos = false
                        pintarArriba()
                        cargar()
                    }
                }
            }
            filaPeriodos.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        arriba.addView(filaPeriodos, lp(top = 12))

        val d = datos ?: return
        val p = d.optJSONObject("periodo") ?: return
        val esActual = p.optBoolean("es_actual", true)
        val hoy = d.optString("hoy")

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nav.addView(flecha("‹", "Periodo anterior", true) {
            ref = p.optString("anterior")
            verTodos = false
            cargar()
        })
        val titulo = if (p.optString("tipo") == "dia" && p.optString("ref") == hoy) "Hoy" else p.optString("titulo")
        nav.addView(label(titulo, 17f, C_TEXT, medium = true).apply {
            gravity = Gravity.CENTER
            marcarTitulo(this)
            if (!esActual) {
                isClickable = true
                isFocusable = true
                contentDescription = "$titulo. Toca para volver a hoy."
                setOnClickListener {
                    ref = null
                    verTodos = false
                    cargar()
                }
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nav.addView(flecha("›", "Periodo siguiente", !esActual) {
            ref = p.optString("siguiente")
            verTodos = false
            cargar()
        })
        arriba.addView(nav, lp(top = 10))
        if (!esActual) {
            arriba.addView(label("Toca el título para volver a hoy", 12f, C_FAINT).apply { gravity = Gravity.CENTER })
        }

        // Números grandes
        val t = d.optJSONObject("totales") ?: JSONObject()
        val a = d.optJSONObject("antes") ?: JSONObject()
        val ingreso = t.optLong("ingreso")
        val gasto = t.optLong("gasto")
        val ahorro = t.optLong("ahorro")
        val queda = t.optLong("queda")
        val de = DE[periodo] ?: ""
        var cambio = ""
        val gastoAntes = a.optLong("gasto")
        if (gasto > 0 && gastoAntes > 0) {
            val pct = ((gasto - gastoAntes) * 100.0 / gastoAntes).roundToInt()
            cambio = if (pct == 0) "igual que ${ANTES[periodo]}" else "${abs(pct)} % ${if (pct < 0) "menos" else "más"} que ${ANTES[periodo]}"
        }
        val fila1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila1.addView(numero("INGRESOS", dinero(ingreso), "lo que entró $de", C_TEXT), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        fila1.addView(
            numero("GASTOS", dinero(gasto), if (cambio.isNotEmpty()) cambio else "lo que salió $de", C_TEXT),
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(8) }
        )
        arriba.addView(fila1, lp(top = 10))
        val fila2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila2.addView(
            numero("AHORRADO", dinero(ahorro), if (ingreso > 0 && ahorro > 0) "${ahorro * 100 / ingreso} % de lo que entró" else "para tus metas", C_TEXT),
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
        )
        fila2.addView(
            numero(
                if (queda < 0) "TE PASASTE" else "TE QUEDA", dinero(abs(queda)),
                "en la cuenta: ${dinero(d.optLong("saldo"))}", if (queda < 0) C_REC else C_ACCENT_SOFT, destacado = true
            ),
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(8) }
        )
        arriba.addView(fila2, lp(top = 8))
    }

    private fun numero(titulo: String, valor: String, pie: String, color: Int, destacado: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_SURFACE, 16, if (destacado) color else C_BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isFocusable = true
            contentDescription = "$titulo: $valor. $pie"
            addView(label(titulo, 11f, C_MUTED, medium = true).apply { letterSpacing = 0.1f })
            addView(label(valor, 22f, color, medium = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(top = 2))
            addView(label(pie, 12f, C_FAINT).apply { setLineSpacing(0f, 1.15f) }, lp(top = 2))
        }

    private fun flecha(simbolo: String, descripcion: String, activa: Boolean, onClick: () -> Unit) =
        label(simbolo, 24f, if (activa) C_TEXT else C_BORDER).apply {
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            contentDescription = descripcion
            if (activa) {
                background = ripple(rounded(C_BG, 24, C_BORDER), 24)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            } else {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }

    // ───────────────────────── Formulario ─────────────────────────

    private fun pintarTipo() {
        for ((v, clave) in listOf(tipoGasto to "gasto", tipoIngreso to "ingreso")) {
            val activo = tipo == clave
            v.setTextColor(if (activo) C_TEXT else C_MUTED)
            v.background = ripple(rounded(if (activo) 0xFF23213F.toInt() else C_SURFACE, 12, if (activo) C_ACCENT_SOFT else C_BORDER), 12)
            v.contentDescription = v.text.toString() + if (activo) ", seleccionado" else ""
        }
        botonAgregar.text = if (tipo == "ingreso") "Añadir ingreso" else "Añadir gasto"
    }

    private fun pintarCategorias() {
        filaCategorias.removeAllViews()
        val lista = categorias?.optJSONArray(tipo)
        if (lista == null || lista.length() == 0) {
            filaCategorias.addView(label("Las categorías aparecen cuando conectes con tu laptop.", 13f, C_FAINT))
            return
        }
        var existe = false
        for (i in 0 until lista.length()) {
            if (lista.optJSONObject(i)?.optString("clave") == categoria) existe = true
        }
        if (!existe) categoria = lista.optJSONObject(0)?.optString("clave") ?: ""
        var primero = true
        for (i in 0 until lista.length()) {
            val c = lista.optJSONObject(i) ?: continue
            val clave = c.optString("clave")
            val nombre = c.optString("nombre")
            val v = chip(nombre, activo = clave == categoria, relleno = true) {
                categoria = clave
                pintarCategorias()
            }
            v.contentDescription = "Categoría $nombre" + if (clave == categoria) ", seleccionada" else ""
            filaCategorias.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = if (primero) 0 else dp(8) })
            primero = false
        }
    }

    private fun elegirFecha() {
        if (fecha != null) {  // tocar la fecha puesta vuelve a «Hoy»
            fecha = null
            pintarFecha()
            return
        }
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, a, m, d ->
            fecha = String.format(Locale.US, "%04d-%02d-%02d", a, m + 1, d)
            pintarFecha()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pintarFecha() {
        val f = fecha
        chipFecha.text = if (f == null) "Hoy" else "${f.substring(8, 10)}/${f.substring(5, 7)}  ✕"
        chipFecha.contentDescription = if (f == null) "Fecha: hoy. Toca para elegir otro día." else "Fecha $f. Toca para volver a hoy."
        chipFecha.setTextColor(if (f == null) C_MUTED else C_ACCENT_SOFT)
    }

    private fun hoyIso(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun agregar() {
        val texto = campoMonto.text.toString().trim().replace(',', '.')
        val valor = texto.toDoubleOrNull()
        if (valor == null || valor <= 0.0) {
            campoMonto.requestFocus()
            avisar("Escribe cuánto fue.")
            return
        }
        if (prefs.server.isBlank()) {
            avisar("Primero conecta tu laptop.")
            return
        }
        val esIngreso = tipo == "ingreso"
        val item = JSONObject()
            .put("tipo", tipo)
            .put("monto", texto)
            .put("categoria", categoria)
            .put("nota", campoNota.text.toString().trim())
            .put("fecha", fecha ?: hoyIso())
            .put("origen", "app")
            .put("uid", UUID.randomUUID().toString())
        if (cuenta > 0) item.put("cuenta", cuenta)
        botonAgregar.isEnabled = false
        io.execute {
            val r = Net.finanzasCambio(prefs.server, prefs.token, "movimiento", item)
            ui.post {
                if (isFinishing) return@post
                botonAgregar.isEnabled = true
                when {
                    r == null -> {
                        encolar(item)
                        limpiarFormulario()
                        pintarCola()
                        avisarLargo("Sin conexión con tu laptop: lo guardé aquí y se sube solo cuando vuelva.")
                    }
                    r.codigo == 200 -> {
                        limpiarFormulario()
                        avisarLargo(avisoDe(r.json) ?: if (esIngreso) "Ingreso anotado" else "Gasto anotado")
                        cargar(silencioso = true)
                    }
                    r.codigo == 401 -> avisar("El token no coincide con el de tu laptop.")
                    r.codigo == 404 -> avisar("Tu laptop todavía no tiene Finanzas: actualiza Eco allá.")
                    else -> avisar("No se pudo anotar (error ${r.codigo}).")
                }
            }
        }
    }

    private fun limpiarFormulario() {
        campoMonto.setText("")
        campoNota.setText("")
        fecha = null
        pintarFecha()
    }

    /** El primer aviso que mandó la laptop (presupuesto, sugerencia de ahorro, meta cumplida), si hubo. */
    private fun avisoDe(j: JSONObject?): String? {
        val texto = j?.optJSONArray("avisos")?.optString(0) ?: return null
        return if (texto.isBlank()) null else texto
    }

    // ───────────────────────── Abajo: consejos, gráficos y listas ─────────────────────────

    private fun pintarAbajo() {
        abajo.removeAllViews()
        val d = datos ?: return
        pintarConsejo(d)
        pintarDona(d)
        pintarBarras(d)
        pintarPresupuestos(d)
        pintarMetas(d)
        pintarFijos(d)
        pintarMovimientos(d)
        abajo.addView(boton("Actualizar") { cargar() }, lp(top = 24))
    }

    private fun pintarConsejo(d: JSONObject) {
        val c = d.optJSONObject("consejo") ?: return
        val textos = c.optJSONArray("textos") ?: JSONArray()
        val diario = c.optLong("diario")
        if (textos.length() == 0 && diario <= 0) return
        val tarjeta = tarjeta()
        tarjeta.addView(sobre("PARA AHORRAR ESTE MES"))
        if (c.optLong("libre") > 0 && diario > 0) {
            tarjeta.addView(label("${dinero(diario)} por día", 22f, C_TEXT, medium = true), lp(top = 8))
            val dias = c.optInt("dias")
            tarjeta.addView(
                label("es lo que puedes gastar para llegar a fin de mes (${if (dias == 1) "1 día" else "$dias días"}).", 13f, C_FAINT)
                    .apply { setLineSpacing(0f, 1.2f) },
                lp(top = 2)
            )
        }
        for (i in 0 until textos.length()) {
            tarjeta.addView(label("•  " + textos.optString(i), 14f, C_MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(top = 8))
        }
        abajo.addView(tarjeta, lp(top = 16))
    }

    private fun pintarDona(d: JSONObject) {
        val tarjeta = tarjeta()
        tarjeta.addView(sobre("GASTOS POR CATEGORÍA"))
        val cats = d.optJSONArray("por_categoria") ?: JSONArray()
        val total = d.optJSONObject("totales")?.optLong("gasto") ?: 0L
        if (total <= 0L || cats.length() == 0) {
            tarjeta.addView(label("Sin gastos en este periodo.", 14f, C_FAINT), lp(top = 8))
            abajo.addView(tarjeta, lp(top = 16))
            return
        }
        // Hasta 6 categorías; el resto, juntas en «Otras». El nombre siempre va escrito al lado.
        val nombres = ArrayList<String>()
        val montos = ArrayList<Long>()
        val colores = ArrayList<Int>()
        var resto = 0L
        var nResto = 0
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            if (i < 6 || cats.length() == 7) {
                nombres.add(c.optString("nombre"))
                montos.add(c.optLong("total"))
                colores.add(color(c.optString("color")))
            } else {
                resto += c.optLong("total")
                nResto++
            }
        }
        if (nResto > 0) {
            nombres.add("Otras $nResto")
            montos.add(resto)
            colores.add(C_OTRAS)
        }
        val partes = FloatArray(montos.size) { montos[it].toFloat() / total.toFloat() }
        val descripcion = StringBuilder("Gastos por categoría: ")
        for (i in nombres.indices) {
            if (i > 0) descripcion.append(", ")
            descripcion.append(nombres[i]).append(" ").append((partes[i] * 100).roundToInt()).append(" %")
        }

        val grafico = FrameLayout(this)
        grafico.addView(DonaView(this).apply {
            poner(colores.toIntArray(), partes)
            contentDescription = descripcion.toString()
        }, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        val centro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(label(dinero(total), 16f, C_TEXT, medium = true).apply { gravity = Gravity.CENTER })
            addView(label("gastado", 11f, C_FAINT).apply { gravity = Gravity.CENTER })
        }
        grafico.addView(centro, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        tarjeta.addView(grafico, LinearLayout.LayoutParams(MATCH_PARENT, dp(150)).apply { topMargin = dp(12) })

        for (i in nombres.indices) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(28)
            }
            fila.addView(View(this).apply { background = rounded(colores[i], 3) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            fila.addView(label(nombres[i], 14f, C_TEXT).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            fila.addView(label(dinero(montos[i]), 14f, C_TEXT, medium = true))
            fila.addView(label("${(partes[i] * 100).roundToInt()} %", 13f, C_FAINT).apply {
                gravity = Gravity.END
                minimumWidth = dp(44)
            })
            tarjeta.addView(fila, lp(top = if (i == 0) 12 else 2))
        }
        abajo.addView(tarjeta, lp(top = 16))
    }

    private fun pintarBarras(d: JSONObject) {
        val serie = d.optJSONArray("serie") ?: return
        val n = serie.length()
        if (n == 0) return
        val tarjeta = tarjeta()
        tarjeta.addView(sobre(SERIE[periodo] ?: "GASTOS"))
        val etiquetas = Array(n) { "" }
        val valores = LongArray(n)
        val textos = Array(n) { "" }
        var actual = -1
        var maximo = 0L
        var entro = 0L
        var salio = 0L
        for (i in 0 until n) {
            val s = serie.optJSONObject(i) ?: continue
            valores[i] = s.optLong("gasto")
            entro += s.optLong("ingreso")
            salio += valores[i]
            if (valores[i] > maximo) maximo = valores[i]
            if (s.optBoolean("actual", false)) actual = i
            val e = s.optString("etiqueta")
            etiquetas[i] = if (n > 8) e.take(1).uppercase(Locale.ROOT) else e
        }
        val descripcion = StringBuilder()
        for (i in 0 until n) {
            // Con 12 barras solo llevan número la de ahora y la más alta: así no se enciman.
            if (valores[i] > 0 && (n <= 8 || i == actual || valores[i] == maximo)) textos[i] = dineroCorto(valores[i])
            if (i > 0) descripcion.append(", ")
            descripcion.append(serie.optJSONObject(i)?.optString("etiqueta") ?: "").append(": ").append(dinero(valores[i]))
        }
        tarjeta.addView(BarrasView(this).apply {
            poner(etiquetas, valores, textos, actual)
            contentDescription = descripcion.toString()
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(170)).apply { topMargin = dp(8) })
        tarjeta.addView(
            label("En total: entró ${dinero(entro)} y salió ${dinero(salio)}.", 13f, C_FAINT).apply { setLineSpacing(0f, 1.2f) },
            lp(top = 8)
        )
        abajo.addView(tarjeta, lp(top = 16))
    }

    private fun pintarPresupuestos(d: JSONObject) {
        val lista = d.optJSONArray("presupuestos") ?: JSONArray()
        val tarjeta = tarjeta()
        tarjeta.addView(sobre("PRESUPUESTOS DEL MES"))
        if (lista.length() == 0) {
            tarjeta.addView(
                label("Ponle un tope por mes a una categoría. Eco te avisa al llegar al 80 % y si te pasas.", 14f, C_FAINT)
                    .apply { setLineSpacing(0f, 1.25f) },
                lp(top = 8)
            )
        }
        for (i in 0 until lista.length()) {
            val p = lista.optJSONObject(i) ?: continue
            val nombre = p.optString("nombre")
            val limite = p.optLong("limite")
            val gastado = p.optLong("gastado")
            val resta = p.optLong("resta")
            val pct = if (limite > 0) (gastado * 100 / limite).toInt() else 0
            val tono = if (resta < 0) C_REC else if (pct >= 80) C_WARN else C_ACCENT_SOFT
            val pie = if (resta < 0) "te pasaste ${dinero(-resta)}" else "quedan ${dinero(resta)}"
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple(rounded(C_SURFACE, 10), 10)
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = "$nombre: ${dinero(gastado)} de ${dinero(limite)}, $pie. Toca para cambiar el tope."
                setOnClickListener { pedirTope(p.optString("categoria"), nombre, limite) }
            }
            fila.addView(filaDoble(nombre, "${dinero(gastado)} / ${dinero(limite)}"))
            fila.addView(progreso(pct, tono), lp(top = 6))
            fila.addView(label(pie, 12f, if (resta < 0) C_REC else if (pct >= 80) C_WARN else C_FAINT), lp(top = 4))
            tarjeta.addView(fila, lp(top = 8))
        }
        tarjeta.addView(boton("+ Presupuesto") { nuevoPresupuesto() }, lp(top = 12))
        abajo.addView(tarjeta, lp(top = 16))
    }

    private fun pintarMetas(d: JSONObject) {
        val lista = d.optJSONArray("metas") ?: JSONArray()
        val tarjeta = tarjeta()
        tarjeta.addView(sobre("METAS DE AHORRO"))
        if (lista.length() == 0) {
            tarjeta.addView(
                label("Dime para qué quieres ahorrar y cuánto. Eco calcula cuánto apartar cada semana.", 14f, C_FAINT)
                    .apply { setLineSpacing(0f, 1.25f) },
                lp(top = 8)
            )
        }
        for (i in 0 until lista.length()) {
            val m = lista.optJSONObject(i) ?: continue
            val id = m.optLong("id")
            val nombre = m.optString("nombre")
            val falta = m.optLong("falta")
            val porSemana = if (m.isNull("por_semana")) 0L else m.optLong("por_semana")
            val dias = if (m.isNull("dias")) null else m.optInt("dias")
            val pie = when {
                falta <= 0L -> "¡Meta cumplida!"
                porSemana > 0L -> "aparta ${dinero(porSemana)} por semana" +
                    (if (dias != null) " · " + (if (dias == 0) "es hoy" else if (dias == 1) "falta 1 día" else "faltan $dias días") else "")
                dias != null && dias < 0 -> "la fecha ya pasó · te faltan ${dinero(falta)}"
                else -> "te faltan ${dinero(falta)}"
            }
            val bloque = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                contentDescription = "$nombre: ${dinero(m.optLong("ahorrado"))} de ${dinero(m.optLong("objetivo"))}, $pie"
            }
            bloque.addView(filaDoble(nombre, "${dinero(m.optLong("ahorrado"))} / ${dinero(m.optLong("objetivo"))}"))
            bloque.addView(progreso(m.optInt("pct"), if (falta <= 0L) C_LIVE else C_ACCENT_SOFT), lp(top = 6))
            bloque.addView(label(pie, 12f, if (falta <= 0L) C_LIVE else C_FAINT), lp(top = 4))
            tarjeta.addView(bloque, lp(top = 12))
            if (falta > 0L) {
                tarjeta.addView(boton("Apartar dinero para «$nombre»") { apartar(id, nombre, falta, porSemana) }, lp(top = 8))
            }
        }
        tarjeta.addView(boton("+ Meta") { nuevaMeta() }, lp(top = 12))
        abajo.addView(tarjeta, lp(top = 16))
    }

    private fun pintarFijos(d: JSONObject) {
        val lista = d.optJSONArray("fijos") ?: JSONArray()
        val tarjeta = tarjeta()
        tarjeta.addView(sobre("PAGOS DE CADA MES"))
        if (lista.length() == 0) {
            tarjeta.addView(
                label("Internet, colegiatura, suscripciones… Eco te avisa un día antes y los anota con un toque.", 14f, C_FAINT)
                    .apply { setLineSpacing(0f, 1.25f) },
                lp(top = 8)
            )
        }
        for (i in 0 until lista.length()) {
            val f = lista.optJSONObject(i) ?: continue
            val id = f.optLong("id")
            val nota = f.optString("nota")
            val esIngreso = f.optString("tipo") == "ingreso"
            val listo = f.optBoolean("listo", false)
            val enDias = f.optInt("en_dias")
            val cuando = when {
                listo -> "anotado este mes"
                enDias > 1 -> "en $enDias días"
                enDias == 1 -> "mañana"
                enDias == 0 -> "es hoy"
                else -> "ya tocaba"
            }
            val bloque = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                contentDescription = "$nota, ${dinero(f.optLong("monto"))}, día ${f.optInt("dia")}, $cuando"
            }
            bloque.addView(filaDoble(nota, dinero(f.optLong("monto"), signo = esIngreso)))
            bloque.addView(
                label("día ${f.optInt("dia")} · ${f.optString("nombre")} · $cuando", 12f, if (listo) C_LIVE else if (enDias <= 0) C_WARN else C_FAINT),
                lp(top = 2)
            )
            tarjeta.addView(bloque, lp(top = 12))
            if (!listo) {
                tarjeta.addView(boton(if (esIngreso) "Ya me pagaron: $nota" else "Ya lo pagué: $nota") {
                    cambiar("fijo", JSONObject().put("id", id).put("registrar", true).put("origen", "app"), "Anotado en tus finanzas")
                }, lp(top = 8))
            }
        }
        tarjeta.addView(boton("+ Pago fijo") { nuevoFijo() }, lp(top = 12))
        abajo.addView(tarjeta, lp(top = 16))
    }

    private fun pintarMovimientos(d: JSONObject) {
        val lista = d.optJSONArray("movimientos") ?: JSONArray()
        val hoy = d.optString("hoy")
        abajo.addView(label("MOVIMIENTOS ${(DE[periodo] ?: "").uppercase(Locale.ROOT)}  ·  ${lista.length()}", 12f, C_ACCENT_SOFT, medium = true).apply {
            letterSpacing = 0.12f
            marcarTitulo(this)
        }, lp(top = 28))
        if (lista.length() == 0) {
            abajo.addView(
                avisoCard("Nada anotado ${DE[periodo]}. También puedes escribirle a Eco por Telegram: «gasté 3.50 en almuerzo»."),
                lp(top = 8)
            )
            return
        }
        val limite = if (verTodos) lista.length() else minOf(lista.length(), MOVS_CORTOS)
        for (i in 0 until limite) {
            val m = lista.optJSONObject(i) ?: continue
            abajo.addView(filaMovimiento(m, hoy), lp(top = 8))
        }
        if (limite < lista.length()) {
            abajo.addView(boton("Ver los ${lista.length()} movimientos") {
                verTodos = true
                pintarAbajo()
            }, lp(top = 12))
        }
    }

    /** Un movimiento: tocarlo ofrece borrarlo (para corregirlo, bórralo y anótalo de nuevo). */
    private fun filaMovimiento(m: JSONObject, hoy: String): View {
        val id = m.optLong("id")
        val tipoMov = m.optString("tipo")
        val nombre = m.optString("nombre")
        val nota = m.optString("nota")
        val monto = m.optLong("monto")
        val de = when (m.optString("origen")) {
            "telegram" -> "por Telegram"
            "voz" -> "por voz"
            "fijo" -> "pago fijo"
            else -> ""
        }
        val detalle = listOf(fechaCorta(m.optString("fecha"), hoy), nota, de).filter { it.isNotEmpty() }.joinToString(" · ")
        val texto = when (tipoMov) {
            "ingreso" -> dinero(monto, signo = true)
            "gasto" -> dinero(-monto)
            else -> dinero(monto)
        }
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(rounded(C_SURFACE, 16, C_BORDER), 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
            contentDescription = "$nombre, $texto, $detalle. Toca para borrar."
        }
        fila.addView(View(this).apply { background = rounded(color(m.optString("color")), 5) }, LinearLayout.LayoutParams(dp(10), dp(10)))
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(label(nombre, 15f, C_TEXT, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        textos.addView(label(detalle, 13f, C_FAINT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(top = 1))
        fila.addView(textos, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        fila.addView(
            label(texto, 15f, if (tipoMov == "ingreso") C_LIVE else if (tipoMov == "ahorro") C_ACCENT_SOFT else C_TEXT, medium = true),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
        )
        fila.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Borrar este movimiento?")
                .setMessage("$nombre · ${dinero(monto)}" + if (detalle.isNotEmpty()) "\n$detalle" else "")
                .setPositiveButton("Borrar") { _, _ ->
                    cambiar("movimiento/borrar", JSONObject().put("id", id), "Movimiento borrado")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        return fila
    }

    // ───────────────────────── Cambios: presupuestos, metas, pagos fijos, cuentas ─────────────────────────

    /** Manda un cambio a la laptop y, si se guardó, vuelve a pedir los números. */
    private fun cambiar(accion: String, d: JSONObject, exito: String) {
        if (prefs.server.isBlank()) {
            avisar("Primero conecta tu laptop.")
            return
        }
        io.execute {
            val r = Net.finanzasCambio(prefs.server, prefs.token, accion, d)
            ui.post {
                if (isFinishing) return@post
                when {
                    r == null -> avisar("No encuentro tu laptop. Inténtalo cuando estés en tu red.")
                    r.codigo == 200 -> {
                        avisarLargo(avisoDe(r.json) ?: exito)
                        cargar(silencioso = true)
                    }
                    else -> avisar("No se pudo guardar. Revisa los datos e inténtalo otra vez.")
                }
            }
        }
    }

    private fun nuevoPresupuesto() {
        val lista = categorias?.optJSONArray("gasto") ?: return
        val claves = ArrayList<String>()
        val nombres = ArrayList<String>()
        for (i in 0 until lista.length()) {
            val c = lista.optJSONObject(i) ?: continue
            claves.add(c.optString("clave"))
            nombres.add(c.optString("nombre"))
        }
        AlertDialog.Builder(this)
            .setTitle("¿De qué categoría?")
            .setItems(nombres.toTypedArray()) { _, i -> pedirTope(claves[i], nombres[i], 0L) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirTope(clave: String, nombre: String, actual: Long) {
        val monto = campoDialogo("Tope por mes, ej. 60", if (actual > 0) plano(actual) else "", dinero = true)
        val b = AlertDialog.Builder(this)
            .setTitle("Tope de $nombre")
            .setMessage("Lo más que quieres gastar por mes. Eco te avisa al llegar al 80 % y si te pasas.")
            .setView(cajaDialogo(monto))
            .setPositiveButton("Guardar") { _, _ ->
                val texto = monto.text.toString().trim().replace(',', '.')
                val v = texto.toDoubleOrNull()
                if (v == null || v <= 0.0) avisar("Escribe un tope mayor que cero.")
                else cambiar("presupuesto", JSONObject().put("cuenta", cuenta).put("categoria", clave).put("monto", texto), "Presupuesto guardado")
            }
            .setNegativeButton("Cancelar", null)
        if (actual > 0) {
            b.setNeutralButton("Quitar tope") { _, _ ->
                cambiar("presupuesto", JSONObject().put("cuenta", cuenta).put("categoria", clave).put("monto", ""), "Tope quitado")
            }
        }
        b.show()
    }

    private fun nuevaMeta() {
        val nombre = campoDialogo("¿Para qué ahorras? ej. Laptop nueva", "", dinero = false)
        val objetivo = campoDialogo("¿Cuánto quieres juntar? ej. 800", "", dinero = true)
        var limite: String? = null
        val chipLimite = chip("+ Fecha límite (opcional)", activo = false) {}
        chipLimite.setOnClickListener {
            if (limite != null) {
                limite = null
                chipLimite.text = "+ Fecha límite (opcional)"
            } else {
                val c = Calendar.getInstance()
                DatePickerDialog(this, { _, a, m, d ->
                    val elegida = String.format(Locale.US, "%04d-%02d-%02d", a, m + 1, d)
                    limite = elegida
                    chipLimite.text = "Para el ${elegida.substring(8, 10)}/${elegida.substring(5, 7)}/${elegida.substring(0, 4)}  ✕"
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Nueva meta de ahorro")
            .setMessage("Con fecha, Eco calcula cuánto apartar cada semana.")
            .setView(cajaDialogo(nombre, objetivo, chipLimite))
            .setPositiveButton("Guardar") { _, _ ->
                val n = nombre.text.toString().trim()
                val texto = objetivo.text.toString().trim().replace(',', '.')
                val v = texto.toDoubleOrNull()
                if (n.isEmpty() || v == null || v <= 0.0) {
                    avisar("Ponle nombre y cuánto quieres juntar.")
                } else {
                    val d = JSONObject().put("cuenta", cuenta).put("nombre", n).put("objetivo", texto)
                    val f = limite
                    if (f != null) d.put("fecha", f)
                    cambiar("meta", d, "Meta guardada")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun apartar(id: Long, nombre: String, falta: Long, porSemana: Long) {
        val sugerido = if (porSemana > 0L) minOf(porSemana, falta) else 0L
        val monto = campoDialogo("¿Cuánto apartas? ej. 10", if (sugerido > 0L) plano(sugerido) else "", dinero = true)
        AlertDialog.Builder(this)
            .setTitle("Apartar para «$nombre»")
            .setMessage("Se descuenta de «Te queda» y se suma a la meta. Te faltan ${dinero(falta)}.")
            .setView(cajaDialogo(monto))
            .setPositiveButton("Apartar") { _, _ ->
                val texto = monto.text.toString().trim().replace(',', '.')
                val v = texto.toDoubleOrNull()
                if (v == null || v <= 0.0) avisar("Escribe cuánto vas a apartar.")
                else cambiar("meta", JSONObject().put("id", id).put("aportar", texto).put("origen", "app"), "Ahorro anotado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun nuevoFijo() {
        val nombre = campoDialogo("¿Qué es? ej. Internet", "", dinero = false)
        val monto = campoDialogo("¿Cuánto? ej. 25", "", dinero = true)
        val dia = campoDialogo("Día del mes (1 a 31)", "", dinero = false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Nuevo pago de cada mes")
            .setMessage("Eco te avisa por Telegram un día antes y el mismo día.")
            .setView(cajaDialogo(nombre, monto, dia))
            .setPositiveButton("Guardar") { _, _ ->
                val n = nombre.text.toString().trim()
                val texto = monto.text.toString().trim().replace(',', '.')
                val v = texto.toDoubleOrNull()
                val numero = dia.text.toString().trim().toIntOrNull()
                if (n.isEmpty() || v == null || v <= 0.0 || numero == null || numero < 1 || numero > 31) {
                    avisar("Ponle nombre, monto y el día del mes (1 a 31).")
                } else {
                    cambiar(
                        "fijo",
                        JSONObject().put("cuenta", cuenta).put("nota", n).put("monto", texto).put("dia", numero).put("tipo", "gasto"),
                        "Pago fijo guardado"
                    )
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun nuevaCuenta() {
        val nombre = campoDialogo("Nombre, ej. Black Hole Code", "", dinero = false)
        fun crear(tipoCuenta: String) {
            val n = nombre.text.toString().trim()
            if (n.isEmpty()) {
                avisar("Ponle un nombre a la cuenta.")
                return
            }
            if (prefs.server.isBlank()) {
                avisar("Primero conecta tu laptop.")
                return
            }
            io.execute {
                val r = Net.finanzasCambio(prefs.server, prefs.token, "cuenta", JSONObject().put("nombre", n).put("tipo", tipoCuenta))
                ui.post {
                    if (isFinishing) return@post
                    val nueva = r?.json?.optLong("cuenta", 0L) ?: 0L
                    if (r?.codigo == 200 && nueva > 0L) {
                        cuenta = nueva
                        avisar("Cuenta creada")
                        cargar()
                    } else {
                        avisar(if (r == null) "No encuentro tu laptop." else "No se pudo crear la cuenta.")
                    }
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Nueva cuenta")
            .setMessage("Una cuenta solo separa lo que anotas; no se conecta a ningún banco. ¿De qué es?")
            .setView(cajaDialogo(nombre))
            .setPositiveButton("Personal") { _, _ -> crear("personal") }
            .setNeutralButton("Empresa") { _, _ -> crear("empresa") }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ───────────────────────── Piezas ─────────────────────────

    /** Centavos → "$1,486.00" (con signo: "+$450.00" o "−$38.40"). */
    private fun dinero(c: Long, signo: Boolean = false): String {
        val n = String.format(Locale.US, "%,.2f", abs(c) / 100.0)
        return (if (c < 0) "−" else if (signo && c > 0) "+" else "") + moneda + n
    }

    /** Para encima de las barras: 30800 → "308", 131538 → "1.3k". */
    private fun dineroCorto(c: Long): String {
        val n = c / 100.0
        return when {
            n >= 10000 -> "${(n / 1000).roundToInt()}k"
            n >= 1000 -> String.format(Locale.US, "%.1fk", n / 1000).replace(".0k", "k")
            n >= 100 || c % 100 == 0L -> n.roundToInt().toString()
            else -> String.format(Locale.US, "%.2f", n)
        }
    }

    /** Centavos como se escriben en un campo: 2500 → "25.00". */
    private fun plano(c: Long): String = String.format(Locale.US, "%.2f", c / 100.0)

    private fun fechaCorta(iso: String, hoy: String): String {
        if (iso.length < 10) return ""
        if (iso == hoy) return "hoy"
        val mes = iso.substring(5, 7).toIntOrNull() ?: return iso
        val dia = iso.substring(8, 10).toIntOrNull() ?: return iso
        val base = "$dia ${MESES[(mes - 1).coerceIn(0, 11)]}"
        return if (hoy.length >= 4 && iso.substring(0, 4) != hoy.substring(0, 4)) "$base ${iso.substring(0, 4)}" else base
    }

    private fun color(hex: String): Int = try { Color.parseColor(hex) } catch (_: Exception) { C_FAINT }

    private fun sobre(texto: String) = label(texto, 11f, C_MUTED, medium = true).apply {
        letterSpacing = 0.12f
        marcarTitulo(this)
    }

    private fun filaDoble(izquierda: String, derecha: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(izquierda, 15f, C_TEXT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label(derecha, 13f, C_MUTED), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    }

    private fun progreso(pct: Int, tono: Int): View {
        val p = pct.coerceIn(0, 100)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C_TRACK, 3)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (p > 0) addView(View(this@FinanzasActivity).apply { background = rounded(tono, 3) }, LinearLayout.LayoutParams(0, dp(5), p.toFloat()))
            addView(View(this@FinanzasActivity), LinearLayout.LayoutParams(0, dp(5), (100 - p).toFloat().coerceAtLeast(0.01f)))
        }
    }

    private fun conmutador(texto: String, onClick: () -> Unit) = label(texto, 15f, C_MUTED, medium = true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun chip(texto: String, activo: Boolean, relleno: Boolean = false, onClick: () -> Unit) =
        label(texto, 14f, if (activo) (if (relleno) 0xFFFFFFFF.toInt() else C_TEXT) else C_MUTED, medium = true).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ripple(
                rounded(if (activo && relleno) C_ACCENT else if (activo) C_SURFACE_2 else C_BG, 22, if (activo) C_ACCENT else C_BORDER),
                22
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun campoDialogo(pista: String, valor: String, dinero: Boolean) = EditText(this).apply {
        hint = pista
        setHintTextColor(C_FAINT)
        setTextColor(C_TEXT)
        textSize = 16f
        setSingleLine(true)
        inputType = if (dinero) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background = rounded(C_SURFACE_2, 14, C_BORDER)
        minimumHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setText(valor)
    }

    private fun cajaDialogo(vararg vistas: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
        for (v in vistas) addView(v, lp(top = 8))
    }

    private fun avisar(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()

    private fun avisarLargo(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

    private fun marcarTitulo(v: TextView) {
        if (Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true
    }

    private fun avisoCard(texto: String): View = tarjeta().apply {
        addView(label(texto, 15f, C_TEXT).apply { setLineSpacing(0f, 1.25f) })
    }

    private fun tarjeta() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_SURFACE, 20, C_BORDER)
        setPadding(dp(16), dp(16), dp(16), dp(14))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun label(text: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(content: Drawable, radius: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(0xFFFFFFFF.toInt(), radius))

    private fun boton(texto: String, principal: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = texto
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = ripple(if (principal) rounded(C_ACCENT, 14) else rounded(C_SURFACE_2, 14, C_BORDER), 14)
        setTextColor(if (principal) 0xFFFFFFFF.toInt() else C_TEXT)
        setOnClickListener { onClick() }
    }

    // ───────────────────────── Gráficos (dibujados a mano, sin librerías) ─────────────────────────

    /** La dona de gastos por categoría: un arco por categoría, con un huequito entre uno y otro. */
    private class DonaView(context: Context) : View(context) {
        private val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val caja = RectF()
        private var colores = IntArray(0)
        private var partes = FloatArray(0)

        fun poner(colores: IntArray, partes: FloatArray) {
            this.colores = colores
            this.partes = partes
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val lado = minOf(width, height).toFloat()
            if (lado <= 0f || partes.isEmpty()) return
            val grosor = lado * 0.13f
            pincel.strokeWidth = grosor
            val margen = grosor / 2f + 1f
            val izquierda = (width - lado) / 2f
            val arriba = (height - lado) / 2f
            caja.set(izquierda + margen, arriba + margen, izquierda + lado - margen, arriba + lado - margen)
            val hueco = if (partes.size > 1) 2.5f else 0f
            var inicio = -90f
            for (i in partes.indices) {
                val barrido = partes[i] * 360f
                pincel.color = if (i < colores.size) colores[i] else Color.GRAY
                canvas.drawArc(caja, inicio + hueco / 2f, maxOf(0.5f, barrido - hueco), false, pincel)
                inicio += barrido
            }
        }
    }

    /** Las barras de gasto por día, semana o mes; la del periodo que estás viendo va en violeta. */
    private class BarrasView(context: Context) : View(context) {
        private val barra = Paint(Paint.ANTI_ALIAS_FLAG)
        private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val forma = RectF()
        private var etiquetas = arrayOf<String>()
        private var valores = LongArray(0)
        private var textos = arrayOf<String>()
        private var actual = -1

        fun poner(etiquetas: Array<String>, valores: LongArray, textos: Array<String>, actual: Int) {
            this.etiquetas = etiquetas
            this.valores = valores
            this.textos = textos
            this.actual = actual
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val n = valores.size
            if (n == 0 || width <= 0 || height <= 0) return
            val d = resources.displayMetrics.density
            letra.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
            val renglon = letra.textSize * 1.7f
            val base = height - renglon              // donde se apoyan las barras
            val techo = renglon                      // lugar para el número de arriba
            var maximo = 1L
            for (v in valores) if (v > maximo) maximo = v
            val paso = width.toFloat() / n
            val ancho = minOf(paso * 0.62f, 34f * d)
            val radio = 4f * d
            for (i in 0 until n) {
                val cx = paso * i + paso / 2f
                val alto = if (valores[i] > 0L) maxOf(3f * d, (base - techo) * (valores[i].toFloat() / maximo.toFloat())) else 0f
                val esActual = i == actual
                if (alto > 0f) {
                    barra.color = if (esActual) C_ACCENT else C_BARRA
                    forma.set(cx - ancho / 2f, base - alto, cx + ancho / 2f, base)
                    canvas.drawRoundRect(forma, radio, radio, barra)
                    // La base va recta: solo la punta de arriba es redondeada.
                    canvas.drawRect(cx - ancho / 2f, base - minOf(alto, radio), cx + ancho / 2f, base, barra)
                }
                letra.color = if (esActual) C_TEXT else C_FAINT
                letra.isFakeBoldText = esActual
                if (i < etiquetas.size) canvas.drawText(etiquetas[i], cx, height - renglon * 0.3f, letra)
                if (i < textos.size && textos[i].isNotEmpty()) canvas.drawText(textos[i], cx, base - alto - 5f * d, letra)
            }
        }
    }
}
