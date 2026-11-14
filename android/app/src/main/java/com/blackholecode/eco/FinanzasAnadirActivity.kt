package com.blackholecode.eco

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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

/**
 * Anotar un gasto o un ingreso: el monto en grande, la cuenta, la categoría (círculos con ícono),
 * la fecha (hoy, ayer u otro día) y un comentario. No espera a la laptop: el movimiento se guarda en el
 * teléfono al instante ([FinLocal]) y se sube solo, en segundo plano, cuando la laptop esté al alcance.
 */
class FinanzasAnadirActivity : Activity() {

    companion object {
        /** "gasto" o "ingreso": la pestaña con la que se abre. */
        const val EXTRA_TIPO = "tipo"

        private const val C_BG = Fin.C_BG
        private const val C_SURFACE = Fin.C_SURFACE
        private const val C_SURFACE_2 = Fin.C_SURFACE_2
        private const val C_BORDER = Fin.C_BORDER
        private const val C_TEXT = Fin.C_TEXT
        private const val C_MUTED = Fin.C_MUTED
        private const val C_FAINT = Fin.C_FAINT
        private const val C_ACCENT = Fin.C_ACCENT
        private const val C_ACCENT_SOFT = Fin.C_ACCENT_SOFT

        /** Cuántas categorías se ven antes de tocar «Más» (con «Más» son dos filas de tres). */
        private const val CORTAS = 5
    }

    private lateinit var local: SharedPreferences

    private lateinit var pestanas: LinearLayout
    private lateinit var campoMonto: EditText
    private lateinit var textoCuenta: TextView
    private lateinit var rejilla: LinearLayout
    private lateinit var fechas: LinearLayout
    private lateinit var campoNota: EditText
    private lateinit var botonAnadir: Button

