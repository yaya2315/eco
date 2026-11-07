package com.blackholecode.eco

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Tus reuniones, leídas en el teléfono. La laptop las convierte en una crónica (la reunión contada como
 * una charla: temas explicados, silencios, acuerdos) y aquí se pide y se lee. Dos vistas en una pantalla:
 * la lista de reuniones y, al tocar una, su crónica completa. Se construye en código, como MainActivity.
 */
class ReunionesActivity : Activity() {

    companion object {
        /** Id de la reunión que hay que abrir directo (lo manda la notificación "tu reunión está lista"). */
        const val EXTRA_ID = "reunion_id"

        private const val C_BG = 0xFF0A0A12.toInt()
        private const val C_SURFACE = 0xFF14141F.toInt()
        private const val C_SURFACE_2 = 0xFF1C1C2A.toInt()
        private const val C_BORDER = 0xFF2A2A3D.toInt()
        private const val C_TEXT = 0xFFEEEEF6.toInt()
        private const val C_MUTED = 0xFFA3A3BA.toInt()
        private const val C_ACCENT = 0xFF6B5BF5.toInt()
        private const val C_ACCENT_SOFT = 0xFFB8AEFF.toInt()
        private const val C_WARN = 0xFFFFB547.toInt()
    }

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout

    private var enDetalle = false
    private var abiertaDeFrente = false  // llegó desde la notificación: "atrás" cierra la pantalla
    private var carga = 0                // para ignorar respuestas viejas si tocas rápido

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        scroll = ScrollView(this).apply {
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
        mostrar(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent != null) mostrar(intent)
    }

    override fun onDestroy() {
        carga++
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (enDetalle && !abiertaDeFrente) cargarLista() else super.onBackPressed()
    }

    private fun mostrar(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        abiertaDeFrente = id > 0
        if (id > 0) cargarDetalle(id) else cargarLista()
    }

    // ───────────────────────── Lista ─────────────────────────

    private fun cargarLista() {
        enDetalle = false
        val mia = ++carga
        encabezado("Reuniones", "Eco te cuenta qué pasó en cada una.")
        col.addView(label("Buscando en tu laptop…", 15f, C_MUTED), lp(top = 24))
        if (prefs.server.isBlank()) {
            pintarLista(null)
            return
        }
        io.execute {
            val r = Net.reuniones(prefs.server, prefs.token)
            ui.post { if (mia == carga && !isFinishing) pintarLista(r) }
        }
    }

    private fun pintarLista(r: Net.Respuesta?) {
        encabezado("Reuniones", "Eco te cuenta qué pasó en cada una.")
        val j = r?.json
        if (j == null) {
            val texto = when {
                prefs.server.isBlank() -> "Primero conecta tu laptop: en Eco, abre la sección «Tu laptop»."
                r?.codigo == 401 -> "La laptop respondió, pero el token no coincide."
                r == null -> "No encuentro tu laptop. Revisa que Eco esté encendido allá y que estés en la misma red o en Tailscale."
                else -> "La laptop respondió con error ${r.codigo}. Falta actualizar Eco en la laptop."
            }
            col.addView(avisoCard(texto), lp(top = 20))
            col.addView(boton("Reintentar") { cargarLista() }, lp(top = 12))
            return
        }
        val procesando = j.optJSONArray("procesando")
        if (procesando != null) {
            for (i in 0 until procesando.length()) {
                val p = procesando.getJSONObject(i)
                val estado = if (p.optString("estado") == "grabando") "se sigue recibiendo en la laptop" else "la laptop la está contando"
                col.addView(procesandoCard("Reunión de las ${p.optString("hora")}", estado), lp(top = 14))
            }
        }
        val lista = j.optJSONArray("reuniones")
        if (lista == null || lista.length() == 0) {
            if (procesando == null || procesando.length() == 0) {
                col.addView(
                    avisoCard("Todavía no hay reuniones. Cuando termines una con «Iniciar modo reunión», la laptop la procesa y aparece aquí."),
                    lp(top = 20)
                )
            }
            return
        }
        for (i in 0 until lista.length()) {
            val m = lista.getJSONObject(i)
            col.addView(tarjetaReunion(m), lp(top = 14))
        }
    }

