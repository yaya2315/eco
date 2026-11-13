package com.blackholecode.eco

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tus finanzas, en el teléfono. Arriba, la cuenta y su saldo, con las pestañas Gastos e Ingresos; en el
 * centro, la dona del periodo (día, semana, mes o año) y debajo cada categoría. El botón «+» abre la
 * pantalla para anotar ([FinanzasAnadirActivity]) y el menú lateral lleva a Movimientos, Gráficos,
 * Presupuestos, Metas de ahorro, Pagos de cada mes y Cuentas.
 * Los números se piden a la laptop (lo mismo que el apartado Finanzas del panel). Se construye en código.
 */
class FinanzasActivity : Activity() {

    companion object {
        private const val C_BG = Fin.C_BG
        private const val C_SURFACE = Fin.C_SURFACE
        private const val C_SURFACE_2 = Fin.C_SURFACE_2
        private const val C_BORDER = Fin.C_BORDER
        private const val C_TEXT = Fin.C_TEXT
        private const val C_MUTED = Fin.C_MUTED
        private const val C_FAINT = Fin.C_FAINT
        private const val C_ACCENT = Fin.C_ACCENT
        private const val C_ACCENT_SOFT = Fin.C_ACCENT_SOFT
        private const val C_WARN = Fin.C_WARN
        private const val C_REC = Fin.C_REC
        private const val C_LIVE = Fin.C_LIVE

        private const val MOVS_CORTOS = 30

        private const val INICIO = "inicio"
        private const val MOVIMIENTOS = "movimientos"
        private const val GRAFICOS = "graficos"
        private const val PRESUPUESTOS = "presupuestos"
        private const val METAS = "metas"
        private const val FIJOS = "fijos"
        private const val CUENTAS = "cuentas"

        /** Las secciones del menú lateral, en orden. */
        private val SECCIONES = listOf(
            INICIO to "Inicio", MOVIMIENTOS to "Movimientos", GRAFICOS to "Gráficos", PRESUPUESTOS to "Presupuestos",
            METAS to "Metas de ahorro", FIJOS to "Pagos de cada mes", CUENTAS to "Cuentas",
        )
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
    }

    private lateinit var prefs: Prefs
    private lateinit var local: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var cabecera: LinearLayout     // cuenta, saldo y pestañas Gastos / Ingresos
    private lateinit var cuerpo: LinearLayout       // la sección que estás viendo
    private lateinit var mas: TextView              // el botón «+»
    private lateinit var velo: View                 // oscurece la pantalla detrás del menú
    private lateinit var menu: LinearLayout         // el menú lateral

