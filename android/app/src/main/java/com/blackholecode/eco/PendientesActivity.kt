package com.blackholecode.eco

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Paint
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Tus pendientes, en el teléfono. Son los mismos que ves en el panel de la laptop: se piden a la laptop,
 * se agrupan por urgencia (vencidos, hoy, próximos, sin fecha) y aquí los marcas o agregas uno nuevo.
 * Se construye en código, como MainActivity y ReunionesActivity.
 */
class PendientesActivity : Activity() {

    companion object {
        private const val C_BG = 0xFF0A0A12.toInt()
        private const val C_SURFACE = 0xFF14141F.toInt()
        private const val C_SURFACE_2 = 0xFF1C1C2A.toInt()
        private const val C_BORDER = 0xFF2A2A3D.toInt()
        private const val C_TEXT = 0xFFEEEEF6.toInt()
        private const val C_MUTED = 0xFFA3A3BA.toInt()
        private const val C_FAINT = 0xFF8C8CA6.toInt()
        private const val C_ACCENT = 0xFF6B5BF5.toInt()
        private const val C_ACCENT_SOFT = 0xFFB8AEFF.toInt()
        private const val C_WARN = 0xFFFFB547.toInt()
        private const val C_REC = 0xFFFF5C7A.toInt()
        private const val C_LIVE = 0xFF3DDC97.toInt()

        private const val TODOS = "todos"

        /** id del grupo, título y color (los mismos que en el panel de la laptop). */
        private val GRUPOS = listOf(
            Triple("vencidas", "Vencidos", C_REC),
            Triple("hoy", "Hoy", C_WARN),
            Triple("proximas", "Próximos", C_LIVE),
            Triple("sin", "Sin fecha", C_FAINT),
        )
    }

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var col: LinearLayout
    private lateinit var campo: EditText
    private lateinit var botonAgregar: Button
    private lateinit var chipFecha: TextView
    private lateinit var chipHora: TextView
    private lateinit var chips: LinearLayout
    private lateinit var lista: LinearLayout
    private lateinit var subtitulo: TextView

    private var tareas = JSONArray()
    private var recordar = JSONArray()
    private val hechos = HashSet<Long>()   // lo que marcaste en esta visita (se ve tachado hasta recargar)
    private var filtro = TODOS
    private var fecha: String? = null      // "2026-11-13" para el pendiente que estás agregando
    private var hora: String? = null       // "14:00"
    private var carga = 0                  // para ignorar respuestas viejas
    private var cargado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
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
        col.addView(label("Pendientes", 30f, C_TEXT, medium = true).apply { marcarTitulo(this) }, lp(top = 4))
        subtitulo = label("Lo que tienes por hacer, hasta que lo marques.", 15f, C_MUTED)
        col.addView(subtitulo, lp(top = 2))