    private fun tarjetaReunion(m: JSONObject): View {
        val id = m.optLong("id")
        val titulo = m.optString("titulo").ifBlank { "Reunión" }
        val duracion = duracionTexto(m.optDouble("duracion", 0.0))
        val meta = listOf(m.optString("fecha"), m.optString("hora"), duracion).filter { it.isNotBlank() }.joinToString(" · ")
        val conCronica = m.optBoolean("cronica", false)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(rounded(C_SURFACE, 20, C_BORDER), 20)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true
            isFocusable = true
            minimumHeight = dp(64)
            contentDescription = "$titulo. $meta. " + if (conCronica) "Con crónica." else "Solo resumen."
            setOnClickListener { cargarDetalle(id) }
        }
        card.addView(label(titulo, 17f, C_TEXT, medium = true))
        card.addView(label(meta, 13f, C_MUTED), lp(top = 4))
        val resumen = m.optString("resumen")
        if (resumen.isNotBlank()) {
            card.addView(label(resumen, 14f, C_MUTED).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.2f)
            }, lp(top = 8))
        }
        card.addView(
            label(if (conCronica) "Crónica lista · toca para leer" else "Solo resumen", 13f, if (conCronica) C_ACCENT_SOFT else C_MUTED, medium = true),
            lp(top = 10)
        )
        return card
    }

    private fun procesandoCard(titulo: String, estado: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(C_SURFACE, 20, C_BORDER)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        contentDescription = "$titulo: $estado"
        val punto = View(this@ReunionesActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_WARN) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(punto, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })
        val textos = LinearLayout(this@ReunionesActivity).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(label(titulo, 15f, C_TEXT, medium = true))
        textos.addView(label(estado.replaceFirstChar { it.uppercase() }, 13f, C_MUTED), lp(top = 2))
        addView(textos, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    // ───────────────────────── Una reunión ─────────────────────────

    private fun cargarDetalle(id: Long) {
        enDetalle = true
        val mia = ++carga
        col.removeAllViews()
        col.addView(label("Abriendo la reunión…", 15f, C_MUTED), lp(top = 24))
        if (prefs.server.isBlank()) {
            pintarDetalle(null)
            return
        }
        io.execute {
            val r = Net.reunion(prefs.server, prefs.token, id = id)
            ui.post { if (mia == carga && !isFinishing) pintarDetalle(r) }
        }
    }

    private fun pintarDetalle(r: Net.Respuesta?) {
        col.removeAllViews()
        if (!abiertaDeFrente) col.addView(botonAtras())
        val m = r?.json?.optJSONObject("reunion")
        if (m == null) {
            val texto = when {
                r == null -> "No encuentro tu laptop ahora. Intenta de nuevo cuando estés en tu red."
                r.codigo == 404 -> "Esa reunión ya no está en la laptop."
                else -> "No se pudo abrir la reunión (error ${r.codigo})."
            }
            col.addView(avisoCard(texto), lp(top = 16))
            col.addView(boton("Ver todas las reuniones") { abiertaDeFrente = false; cargarLista() }, lp(top = 12))
            return
        }
        val titulo = m.optString("titulo").ifBlank { "Reunión" }
        val duracion = duracionTexto(m.optDouble("duracion", 0.0))
        val meta = listOf(m.optString("fecha"), m.optString("hora"), duracion).filter { it.isNotBlank() }.joinToString(" · ")
        col.addView(label("REUNIÓN", 11f, C_ACCENT_SOFT, medium = true).apply { letterSpacing = 0.18f }, lp(top = if (abiertaDeFrente) 0 else 16))
        col.addView(label(titulo, 26f, C_TEXT, medium = true).apply { marcarTitulo(this) }, lp(top = 4))
        col.addView(label(meta, 14f, C_MUTED), lp(top = 4))

        val resumen = m.optString("resumen")
        if (resumen.isNotBlank()) {
            val card = tarjeta()
            card.addView(label("EN POCAS PALABRAS", 11f, C_MUTED, medium = true).apply { letterSpacing = 0.12f })
            card.addView(parrafo(resumen, 15f), lp(top = 8))
            col.addView(card, lp(top = 16))
        }

        val cronica = if (m.isNull("cronica")) "" else m.optString("cronica")
        if (cronica.isNotBlank()) {
            col.addView(label("LO QUE PASÓ", 11f, C_ACCENT_SOFT, medium = true).apply { letterSpacing = 0.12f }, lp(top = 26))
            agregarCronica(cronica)
            col.addView(boton("Compartir la crónica") { compartir(titulo, meta, cronica) }, lp(top = 24))
        } else {
            col.addView(
                avisoCard("Esta reunión se guardó sin crónica (solo el resumen). La crónica se escribe cuando termina la reunión y la IA de tu laptop está disponible."),
                lp(top = 20)
            )
        }

        seccionLista("Por hacer", m.optJSONArray("tareas")?.let { a ->
            (0 until a.length()).map { i ->
                val t = a.getJSONObject(i)
                val cuando = t.optString("cuando")
                t.optString("texto") + if (cuando.isNotBlank()) " — $cuando" else ""
            }
        })
        seccionLista("Para no olvidar", listaDeTextos(m.optJSONArray("datos")))
        seccionLista("Ideas", listaDeTextos(m.optJSONArray("ideas")))
    }

    /** La crónica llega como texto: "### título (hora)" para cada tema y párrafos debajo. */
    private fun agregarCronica(texto: String) {
        val bloques = texto.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        for (b in bloques) {
            if (b.startsWith("###")) {
                val partes = b.removePrefix("###").trim().split("\n", limit = 2)
                col.addView(label(partes[0].trim(), 17f, C_ACCENT_SOFT, medium = true).apply { marcarTitulo(this) }, lp(top = 24))
                if (partes.size > 1 && partes[1].isNotBlank()) col.addView(parrafo(partes[1].trim(), 16f), lp(top = 8))
            } else {
                col.addView(parrafo(b, 16f), lp(top = 10))
            }
        }
    }

    private fun seccionLista(titulo: String, items: List<String>?) {
        if (items.isNullOrEmpty()) return
        val card = tarjeta()
        card.addView(label(titulo.uppercase(), 11f, C_MUTED, medium = true).apply { letterSpacing = 0.12f })
        for (t in items) card.addView(label("•  $t", 15f, C_TEXT).apply { setLineSpacing(0f, 1.25f) }, lp(top = 8))
        col.addView(card, lp(top = 16))
    }

    private fun listaDeTextos(a: org.json.JSONArray?): List<String>? =
        a?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }

    private fun compartir(titulo: String, meta: String, cronica: String) {
        val envio = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, titulo)
            .putExtra(Intent.EXTRA_TEXT, "$titulo\n$meta\n\n$cronica")
        startActivity(Intent.createChooser(envio, "Compartir la crónica"))
    }

    // ───────────────────────── Piezas ─────────────────────────

    private fun encabezado(titulo: String, subtitulo: String) {
        col.removeAllViews()
        if (!abiertaDeFrente) col.addView(botonAtras())
        col.addView(label("BLACK HOLE CODE", 11f, C_ACCENT_SOFT, medium = true).apply { letterSpacing = 0.18f }, lp(top = 16))
        col.addView(label(titulo, 30f, C_TEXT, medium = true).apply { marcarTitulo(this) }, lp(top = 4))
        col.addView(label(subtitulo, 15f, C_MUTED), lp(top = 2))
    }

    /** "← Eco": vuelve a la pantalla principal (o a la lista, desde una reunión). */
    private fun botonAtras(): View = label(if (enDetalle) "←  Reuniones" else "←  Eco", 15f, C_ACCENT_SOFT, medium = true).apply {
        minimumHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = if (enDetalle) "Volver a la lista de reuniones" else "Volver a Eco"
        setOnClickListener { if (enDetalle) cargarLista() else finish() }
    }

    private fun marcarTitulo(v: TextView) {
        if (Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true
    }

    private fun avisoCard(texto: String): View = tarjeta().apply {
        addView(label(texto, 15f, C_TEXT).apply { setLineSpacing(0f, 1.25f) })
    }

    private fun tarjeta() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_SURFACE, 20, C_BORDER)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun parrafo(texto: String, tam: Float) = label(texto, tam, C_TEXT).apply {
        setLineSpacing(0f, 1.4f)
        setTextIsSelectable(true)
    }

    private fun duracionTexto(seg: Double): String {
        val min = (seg / 60).roundToInt()
        return when {
            seg <= 0 -> ""
            min < 1 -> "menos de 1 min"
            min < 60 -> "$min min"
            else -> "${min / 60} h" + if (min % 60 > 0) " ${min % 60} min" else ""
        }
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