    private var categorias: JSONObject? = null
    private var cuentas: JSONArray = JSONArray()
    private var moneda = "$"
    private var cuenta = 0L
    private var tipo = "gasto"
    private var categoria = ""
    private var todas = false                    // ver todas las categorías
    private var fecha = Fin.isoHace(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        local = getSharedPreferences(Fin.LOCAL, Context.MODE_PRIVATE)
        // Todo sale de lo guardado en el teléfono: funciona igual con la laptop apagada.
        cuenta = FinLocal.cuentaReal(this, local.getLong("cuenta", 0L))
        moneda = FinLocal.moneda(this, local)
        categorias = FinLocal.categorias(this, local, cuenta)
        cuentas = FinLocal.cuentas(this)
        if (intent.getStringExtra(EXTRA_TIPO) == "ingreso") tipo = "ingreso"
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(C_BG)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        val frame = FrameLayout(this)
        val wide = resources.configuration.screenWidthDp > 600
        frame.addView(
            col,
            FrameLayout.LayoutParams(if (wide) dp(560) else MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
        )
        scroll.addView(frame, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(scroll)
        construir(col)
        campoMonto.requestFocus()
    }

    // ───────────────────────── Estructura ─────────────────────────

    private fun construir(col: LinearLayout) {
        // Barra de arriba: volver y el título
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        barra.addView(label("←", 24f, C_TEXT).apply {
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            background = Fin.ripple(this@FinanzasAnadirActivity, rounded(0, 24), 24)
            isClickable = true
            isFocusable = true
            contentDescription = "Volver a Finanzas"
            setOnClickListener { finish() }
        })
        barra.addView(label("Añadir movimiento", 20f, C_TEXT, medium = true).apply { marcarTitulo(this) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        col.addView(barra)

        pestanas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(pestanas, lp(top = 8))

        // El monto, en grande
        val filaMonto = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        filaMonto.addView(label(moneda, 28f, C_ACCENT_SOFT, medium = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        campoMonto = EditText(this).apply {
            hint = "0"
            setHintTextColor(0xFF55556E.toInt())
            setTextColor(C_TEXT)
            textSize = 44f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            minimumWidth = dp(140)
            minimumHeight = dp(72)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = "Monto"
        }
        filaMonto.addView(campoMonto, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(filaMonto, lp(top = 12))
        col.addView(
            View(this).apply { background = rounded(C_BORDER, 1) },
            LinearLayout.LayoutParams(dp(200), dp(2)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        // Cuenta
        col.addView(sobre("CUENTA"), lp(top = 24))
        textoCuenta = label("", 17f, C_ACCENT_SOFT, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { elegirCuenta() }
        }
        col.addView(textoCuenta)

        // Categorías
        col.addView(sobre("CATEGORÍA"), lp(top = 12))
        rejilla = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rejilla, lp(top = 4))

        // Fecha
        col.addView(sobre("FECHA"), lp(top = 20))
        fechas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        col.addView(fechas, lp(top = 8))

        // Comentario
        col.addView(sobre("COMENTARIO"), lp(top = 20))
        campoNota = EditText(this).apply {
            hint = "Opcional: almuerzo con Steven"
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
                if (accion == EditorInfo.IME_ACTION_DONE) { anadir(); true } else false
            }
        }
        col.addView(campoNota, lp(top = 8))

        botonAnadir = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(56)
            minimumHeight = dp(56)
            stateListAnimator = null
            background = Fin.ripple(this@FinanzasAnadirActivity, rounded(C_ACCENT, 28), 28)
            setTextColor(Fin.BLANCO)
            setOnClickListener { anadir() }
        }
        col.addView(botonAnadir, lp(top = 24))

        pintarPestanas()
        pintarCuenta()
        pintarCategorias()
        pintarFechas()
    }

    // ───────────────────────── Pintar ─────────────────────────

    private fun pintarPestanas() {
        pestanas.removeAllViews()
        for ((clave, nombre) in listOf("gasto" to "GASTOS", "ingreso" to "INGRESOS")) {
            val activa = clave == tipo
            val caja = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = (if (clave == "gasto") "Gasto" else "Ingreso") + if (activa) ", seleccionado" else ""
                setOnClickListener {
                    if (tipo != clave) {
                        tipo = clave
                        todas = false
                        pintarPestanas()
                        pintarCategorias()
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
        botonAnadir.text = if (tipo == "ingreso") "Añadir ingreso" else "Añadir gasto"
    }

    private fun pintarCuenta() {
        var nombre = ""
        for (i in 0 until cuentas.length()) {
            val c = cuentas.optJSONObject(i) ?: continue
            if (c.optLong("id") == cuenta) nombre = c.optString("nombre")
        }
        if (nombre.isEmpty()) {
            val primera = cuentas.optJSONObject(0)
            if (primera != null) {
                cuenta = primera.optLong("id")
                nombre = primera.optString("nombre")
            }
        }
        textoCuenta.text = if (nombre.isEmpty()) "La de siempre" else "$nombre  ▾"
        textoCuenta.contentDescription = if (nombre.isEmpty()) "Cuenta: la de siempre" else "Cuenta $nombre. Toca para cambiarla."
    }

    private fun elegirCuenta() {
        if (cuentas.length() < 2) return
        val nombres = ArrayList<String>()
        val ids = ArrayList<Long>()
        for (i in 0 until cuentas.length()) {
            val c = cuentas.optJSONObject(i) ?: continue
            ids.add(c.optLong("id"))
            nombres.add(c.optString("nombre"))
        }
        AlertDialog.Builder(this)
            .setTitle("¿En qué cuenta lo anoto?")
            .setItems(nombres.toTypedArray()) { _, i ->
                cuenta = ids[i]
                categorias = FinLocal.categorias(this, local, cuenta)   // primero las que más usas en esa cuenta
                pintarCuenta()
                pintarCategorias()
            }
            .show()
    }

    /** Las categorías en filas de tres círculos; primero las que más usas y el resto detrás de «Más». */
    private fun pintarCategorias() {
        rejilla.removeAllViews()
        val lista = categorias?.optJSONArray(tipo)
        if (lista == null || lista.length() == 0) {
            rejilla.addView(label("No hay categorías para elegir.", 14f, C_FAINT).apply {
                setLineSpacing(0f, 1.25f)
            })
            return
        }
        val visibles = ArrayList<JSONObject>()
        var elegidaVisible = false
        for (i in 0 until lista.length()) {
            val c = lista.optJSONObject(i) ?: continue
            if (todas || visibles.size < CORTAS) {
                visibles.add(c)
                if (c.optString("clave") == categoria) elegidaVisible = true
            } else if (c.optString("clave") == categoria) {
                visibles[CORTAS - 1] = c   // la que elegiste siempre se ve
                elegidaVisible = true
            }
        }
        if (!elegidaVisible) categoria = visibles.firstOrNull()?.optString("clave") ?: ""
        val hayMas = lista.length() > CORTAS
        val celdas = ArrayList<View>()
        for (c in visibles) celdas.add(celdaCategoria(c))
        if (hayMas) celdas.add(celdaMas())
        var fila: LinearLayout? = null
        for ((i, celda) in celdas.withIndex()) {
            if (i % 3 == 0) {
                fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rejilla.addView(fila, lp(top = if (i == 0) 0 else 4))
            }
            fila?.addView(celda, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        // Completa la última fila para que los círculos queden alineados
        val sobran = (3 - celdas.size % 3) % 3
        for (i in 0 until sobran) fila?.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
    }

    private fun celdaCategoria(c: JSONObject): View {
        val clave = c.optString("clave")
        val nombre = c.optString("nombre")
        val activa = clave == categoria
        val celda = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = Fin.ripple(this@FinanzasAnadirActivity, rounded(if (activa) C_SURFACE_2 else 0, 16, if (activa) C_ACCENT_SOFT else null), 16)
            isClickable = true
            isFocusable = true
            contentDescription = "Categoría $nombre" + if (activa) ", seleccionada" else ""
            setOnClickListener {
                categoria = clave
                pintarCategorias()
            }
        }
        celda.addView(Fin.circulo(this, clave, Fin.color(c.optString("color")), 56), LinearLayout.LayoutParams(dp(56), dp(56)))
        celda.addView(label(nombre, 13f, if (activa) C_TEXT else C_MUTED, medium = activa).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        }, lp(top = 6))
        return celda
    }

    private fun celdaMas(): View {
        val celda = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = Fin.ripple(this@FinanzasAnadirActivity, rounded(0, 16), 16)
            isClickable = true
            isFocusable = true
            contentDescription = if (todas) "Ver menos categorías" else "Ver todas las categorías"
            setOnClickListener {
                todas = !todas
                pintarCategorias()
            }
        }
        celda.addView(label(if (todas) "−" else "+", 26f, C_TEXT).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_SURFACE_2)
                setStroke(dp(1), C_BORDER)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        celda.addView(label(if (todas) "Menos" else "Más", 13f, C_MUTED).apply { gravity = Gravity.CENTER }, lp(top = 6))
        return celda
    }

    /** Hoy, ayer u otro día. */
    private fun pintarFechas() {
        fechas.removeAllViews()
        val hoy = Fin.isoHace(0)
        val ayer = Fin.isoHace(1)
        val otra = fecha != hoy && fecha != ayer
        val opciones = listOf(Triple(hoy, "hoy", fecha == hoy), Triple(ayer, "ayer", fecha == ayer))
        for ((iso, nombre, activa) in opciones) {
            fechas.addView(chipFecha("${iso.substring(8, 10)}/${iso.substring(5, 7)}\n$nombre", activa, "Fecha: $nombre") {
                fecha = iso
                pintarFechas()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
        val texto = if (otra) "${fecha.substring(8, 10)}/${fecha.substring(5, 7)}\n${fecha.substring(0, 4)}" else "Otro\ndía"
        fechas.addView(chipFecha(texto, otra, if (otra) "Fecha $fecha. Toca para cambiarla." else "Elegir otro día") {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, a, m, d ->
                fecha = String.format(Locale.US, "%04d-%02d-%02d", a, m + 1, d)
                pintarFechas()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun chipFecha(texto: String, activa: Boolean, descripcion: String, onClick: () -> Unit) =
        label(texto, 14f, if (activa) Fin.BLANCO else C_MUTED, medium = true).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(56)
            setLineSpacing(0f, 1.1f)
            background = Fin.ripple(this@FinanzasAnadirActivity, rounded(if (activa) C_ACCENT else C_SURFACE, 14, if (activa) C_ACCENT else C_BORDER), 14)
            isClickable = true
            isFocusable = true
            contentDescription = descripcion + if (activa) ", seleccionada" else ""
            setOnClickListener { onClick() }
        }

    // ───────────────────────── Guardar ─────────────────────────

    /** Guarda el movimiento en el teléfono y cierra. No espera a la laptop: se sube solo después. */
    private fun anadir() {
        val valor = campoMonto.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (valor == null || valor <= 0.0) {
            campoMonto.requestFocus()
            avisar("Escribe cuánto fue.")
            return
        }
        val centavos = Math.round(valor * 100.0)
        if (!FinLocal.montoValido(centavos)) {
            campoMonto.requestFocus()
            avisar("Ese monto no se puede anotar.")
            return
        }
        val nota = campoNota.text.toString().trim().replace(Regex("\\s+"), " ").take(120)
        botonAnadir.isEnabled = false
        FinLocal.anotar(local, tipo, centavos, categoria, nota, fecha, cuenta)
        avisar(if (tipo == "ingreso") "Ingreso anotado" else "Gasto anotado")
        finish()
    }

    // ───────────────────────── Piezas ─────────────────────────

    private fun sobre(texto: String) = label(texto, 11f, C_MUTED, medium = true).apply {
        letterSpacing = 0.12f
        marcarTitulo(this)
    }

    private fun avisar(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()

    private fun marcarTitulo(v: TextView) {
        if (Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true
    }

    private fun dp(v: Int): Int = Fin.dp(this, v)

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun label(text: String, size: Float, color: Int, medium: Boolean = false) = Fin.label(this, text, size, color, medium)

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): Drawable = Fin.rounded(this, fill, radius, stroke)
}