        // Agregar un pendiente
        val tarjeta = tarjeta()
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        campo = EditText(this).apply {
            hint = "Agregar un pendiente…"
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
        fila.addView(campo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        botonAgregar = Button(this).apply {
            text = "Agregar"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(48)
            minimumHeight = dp(48)
            stateListAnimator = null
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ripple(rounded(C_ACCENT, 14), 14)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { agregar() }
        }
        fila.addView(botonAgregar, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        tarjeta.addView(fila)

        val cuando = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipFecha = chip("") { elegirFecha() }
        chipHora = chip("") { elegirHora() }
        cuando.addView(chipFecha, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        cuando.addView(chipHora, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        tarjeta.addView(cuando, lp(top = 10))
        col.addView(tarjeta, lp(top = 18))
        pintarCuando()

        // Filtros por urgencia
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val barra = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        col.addView(barra, lp(top = 16))

        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(lista, lp(top = 4))
    }

    // ───────────────────────── Cargar ─────────────────────────

    private fun cargar(silencioso: Boolean = false) {
        val mia = ++carga
        if (!silencioso) {
            lista.removeAllViews()
            lista.addView(label("Buscando en tu laptop…", 15f, C_MUTED), lp(top = 20))
        }
        if (prefs.server.isBlank()) {
            pintarError("Primero conecta tu laptop: en Eco, abre la sección «Tu laptop».")
            return
        }
        io.execute {
            val p = Net.pendientes(prefs.server, prefs.token)
            val r = if (p?.json != null) Net.recordar(prefs.server, prefs.token) else null
            ui.post {
                if (mia != carga || isFinishing) return@post
                val nuevas = p?.json
                if (nuevas == null) {
                    val texto = when {
                        p == null -> "No encuentro tu laptop. Revisa que Eco esté encendido allá y que estés en la misma red o en Tailscale."
                        p.codigo == 401 -> "La laptop respondió, pero el token no coincide."
                        else -> "La laptop respondió con error ${p.codigo}."
                    }
                    if (silencioso && cargado) avisar("No pude actualizar: tu laptop no responde.") else pintarError(texto)
                    return@post
                }
                tareas = nuevas
                recordar = r?.json ?: JSONArray()
                hechos.clear()
                cargado = true
                pintar()
            }
        }
    }

    private fun pintarError(texto: String) {
        chips.removeAllViews()
        lista.removeAllViews()
        lista.addView(avisoCard(texto), lp(top = 16))
        lista.addView(boton("Reintentar") { cargar() }, lp(top = 12))
    }

    // ───────────────────────── Pintar ─────────────────────────

    private fun pintar() {
        val cuentas = HashMap<String, Int>()
        var total = 0
        for (i in 0 until tareas.length()) {
            val t = tareas.getJSONObject(i)
            if (hechos.contains(t.optLong("id"))) continue
            val g = grupoDe(t)
            cuentas[g] = (cuentas[g] ?: 0) + 1
            total++
        }
        if (filtro != TODOS && (cuentas[filtro] ?: 0) == 0 && !hayMarcadosEn(filtro)) filtro = TODOS
        subtitulo.text = when (total) {
            0 -> "No tienes nada pendiente."
            1 -> "Tienes 1 cosa por hacer."
            else -> "Tienes $total cosas por hacer."
        }
        pintarChips(cuentas, total)
        pintarLista()
    }

    private fun hayMarcadosEn(grupo: String): Boolean {
        for (i in 0 until tareas.length()) {
            val t = tareas.getJSONObject(i)
            if (grupoDe(t) == grupo && hechos.contains(t.optLong("id"))) return true
        }
        return false
    }

    private fun pintarChips(cuentas: Map<String, Int>, total: Int) {
        chips.removeAllViews()
        if (tareas.length() == 0) return
        val todos = listOf(Triple(TODOS, "Todo", C_ACCENT_SOFT)) + GRUPOS
        var primero = true
        for ((id, titulo, color) in todos) {
            val n = if (id == TODOS) total else (cuentas[id] ?: 0)
            if (id != TODOS && n == 0 && filtro != id) continue
            val activo = filtro == id
            val v = label("$titulo  $n", 14f, if (activo) C_TEXT else C_MUTED, medium = true).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = ripple(rounded(if (activo) C_SURFACE_2 else C_BG, 22, if (activo) color else C_BORDER), 22)
                isClickable = true
                isFocusable = true
                contentDescription = "$titulo, $n" + if (activo) ", seleccionado" else ""
                setOnClickListener {
                    filtro = if (filtro == id) TODOS else id
                    pintar()
                }
            }
            chips.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = if (primero) 0 else dp(8) })
            primero = false
        }
    }

    private fun pintarLista() {
        lista.removeAllViews()
        val porGrupo = HashMap<String, ArrayList<JSONObject>>()
        for (i in 0 until tareas.length()) {
            val t = tareas.getJSONObject(i)
            porGrupo.getOrPut(grupoDe(t)) { ArrayList() }.add(t)
        }
        var algo = false
        for ((id, titulo, color) in GRUPOS) {
            if (filtro != TODOS && filtro != id) continue
            val items = porGrupo[id] ?: continue
            if (items.isEmpty()) continue
            algo = true
            lista.addView(tituloGrupo(titulo, items.count { !hechos.contains(it.optLong("id")) }, color), lp(top = 22))
            for (t in items) lista.addView(filaTarea(t, deRecordar = false), lp(top = 8))
        }
        if (!algo) {
            lista.addView(
                avisoCard("Nada pendiente. Cuando digas algo que tengas que hacer, o lo agregues aquí arriba, aparece en esta lista."),
                lp(top = 16)
            )
        }
        if (filtro == TODOS && recordar.length() > 0) {
            lista.addView(tituloGrupo("Para recordar", recordar.length(), C_ACCENT_SOFT), lp(top = 28))
            for (i in 0 until recordar.length()) lista.addView(filaTarea(recordar.getJSONObject(i), deRecordar = true), lp(top = 8))
        }
        lista.addView(boton("Actualizar") { cargar() }, lp(top = 24))
    }

    private fun tituloGrupo(titulo: String, n: Int, color: Int): View =
        label("${titulo.uppercase()}  ·  $n", 12f, color, medium = true).apply {
            letterSpacing = 0.12f
            marcarTitulo(this)
            contentDescription = "$titulo, $n"
        }

    /** Una tarea: tocar la fila la marca como hecha (o la desmarca). */
    private fun filaTarea(t: JSONObject, deRecordar: Boolean): View {
        val id = t.optLong("id")
        val texto = cadena(t, "texto")
        val cuando = cadena(t, "cuando")
        val vencida = t.optBoolean("vencida", false)
        val hoy = t.optBoolean("hoy", false)
        val deClassroom = cadena(t, "origen") == "classroom"
        val de = cadena(t, "de")

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ripple(rounded(C_SURFACE, 16, C_BORDER), 16)
            setPadding(dp(8), dp(10), dp(16), dp(10))
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
        }
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val caja = CheckBox(this).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList(estados, intArrayOf(C_ACCENT, C_FAINT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fila.addView(caja, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })

        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titulo = label(texto, 16f, C_TEXT).apply { setLineSpacing(0f, 1.2f) }
        textos.addView(titulo, lp(top = 6))
        if (cuando.isNotEmpty()) {
            val color = if (vencida) C_REC else if (hoy) C_WARN else C_MUTED
            textos.addView(label((if (vencida) "Venció " else "") + cuando, 13f, color, medium = vencida || hoy), lp(top = 2))
        }
        val origen = when {
            deClassroom -> "De Classroom"
            cadena(t, "tipo") == "idea" -> if (de.isNotEmpty()) "Idea · de «$de»" else "Idea"
            de.isNotEmpty() -> "De «$de»"
            else -> ""
        }
        if (origen.isNotEmpty()) {
            textos.addView(label(origen, 13f, C_FAINT).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(top = 2))
        }
        fila.addView(textos, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4); bottomMargin = dp(6) })