    private var datos: JSONObject? = null
    private var categorias: JSONObject? = null
    private var cuentas: JSONArray = JSONArray()
    private var moneda = "$"
    private var cuenta = 0L
    private var periodo = "mes"
    private var ref: String? = null              // un día del periodo que estás viendo (null = hoy)
    private var pestana = "gasto"                // Gastos o Ingresos
    private var seccion = INICIO
    private var verTodos = false
    private var menuAbierto = false
    private var actualizado = ""                 // hora de la última vez que respondió la laptop
    private var carga = 0
    private var cargado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        local = getSharedPreferences(Fin.LOCAL, Context.MODE_PRIVATE)
        cuenta = local.getLong("cuenta", 0L)
        periodo = local.getString("periodo", "mes") ?: "mes"
        if (PERIODOS.none { it.first == periodo }) periodo = "mes"
        moneda = local.getString("moneda", "$") ?: "$"
        categorias = try { JSONObject(local.getString("categorias", "") ?: "") } catch (_: Exception) { null }
        cuentas = try { JSONArray(local.getString("cuentas", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        setContentView(construir())
        pintar()
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

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            menuAbierto -> cerrarMenu()
            seccion != INICIO -> irA(INICIO)
            else -> {
                super.onBackPressed()
                overridePendingTransition(0, 0)
            }
        }
    }

    // ───────────────────────── Estructura fija ─────────────────────────

    private fun construir(): View {
        val raiz = FrameLayout(this).apply { setBackgroundColor(C_BG) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(104))   // abajo queda lugar para el botón «+»
        }
        val frame = FrameLayout(this)
        val wide = resources.configuration.screenWidthDp > 600
        frame.addView(
            col,
            FrameLayout.LayoutParams(if (wide) dp(560) else MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
        )
        scroll.addView(frame, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        raiz.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Igual que en el panel de la laptop: arriba eliges Eco o Finanzas.
        col.addView(Fin.selector(this, enFinanzas = true) { salir() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF241F4D.toInt(), C_SURFACE)
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), C_BORDER)
            }
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        col.addView(cabecera, lp(top = 16))

        cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(cuerpo, lp(top = 12))

        // El botón «+»: anotar un gasto o un ingreso.
        mas = label("+", 32f, Fin.BLANCO).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = Fin.ripple(this@FinanzasActivity, GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_ACCENT)
            }, 32)
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { abrirAnadir() }
        }
        raiz.addView(mas, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20)
            bottomMargin = dp(24)
        })

        // Menú lateral con su velo
        velo = View(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
            alpha = 0f
            contentDescription = "Cerrar el menú"
            setOnClickListener { cerrarMenu() }
        }
        raiz.addView(velo, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_SURFACE)
            setPadding(0, dp(40), 0, dp(24))
            elevation = dp(16).toFloat()
            visibility = View.GONE
            isClickable = true   // que los toques no pasen a lo de atrás
        }
        raiz.addView(menu, FrameLayout.LayoutParams(dp(296), MATCH_PARENT, Gravity.START))
        return raiz
    }

    /** Volver a Eco sin animación, para que se sienta como cambiar de pestaña. */
    @Suppress("DEPRECATION")
    private fun salir() {
        finish()
        overridePendingTransition(0, 0)
    }

    private fun abrirAnadir() {
        if (prefs.server.isBlank()) {
            avisar("Primero conecta tu laptop: en Eco, abre la sección «Tu laptop».")
            return
        }
        startActivity(Intent(this, FinanzasAnadirActivity::class.java).putExtra(FinanzasAnadirActivity.EXTRA_TIPO, pestana))
    }

    private fun irA(destino: String) {
        seccion = destino
        verTodos = false
        if (menuAbierto) cerrarMenu()
        pintar()
    }

    // ───────────────────────── Menú lateral ─────────────────────────

    private fun abrirMenu() {
        pintarMenu()
        menuAbierto = true
        velo.visibility = View.VISIBLE
        menu.visibility = View.VISIBLE
        menu.translationX = -dp(296).toFloat()
        menu.animate().translationX(0f).setDuration(180).start()
        velo.animate().alpha(1f).setDuration(180).start()
    }

    private fun cerrarMenu() {
        menuAbierto = false
        menu.animate().translationX(-dp(296).toFloat()).setDuration(160).withEndAction {
            if (!menuAbierto) menu.visibility = View.GONE
        }.start()
        velo.animate().alpha(0f).setDuration(160).withEndAction {
            if (!menuAbierto) velo.visibility = View.GONE
        }.start()
    }

    private fun pintarMenu() {
        menu.removeAllViews()
        val cabeza = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        cabeza.addView(label("FINANZAS", 11f, C_ACCENT_SOFT, medium = true).apply { letterSpacing = 0.18f })
        cabeza.addView(label(nombreCuenta(), 22f, C_TEXT, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(top = 4))
        val saldo = datos?.optLong("saldo")
        if (saldo != null) cabeza.addView(label("Saldo: ${dinero(saldo)}", 15f, C_MUTED), lp(top = 2))
        menu.addView(cabeza)
        menu.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))

        for ((clave, nombre) in SECCIONES) {
            val activa = clave == seccion
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                background = Fin.ripple(this@FinanzasActivity, rounded(if (activa) C_SURFACE_2 else C_SURFACE, 0), 0)
                isClickable = true
                isFocusable = true
                contentDescription = nombre + if (activa) ", estás aquí" else ""
                setOnClickListener { irA(clave) }
            }
            fila.addView(View(this).apply { setBackgroundColor(if (activa) C_ACCENT else 0) }, LinearLayout.LayoutParams(dp(4), dp(28)))
            fila.addView(
                label(nombre, 16f, if (activa) C_TEXT else C_MUTED, medium = activa),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(20) }
            )
            menu.addView(fila, lp(top = if (clave == INICIO) 8 else 0))
        }
        menu.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(8) })
        menu.addView(label("Volver a Eco", 16f, C_ACCENT_SOFT, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(24), 0, dp(24), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { salir() }
        }, lp(top = 4))

        menu.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val enCola = Fin.leerCola(local).length()
        val pie = when {
            enCola == 1 -> "1 movimiento espera conexión con tu laptop"
            enCola > 1 -> "$enCola movimientos esperan conexión con tu laptop"
            actualizado.isNotEmpty() -> "Actualizado a las $actualizado"
            else -> "Sin conexión con tu laptop"
        }
        menu.addView(label(pie, 13f, if (enCola > 0) C_WARN else C_FAINT).apply {
            setPadding(dp(24), 0, dp(24), 0)
            setLineSpacing(0f, 1.2f)
        })
    }

    // ───────────────────────── Cargar ─────────────────────────

    private fun cargar(silencioso: Boolean = false) {
        val mia = ++carga
        if (prefs.server.isBlank()) {
            pintarError("Primero conecta tu laptop: en Eco, abre la sección «Tu laptop».")
            return
        }
        if (!silencioso) {
            cuerpo.removeAllViews()
            cuerpo.addView(label("Buscando en tu laptop…", 15f, C_MUTED), lp(top = 20))
        }
        val c = cuenta
        val p = periodo
        val r = ref
        io.execute {
            Fin.subirCola(local, prefs.server, prefs.token)
            val resp = Net.finanzas(prefs.server, prefs.token, c, p, r)
            ui.post {
                if (mia != carga || isFinishing) return@post
                val j = resp?.json
                if (j == null || !j.optBoolean("ok", false)) {
                    val texto = when {
                        resp == null -> "No encuentro tu laptop. Puedes anotar con el botón «+»: se guarda aquí y se sube cuando vuelva la conexión."
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
                val ahora = Calendar.getInstance()
                actualizado = String.format(Locale.US, "%d:%02d", ahora.get(Calendar.HOUR_OF_DAY), ahora.get(Calendar.MINUTE))
                local.edit()
                    .putLong("cuenta", cuenta)
                    .putString("periodo", periodo)
                    .putString("moneda", moneda)
                    .putString("categorias", categorias?.toString() ?: "")
                    .putString("cuentas", cuentas.toString())
                    .apply()
                pintar()
            }
        }
    }

    private fun pintarError(texto: String) {
        pintarCabecera()
        cuerpo.removeAllViews()
        val enCola = Fin.leerCola(local).length()
        if (enCola > 0) {
            cuerpo.addView(
                avisoCard(if (enCola == 1) "1 movimiento guardado aquí: se sube solo cuando tu laptop esté al alcance."
                else "$enCola movimientos guardados aquí: se suben solos cuando tu laptop esté al alcance."),
                lp(top = 8)
            )
        }
        cuerpo.addView(avisoCard(texto), lp(top = 8))
        cuerpo.addView(boton("Reintentar") { cargar() }, lp(top = 12))
    }

    // ───────────────────────── Pintar ─────────────────────────

    private fun pintar() {
        pintarCabecera()
        mas.visibility = if (seccion == INICIO || seccion == MOVIMIENTOS) View.VISIBLE else View.GONE
        mas.contentDescription = if (pestana == "ingreso") "Añadir un ingreso" else "Añadir un gasto"
        cuerpo.removeAllViews()
        val d = datos
        if (d == null) {
            if (cargado || prefs.server.isBlank()) return
            cuerpo.addView(label("Buscando en tu laptop…", 15f, C_MUTED), lp(top = 20))
            return
        }
        if (seccion != INICIO) cuerpo.addView(tituloSeccion())
        when (seccion) {
            MOVIMIENTOS -> {
                cuerpo.addView(tarjetaPeriodo(d), lp(top = 8))
                pintarMovimientos(d)
            }
            GRAFICOS -> {
                cuerpo.addView(tarjetaPeriodo(d), lp(top = 8))
                pintarNumeros(d)
                pintarBarras(d)
                pintarConsejo(d)
            }
            PRESUPUESTOS -> pintarPresupuestos(d)
            METAS -> pintarMetas(d)
            FIJOS -> pintarFijos(d)
            CUENTAS -> pintarCuentas()
            else -> pintarInicio(d)
        }
    }

    /** Arriba: menú, la cuenta con su saldo, la lista de movimientos y las pestañas Gastos / Ingresos. */
    private fun pintarCabecera() {
        cabecera.removeAllViews()
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(iconoLineas(conPuntos = false, descripcion = "Abrir el menú de Finanzas") { abrirMenu() })

        val centro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Fin.ripple(this@FinanzasActivity, rounded(0, 14), 14)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { elegirCuenta() }
        }
        val saldo = datos?.optLong("saldo")
        centro.addView(label(nombreCuenta() + "  ▾", 16f, C_MUTED, medium = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        centro.addView(label(if (saldo == null) "—" else dinero(saldo), 30f, if (saldo != null && saldo < 0) C_REC else C_TEXT, medium = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        })
        centro.contentDescription = "Cuenta ${nombreCuenta()}" + (if (saldo != null) ", saldo ${dinero(saldo)}" else "") + ". Toca para cambiar de cuenta."
        fila.addView(centro, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        fila.addView(iconoLineas(conPuntos = true, descripcion = "Ver los movimientos") { irA(MOVIMIENTOS) })
        cabecera.addView(fila)

        // Gastos | Ingresos
        val pestanas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((clave, nombre) in listOf("gasto" to "GASTOS", "ingreso" to "INGRESOS")) {
            val activa = clave == pestana
            val caja = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                contentDescription = nombre.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() } + if (activa) ", seleccionado" else ""
                setOnClickListener {
                    if (pestana != clave) {
                        pestana = clave
                        verTodos = false
                        if (seccion != INICIO && seccion != MOVIMIENTOS) seccion = INICIO
                        pintar()
                    }
                }
            }
            caja.addView(label(nombre, 14f, if (activa) C_TEXT else C_FAINT, medium = true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
                minimumHeight = dp(44)
            })
            caja.addView(
                View(this).apply { background = rounded(if (activa) C_ACCENT_SOFT else 0, 2) },
                LinearLayout.LayoutParams(dp(72), dp(3))
            )
            pestanas.addView(caja, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        cabecera.addView(pestanas, lp(top = 4))
    }

    /** Un ícono hecho con rayitas: el menú (tres rayas) o la lista de movimientos (punto y raya). */
    private fun iconoLineas(conPuntos: Boolean, descripcion: String, onClick: () -> Unit): View {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Fin.ripple(this@FinanzasActivity, rounded(0, 24), 24)
            isClickable = true
            isFocusable = true
            contentDescription = descripcion
            setOnClickListener { onClick() }
        }
        for (i in 0 until 3) {
            val raya = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (conPuntos) {
                raya.addView(View(this).apply { background = rounded(C_TEXT, 2) }, LinearLayout.LayoutParams(dp(3), dp(3)))
            }
            raya.addView(
                View(this).apply { background = rounded(C_TEXT, 1) },
                LinearLayout.LayoutParams(dp(if (conPuntos) 14 else 20), dp(2)).apply { marginStart = if (conPuntos) dp(3) else 0 }
            )
            caja.addView(raya, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(4) })
        }
        return FrameLayout(this).apply {
            addView(caja, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }
    }

    private fun nombreCuenta(): String {
        for (i in 0 until cuentas.length()) {
            val c = cuentas.optJSONObject(i) ?: continue
            if (c.optLong("id") == cuenta) return c.optString("nombre")
        }
        return cuentas.optJSONObject(0)?.optString("nombre") ?: "Finanzas"
    }

    private fun elegirCuenta() {
        if (cuentas.length() == 0) return
        val nombres = ArrayList<String>()
        val ids = ArrayList<Long>()
        for (i in 0 until cuentas.length()) {
            val c = cuentas.optJSONObject(i) ?: continue
            ids.add(c.optLong("id"))
            nombres.add(c.optString("nombre") + if (c.optLong("id") == cuenta) "  ✓" else "")
        }
        nombres.add("Administrar cuentas…")
        AlertDialog.Builder(this)
            .setTitle("¿Qué cuenta quieres ver?")
            .setItems(nombres.toTypedArray()) { _, i ->
                if (i >= ids.size) irA(CUENTAS) else cambiarCuenta(ids[i])
            }
            .show()
    }

    private fun cambiarCuenta(id: Long) {
        if (id == cuenta) return
        cuenta = id
        verTodos = false
        datos = null
        cargado = false
        local.edit().putLong("cuenta", id).apply()
        pintarCabecera()
        cargar()
    }

    /** En las secciones del menú: «‹ Inicio» y el nombre de la sección. */
    private fun tituloSeccion(): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(label("‹  Inicio", 15f, C_ACCENT_SOFT, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(4), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Volver al inicio de Finanzas"
            setOnClickListener { irA(INICIO) }
        })
        val nombre = SECCIONES.firstOrNull { it.first == seccion }?.second ?: ""
        fila.addView(label(nombre, 20f, C_TEXT, medium = true).apply {
            gravity = Gravity.END
            marcarTitulo(this)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return fila
    }

    /** Día, semana, mes o año, y las flechas para ir a otro periodo. Se añade dentro de [tarjeta]. */
    private fun ponerPeriodo(tarjeta: LinearLayout, d: JSONObject) {
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((clave, nombre) in PERIODOS) {
            val activo = clave == periodo
            val caja = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = "Ver por $nombre" + if (activo) ", seleccionado" else ""
                setOnClickListener {
                    if (periodo != clave) {
                        periodo = clave
                        ref = null
                        verTodos = false
                        cargar(silencioso = true)
                    }
                }
            }
            caja.addView(label(nombre, 15f, if (activo) C_ACCENT_SOFT else C_MUTED, medium = activo).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
            })
            caja.addView(
                View(this).apply { background = rounded(if (activo) C_ACCENT_SOFT else 0, 2) },
                LinearLayout.LayoutParams(dp(44), dp(3))
            )
            tabs.addView(caja, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        tarjeta.addView(tabs)

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
            cargar(silencioso = true)
        })
        val titulo = if (p.optString("tipo") == "dia" && p.optString("ref") == hoy) "Hoy" else p.optString("titulo")
        nav.addView(label(titulo, 16f, C_TEXT, medium = true).apply {
            gravity = Gravity.CENTER
            marcarTitulo(this)
            if (!esActual) {
                isClickable = true
                isFocusable = true
                contentDescription = "$titulo. Toca para volver a hoy."
                setOnClickListener {
                    ref = null
                    verTodos = false
                    cargar(silencioso = true)
                }
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nav.addView(flecha("›", "Periodo siguiente", !esActual) {
            ref = p.optString("siguiente")
            verTodos = false
            cargar(silencioso = true)
        })
        tarjeta.addView(nav, lp(top = 8))
        if (!esActual) {
            tarjeta.addView(label("Toca la fecha para volver a hoy", 12f, C_FAINT).apply { gravity = Gravity.CENTER })
        }
    }

    private fun tarjetaPeriodo(d: JSONObject): View {
        val t = tarjeta()
        ponerPeriodo(t, d)
        return t
    }

    private fun flecha(simbolo: String, descripcion: String, activa: Boolean, onClick: () -> Unit) =
        label(simbolo, 26f, if (activa) C_TEXT else C_BORDER).apply {
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            contentDescription = descripcion
            if (activa) {
                background = Fin.ripple(this@FinanzasActivity, rounded(0, 24), 24)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            } else {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }

    // ───────────────────────── Inicio: la dona y sus categorías ─────────────────────────

    /** Lo gastado (o lo que entró) por categoría: lo manda la laptop; si es una versión vieja, se suma aquí. */
    private fun porCategoria(d: JSONObject): JSONArray {
        if (pestana == "gasto") return d.optJSONArray("por_categoria") ?: JSONArray()
        d.optJSONArray("por_categoria_ingreso")?.let { return it }
        val movs = d.optJSONArray("movimientos") ?: JSONArray()
        val sumas = LinkedHashMap<String, JSONObject>()
        var total = 0L
        for (i in 0 until movs.length()) {
            val m = movs.optJSONObject(i) ?: continue
            if (m.optString("tipo") != "ingreso") continue
            val clave = m.optString("categoria")
            val suma = sumas.getOrPut(clave) {
                JSONObject().put("clave", clave).put("nombre", m.optString("nombre")).put("color", m.optString("color")).put("total", 0L)
            }
            suma.put("total", suma.optLong("total") + m.optLong("monto"))
            total += m.optLong("monto")
        }
        val salida = JSONArray()
        for (x in sumas.values.sortedByDescending { it.optLong("total") }) {
            x.put("pct", if (total > 0) (x.optLong("total") * 100.0 / total).roundToInt() else 0)
            salida.put(x)
        }
        return salida
    }

    private fun pintarInicio(d: JSONObject) {
        val esGasto = pestana == "gasto"
        val tarjeta = tarjeta()
        ponerPeriodo(tarjeta, d)

        val cats = porCategoria(d)
        val total = d.optJSONObject("totales")?.optLong(if (esGasto) "gasto" else "ingreso") ?: 0L
        val vacio = total <= 0L || cats.length() == 0

        // La dona: hasta 6 categorías; el resto, juntas en «Otras».
        val colores = ArrayList<Int>()
        val montos = ArrayList<Long>()
        var resto = 0L
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            if (i < 6 || cats.length() == 7) {
                colores.add(Fin.color(c.optString("color")))
                montos.add(c.optLong("total"))
            } else {
                resto += c.optLong("total")
            }
        }
        if (resto > 0L) {
            colores.add(Fin.C_OTRAS)
            montos.add(resto)
        }
        val descripcion = StringBuilder(if (esGasto) "Gastos por categoría: " else "Ingresos por categoría: ")
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            if (i > 0) descripcion.append(", ")
            descripcion.append(c.optString("nombre")).append(" ").append(c.optInt("pct")).append(" %")
        }
        val grafico = FrameLayout(this)
        grafico.addView(DonaView(this).apply {
            if (vacio) poner(intArrayOf(Fin.C_TRACK), floatArrayOf(1f))
            else poner(colores.toIntArray(), FloatArray(montos.size) { montos[it].toFloat() / total.toFloat() })
            contentDescription = if (vacio) (if (esGasto) "Sin gastos ${DE[periodo]}" else "Sin ingresos ${DE[periodo]}") else descripcion.toString()
        }, FrameLayout.LayoutParams(dp(224), dp(224), Gravity.CENTER))
        val centro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        if (vacio) {
            centro.addView(label(if (esGasto) "Sin gastos" else "Sin ingresos", 17f, C_TEXT, medium = true).apply { gravity = Gravity.CENTER })
            centro.addView(label(DE[periodo] ?: "", 13f, C_FAINT).apply { gravity = Gravity.CENTER })
        } else {
            centro.addView(label(dinero(total), 22f, C_TEXT, medium = true).apply { gravity = Gravity.CENTER })
            centro.addView(label(if (esGasto) "gastado" else "entró", 13f, C_FAINT).apply { gravity = Gravity.CENTER })
        }
        grafico.addView(centro, FrameLayout.LayoutParams(dp(132), WRAP_CONTENT, Gravity.CENTER))
        tarjeta.addView(grafico, LinearLayout.LayoutParams(MATCH_PARENT, dp(224)).apply { topMargin = dp(12) })

        if (vacio) {
            tarjeta.addView(
                label("Toca «+» para anotar ${if (esGasto) "un gasto" else "un ingreso"}.", 14f, C_MUTED).apply { gravity = Gravity.CENTER },
                lp(top = 12)
            )
        } else if (esGasto) {
            // Cómo vas contra el periodo anterior
            val antes = d.optJSONObject("antes")?.optLong("gasto") ?: 0L
            if (antes > 0L) {
                val pct = ((total - antes) * 100.0 / antes).roundToInt()
                val texto = if (pct == 0) "Igual que ${ANTES[periodo]}" else "${abs(pct)} % ${if (pct < 0) "menos" else "más"} que ${ANTES[periodo]}"
                tarjeta.addView(label(texto, 13f, if (pct < 0) C_LIVE else C_FAINT).apply { gravity = Gravity.CENTER }, lp(top = 10))
            }
        }
        cuerpo.addView(tarjeta)

        // Cada categoría, con su ícono, su parte y su monto
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            val nombre = c.optString("nombre")
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(C_SURFACE, 16, C_BORDER)
                setPadding(dp(12), dp(10), dp(14), dp(10))
                isFocusable = true
                contentDescription = "$nombre: ${dinero(c.optLong("total"))}, ${c.optInt("pct")} por ciento"
            }
            fila.addView(Fin.circulo(this, c.optString("clave"), Fin.color(c.optString("color")), 40), LinearLayout.LayoutParams(dp(40), dp(40)))
            fila.addView(label(nombre, 15f, C_TEXT, medium = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            fila.addView(label("${c.optInt("pct")} %", 13f, C_FAINT), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
            fila.addView(
                label(dinero(c.optLong("total")), 15f, C_TEXT, medium = true).apply {
                    gravity = Gravity.END
                    minimumWidth = dp(84)
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
            )
            cuerpo.addView(fila, lp(top = 8))
        }

        if (esGasto) pintarConsejo(d)
        val enCola = Fin.leerCola(local).length()
        if (enCola > 0) {
            cuerpo.addView(
                label(if (enCola == 1) "1 movimiento guardado aquí espera conexión con tu laptop."
                else "$enCola movimientos guardados aquí esperan conexión con tu laptop.", 13f, C_WARN).apply { setLineSpacing(0f, 1.2f) },
                lp(top = 12)
            )
        }
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
        cuerpo.addView(tarjeta, lp(top = 16))
    }

    // ───────────────────────── Gráficos ─────────────────────────

    private fun pintarNumeros(d: JSONObject) {
        val t = d.optJSONObject("totales") ?: JSONObject()
        val ingreso = t.optLong("ingreso")
        val gasto = t.optLong("gasto")
        val ahorro = t.optLong("ahorro")
        val queda = t.optLong("queda")
        val de = DE[periodo] ?: ""
        val fila1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila1.addView(numero("INGRESOS", dinero(ingreso), "lo que entró $de", C_TEXT), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        fila1.addView(
            numero("GASTOS", dinero(gasto), "lo que salió $de", C_TEXT),
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(8) }
        )
        cuerpo.addView(fila1, lp(top = 8))
        val fila2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila2.addView(
            numero("AHORRADO", dinero(ahorro), if (ingreso > 0 && ahorro > 0) "${ahorro * 100 / ingreso} % de lo que entró" else "para tus metas", C_TEXT),
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
        )
        fila2.addView(
            numero(
                if (queda < 0) "TE PASASTE" else "TE QUEDA", dinero(abs(queda)),
                "ingresos menos gastos y ahorro", if (queda < 0) C_REC else C_ACCENT_SOFT, destacado = true
            ),
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(8) }
        )
        cuerpo.addView(fila2, lp(top = 8))
    }

    private fun numero(titulo: String, valor: String, pie: String, color: Int, destacado: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_SURFACE, 16, if (destacado) color else C_BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isFocusable = true
            contentDescription = "$titulo: $valor. $pie"
            addView(label(titulo, 11f, C_MUTED, medium = true).apply { letterSpacing = 0.1f })
            addView(label(valor, 21f, color, medium = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(top = 2))
            addView(label(pie, 12f, C_FAINT).apply { setLineSpacing(0f, 1.15f) }, lp(top = 2))
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
            if (valores[i] > 0 && (n <= 8 || i == actual || valores[i] == maximo)) textos[i] = Fin.dineroCorto(valores[i])
            if (i > 0) descripcion.append(", ")
            descripcion.append(serie.optJSONObject(i)?.optString("etiqueta") ?: "").append(": ").append(dinero(valores[i]))
        }
        tarjeta.addView(BarrasView(this).apply {
            poner(etiquetas, valores, textos, actual)
            contentDescription = descripcion.toString()
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(180)).apply { topMargin = dp(8) })
        tarjeta.addView(
            label("En total: entró ${dinero(entro)} y salió ${dinero(salio)}.", 13f, C_FAINT).apply { setLineSpacing(0f, 1.2f) },
            lp(top = 8)
        )
        cuerpo.addView(tarjeta, lp(top = 16))
    }

    // ───────────────────────── Presupuestos, metas y pagos fijos ─────────────────────────

    private fun pintarPresupuestos(d: JSONObject) {
        val lista = d.optJSONArray("presupuestos") ?: JSONArray()
        cuerpo.addView(
            label("Un tope por mes para una categoría. Eco te avisa al llegar al 80 % y si te pasas.", 14f, C_MUTED)
                .apply { setLineSpacing(0f, 1.25f) },
            lp(top = 4)
        )
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
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Fin.ripple(this@FinanzasActivity, rounded(C_SURFACE, 16, C_BORDER), 16)
                setPadding(dp(12), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = "$nombre: ${dinero(gastado)} de ${dinero(limite)}, $pie. Toca para cambiar el tope."
                setOnClickListener { pedirTope(p.optString("categoria"), nombre, limite) }
            }
            fila.addView(Fin.circulo(this, p.optString("categoria"), Fin.color(p.optString("color")), 40), LinearLayout.LayoutParams(dp(40), dp(40)))
            val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textos.addView(filaDoble(nombre, "${dinero(gastado)} / ${dinero(limite)}"))
            textos.addView(progreso(pct, tono), lp(top = 6))
            textos.addView(label(pie, 12f, if (resta < 0) C_REC else if (pct >= 80) C_WARN else C_FAINT), lp(top = 4))
            fila.addView(textos, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            cuerpo.addView(fila, lp(top = 8))
        }
        cuerpo.addView(boton("+ Presupuesto", principal = true) { nuevoPresupuesto() }, lp(top = 16))
    }

    private fun pintarMetas(d: JSONObject) {
        val lista = d.optJSONArray("metas") ?: JSONArray()
        cuerpo.addView(
            label("Dime para qué quieres ahorrar y cuánto. Eco calcula cuánto apartar cada semana.", 14f, C_MUTED)
                .apply { setLineSpacing(0f, 1.25f) },
            lp(top = 4)
        )
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
            val tarjeta = tarjeta()
            tarjeta.isFocusable = true
            tarjeta.contentDescription = "$nombre: ${dinero(m.optLong("ahorrado"))} de ${dinero(m.optLong("objetivo"))}, $pie"
            tarjeta.addView(filaDoble(nombre, "${dinero(m.optLong("ahorrado"))} / ${dinero(m.optLong("objetivo"))}"))
            tarjeta.addView(progreso(m.optInt("pct"), if (falta <= 0L) C_LIVE else C_ACCENT_SOFT), lp(top = 8))
            tarjeta.addView(label(pie, 13f, if (falta <= 0L) C_LIVE else C_FAINT), lp(top = 6))
            if (falta > 0L) tarjeta.addView(boton("Apartar dinero") { apartar(id, nombre, falta, porSemana) }, lp(top = 10))
            cuerpo.addView(tarjeta, lp(top = 8))
        }
        cuerpo.addView(boton("+ Meta", principal = true) { nuevaMeta() }, lp(top = 16))
    }

    private fun pintarFijos(d: JSONObject) {
        val lista = d.optJSONArray("fijos") ?: JSONArray()
        cuerpo.addView(
            label("Internet, colegiatura, suscripciones… Eco te avisa un día antes y los anota con un toque.", 14f, C_MUTED)
                .apply { setLineSpacing(0f, 1.25f) },
            lp(top = 4)
        )
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
            val tarjeta = tarjeta()
            tarjeta.isFocusable = true
            tarjeta.contentDescription = "$nota, ${dinero(f.optLong("monto"))}, día ${f.optInt("dia")}, $cuando"
            tarjeta.addView(filaDoble(nota, dinero(f.optLong("monto"), signo = esIngreso)))
            tarjeta.addView(
                label("día ${f.optInt("dia")} · ${f.optString("nombre")} · $cuando", 13f, if (listo) C_LIVE else if (enDias <= 0) C_WARN else C_FAINT),
                lp(top = 4)
            )
            if (!listo) {
                tarjeta.addView(boton(if (esIngreso) "Ya me pagaron" else "Ya lo pagué") {
                    cambiar("fijo", JSONObject().put("id", id).put("registrar", true).put("origen", "app"), "Anotado en tus finanzas")
                }, lp(top = 10))
            }
            cuerpo.addView(tarjeta, lp(top = 8))
        }
        cuerpo.addView(boton("+ Pago fijo", principal = true) { nuevoFijo() }, lp(top = 16))
    }

    // ───────────────────────── Movimientos ─────────────────────────

    private fun pintarMovimientos(d: JSONObject) {
        val todos = d.optJSONArray("movimientos") ?: JSONArray()
        val hoy = d.optString("hoy")
        // En la pestaña Gastos se ven los gastos y lo apartado; en Ingresos, los ingresos.
        val lista = ArrayList<JSONObject>()
        for (i in 0 until todos.length()) {
            val m = todos.optJSONObject(i) ?: continue
            val esIngreso = m.optString("tipo") == "ingreso"
            if (esIngreso == (pestana == "ingreso")) lista.add(m)
        }
        if (lista.isEmpty()) {
            cuerpo.addView(
                avisoCard("Nada anotado ${DE[periodo]}. Toca «+», o escríbele a Eco por Telegram: «gasté 3.50 en almuerzo»."),
                lp(top = 8)
            )
            return
        }
        val limite = if (verTodos) lista.size else minOf(lista.size, MOVS_CORTOS)
        for (i in 0 until limite) cuerpo.addView(filaMovimiento(lista[i], hoy), lp(top = 8))
        if (limite < lista.size) {
            cuerpo.addView(boton("Ver los ${lista.size} movimientos") {
                verTodos = true
                pintar()
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
        val detalle = listOf(Fin.fechaCorta(m.optString("fecha"), hoy), nota, de).filter { it.isNotEmpty() }.joinToString(" · ")
        val texto = when (tipoMov) {
            "ingreso" -> dinero(monto, signo = true)
            "gasto" -> dinero(-monto)
            else -> dinero(monto)
        }
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Fin.ripple(this@FinanzasActivity, rounded(C_SURFACE, 16, C_BORDER), 16)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            minimumHeight = dp(60)
            isClickable = true
            isFocusable = true
            contentDescription = "$nombre, $texto, $detalle. Toca para borrar."
        }
        fila.addView(Fin.circulo(this, m.optString("categoria"), Fin.color(m.optString("color")), 40), LinearLayout.LayoutParams(dp(40), dp(40)))
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

    // ───────────────────────── Cuentas ─────────────────────────

    private fun pintarCuentas() {
        cuerpo.addView(
            label("Una cuenta solo separa lo que anotas (lo tuyo y lo de tu negocio). No se conecta a ningún banco.", 14f, C_MUTED)
                .apply { setLineSpacing(0f, 1.25f) },
            lp(top = 4)
        )
        for (i in 0 until cuentas.length()) {
            val c = cuentas.optJSONObject(i) ?: continue
            val id = c.optLong("id")
            val nombre = c.optString("nombre")
            val esEmpresa = c.optString("tipo") == "empresa"
            val actual = id == cuenta
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Fin.ripple(this@FinanzasActivity, rounded(C_SURFACE, 16, if (actual) C_ACCENT else C_BORDER), 16)
                setPadding(dp(16), dp(12), dp(8), dp(12))
                minimumHeight = dp(64)
                isClickable = true
                isFocusable = true
                contentDescription = "Cuenta $nombre" + (if (actual) ", la estás viendo" else ". Toca para verla.")
                setOnClickListener {
                    if (actual) irA(INICIO) else {
                        seccion = INICIO
                        cambiarCuenta(id)
                    }
                }
            }
            val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textos.addView(label(nombre, 16f, C_TEXT, medium = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            textos.addView(
                label((if (esEmpresa) "Empresa o negocio" else "Personal") + if (actual) " · la estás viendo" else "", 13f, if (actual) C_ACCENT_SOFT else C_FAINT),
                lp(top = 1)
            )
            fila.addView(textos, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            fila.addView(label("Editar", 14f, C_ACCENT_SOFT, medium = true).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Cambiar el nombre de la cuenta $nombre"
                setOnClickListener { editarCuenta(id, nombre) }
            })
            cuerpo.addView(fila, lp(top = 8))
        }
        cuerpo.addView(boton("+ Cuenta", principal = true) { editarCuenta(0L, "") }, lp(top = 16))
    }

    /** Crear una cuenta (id 0) o cambiarle el nombre a una que ya existe. */
    private fun editarCuenta(id: Long, nombreActual: String) {
        val nombre = campoDialogo("Nombre, ej. Black Hole Code", nombreActual, dinero = false)
        fun guardar(tipoCuenta: String) {
            val n = nombre.text.toString().trim()
            if (n.isEmpty()) {
                avisar("Ponle un nombre a la cuenta.")
                return
            }
            if (prefs.server.isBlank()) {
                avisar("Primero conecta tu laptop.")
                return
            }
            val d = JSONObject().put("nombre", n).put("tipo", tipoCuenta)
            if (id > 0L) d.put("id", id)
            io.execute {
                val r = Net.finanzasCambio(prefs.server, prefs.token, "cuenta", d)
                ui.post {
                    if (isFinishing) return@post
                    val nueva = r?.json?.optLong("cuenta", 0L) ?: 0L
                    if (r?.codigo == 200 && nueva > 0L) {
                        avisar(if (id > 0L) "Cuenta guardada" else "Cuenta creada")
                        if (id == 0L) {
                            cuenta = nueva
                            seccion = INICIO
                        }
                        cargar()
                    } else {
                        avisar(if (r == null) "No encuentro tu laptop." else "No se pudo guardar la cuenta.")
                    }
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (id > 0L) "Editar cuenta" else "Nueva cuenta")
            .setMessage("¿De qué es esta cuenta?")
            .setView(cajaDialogo(nombre))
            .setPositiveButton("Personal") { _, _ -> guardar("personal") }
            .setNeutralButton("Empresa") { _, _ -> guardar("empresa") }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ───────────────────────── Cambios: presupuestos, metas y pagos fijos ─────────────────────────

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
                        val aviso = r.json?.optJSONArray("avisos")?.optString(0) ?: ""
                        Toast.makeText(this, if (aviso.isBlank()) exito else aviso, Toast.LENGTH_LONG).show()
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
            nombres.add(Fin.icono(c.optString("clave")) + "  " + c.optString("nombre"))
        }
        AlertDialog.Builder(this)
            .setTitle("¿De qué categoría?")
            .setItems(nombres.toTypedArray()) { _, i ->
                pedirTope(claves[i], lista.optJSONObject(i)?.optString("nombre") ?: "", 0L)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirTope(clave: String, nombre: String, actual: Long) {
        val monto = campoDialogo("Tope por mes, ej. 60", if (actual > 0) Fin.plano(actual) else "", dinero = true)
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
        val chipLimite = label("+ Fecha límite (opcional)", 14f, C_ACCENT_SOFT, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            isClickable = true
            isFocusable = true
        }
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
        val monto = campoDialogo("¿Cuánto apartas? ej. 10", if (sugerido > 0L) Fin.plano(sugerido) else "", dinero = true)
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
                val elDia = dia.text.toString().trim().toIntOrNull()
                if (n.isEmpty() || v == null || v <= 0.0 || elDia == null || elDia < 1 || elDia > 31) {
                    avisar("Ponle nombre, monto y el día del mes (1 a 31).")
                } else {
                    cambiar(
                        "fijo",
                        JSONObject().put("cuenta", cuenta).put("nota", n).put("monto", texto).put("dia", elDia).put("tipo", "gasto"),
                        "Pago fijo guardado"
                    )
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ───────────────────────── Piezas ─────────────────────────

    private fun dinero(c: Long, signo: Boolean = false): String = Fin.dinero(c, moneda, signo)

    private fun sobre(texto: String) = label(texto, 11f, C_MUTED, medium = true).apply {
        letterSpacing = 0.12f
        marcarTitulo(this)
    }

    private fun filaDoble(izquierda: String, derecha: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(izquierda, 15f, C_TEXT, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label(derecha, 13f, C_MUTED), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    }

    private fun progreso(pct: Int, tono: Int): View {
        val p = pct.coerceIn(0, 100)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Fin.C_TRACK, 3)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (p > 0) addView(View(this@FinanzasActivity).apply { background = rounded(tono, 3) }, LinearLayout.LayoutParams(0, dp(6), p.toFloat()))
            addView(View(this@FinanzasActivity), LinearLayout.LayoutParams(0, dp(6), (100 - p).toFloat().coerceAtLeast(0.01f)))
        }
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

    private fun marcarTitulo(v: TextView) {
        if (Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true
    }

    private fun avisoCard(texto: String): View = tarjeta().apply {
        addView(label(texto, 15f, C_TEXT).apply { setLineSpacing(0f, 1.25f) })
    }

    private fun tarjeta() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_SURFACE, 20, C_BORDER)
        setPadding(dp(16), dp(12), dp(16), dp(16))
    }

    private fun dp(v: Int): Int = Fin.dp(this, v)

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun label(text: String, size: Float, color: Int, medium: Boolean = false) = Fin.label(this, text, size, color, medium)

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): Drawable = Fin.rounded(this, fill, radius, stroke)

    private fun boton(texto: String, principal: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = texto
        isAllCaps = false
        textSize = 15f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = Fin.ripple(this@FinanzasActivity, if (principal) rounded(C_ACCENT, 14) else rounded(C_SURFACE_2, 14, C_BORDER), 14)
        setTextColor(if (principal) Fin.BLANCO else C_TEXT)
        setOnClickListener { onClick() }
    }
}