        fun reflejar() {
            val hecho = hechos.contains(id)
            caja.isChecked = hecho
            titulo.setTextColor(if (hecho) C_FAINT else C_TEXT)
            titulo.paintFlags = if (hecho) titulo.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else titulo.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            val que = if (deRecordar) "listo" else "hecho"
            fila.contentDescription = (if (hecho) "Marcado como $que: " else "Sin marcar: ") + texto +
                (if (cuando.isNotEmpty()) ". " + (if (vencida) "Venció " else "") + cuando else "") +
                ". Toca para " + (if (hecho) "desmarcar." else "marcar como $que.")
        }
        reflejar()

        fila.setOnClickListener {
            val hecho = !hechos.contains(id)
            if (hecho) hechos.add(id) else hechos.remove(id)
            reflejar()
            fila.isEnabled = false
            io.execute {
                val ok = Net.marcarTarea(prefs.server, prefs.token, id, hecho)
                ui.post {
                    if (isFinishing) return@post
                    fila.isEnabled = true
                    if (!ok) {
                        if (hecho) hechos.remove(id) else hechos.add(id)
                        reflejar()
                        avisar("No se pudo guardar. Revisa la conexión con tu laptop.")
                    } else {
                        actualizarCuentas()
                    }
                }
            }
        }
        return fila
    }

    /** Después de marcar algo: se actualizan los números sin mover la lista de lugar. */
    private fun actualizarCuentas() {
        val cuentas = HashMap<String, Int>()
        var total = 0
        for (i in 0 until tareas.length()) {
            val t = tareas.getJSONObject(i)
            if (hechos.contains(t.optLong("id"))) continue
            val g = grupoDe(t)
            cuentas[g] = (cuentas[g] ?: 0) + 1
            total++
        }
        subtitulo.text = when (total) {
            0 -> "No tienes nada pendiente."
            1 -> "Tienes 1 cosa por hacer."
            else -> "Tienes $total cosas por hacer."
        }
        pintarChips(cuentas, total)
    }

    private fun grupoDe(t: JSONObject): String = when {
        t.optBoolean("vencida", false) -> "vencidas"
        t.optBoolean("hoy", false) -> "hoy"
        cadena(t, "cuando").isNotEmpty() -> "proximas"
        else -> "sin"
    }

    /** El texto de un campo, o "" si no viene o viene vacío (optString devuelve "null" para un nulo). */
    private fun cadena(t: JSONObject, k: String): String = if (t.isNull(k)) "" else t.optString(k).trim()

    // ───────────────────────── Agregar ─────────────────────────

    private fun agregar() {
        val texto = campo.text.toString().trim()
        if (texto.isEmpty()) {
            campo.requestFocus()
            return
        }
        if (prefs.server.isBlank()) {
            avisar("Primero conecta tu laptop.")
            return
        }
        botonAgregar.isEnabled = false
        val f = fecha
        val h = hora
        io.execute {
            val r = Net.nuevoPendiente(prefs.server, prefs.token, texto, f, h)
            ui.post {
                if (isFinishing) return@post
                botonAgregar.isEnabled = true
                if (r?.codigo == 200) {
                    campo.setText("")
                    fecha = null
                    hora = null
                    pintarCuando()
                    avisar(if (f != null) "Agregado con fecha" else "Agregado a Pendientes")
                    cargar(silencioso = true)
                } else {
                    avisar(if (r == null) "No encuentro tu laptop. Inténtalo cuando estés en tu red." else "No se pudo agregar (error ${r.codigo}).")
                }
            }
        }
    }

    private fun elegirFecha() {
        if (fecha != null) {  // tocar la fecha puesta la quita
            fecha = null
            hora = null
            pintarCuando()
            return
        }
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, a, m, d ->
            fecha = String.format(Locale.US, "%04d-%02d-%02d", a, m + 1, d)
            pintarCuando()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun elegirHora() {
        if (hora != null) {
            hora = null
            pintarCuando()
            return
        }
        TimePickerDialog(this, { _, h, min ->
            hora = String.format(Locale.US, "%02d:%02d", h, min)
            pintarCuando()
        }, 9, 0, true).show()
    }

    private fun pintarCuando() {
        val f = fecha
        val h = hora
        chipFecha.text = if (f == null) "+ Fecha" else "${f.substring(8, 10)}/${f.substring(5, 7)}/${f.substring(0, 4)}  ✕"
        chipFecha.contentDescription = if (f == null) "Ponerle fecha (opcional)" else "Fecha $f. Toca para quitarla."
        chipFecha.setTextColor(if (f == null) C_MUTED else C_ACCENT_SOFT)
        chipHora.visibility = if (f == null) View.GONE else View.VISIBLE
        chipHora.text = if (h == null) "+ Hora" else "$h  ✕"
        chipHora.contentDescription = if (h == null) "Ponerle hora (opcional)" else "Hora $h. Toca para quitarla."
        chipHora.setTextColor(if (h == null) C_MUTED else C_ACCENT_SOFT)
    }

    // ───────────────────────── Piezas ─────────────────────────

    private fun chip(texto: String, onClick: () -> Unit) = label(texto, 14f, C_MUTED, medium = true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(44)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = ripple(rounded(C_BG, 22, C_BORDER), 22)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
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

    private fun boton(texto: String, onClick: () -> Unit) = Button(this).apply {
        this.text = texto
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = ripple(rounded(C_SURFACE_2, 14, C_BORDER), 14)
        setTextColor(C_TEXT)
        setOnClickListener { onClick() }
    }
}
