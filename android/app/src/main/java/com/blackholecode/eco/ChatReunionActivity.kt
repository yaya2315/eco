package com.blackholecode.eco

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Preguntarle a una reunión o clase. Escribes la pregunta tal como te la hicieron y Eco, en la laptop, busca en
 * lo que se dijo y te responde listo para decirlo. La conversación vive en la laptop: es la misma que ves en el
 * apartado Reuniones del panel. La IA de la laptop tarda (corre en el procesador), así que la pregunta se guarda
 * al instante y esta pantalla le pregunta a la laptop cada pocos segundos si ya respondió.
 * Se construye en código, como el resto de la app.
 */
class ChatReunionActivity : Activity() {

    companion object {
        const val EXTRA_ID = "chat_reunion_id"
        const val EXTRA_TITULO = "chat_reunion_titulo"

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

        private const val CADA_MS = 3000L   // cada cuánto se pregunta si ya respondió (solo con la pantalla abierta)
    }

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var id = -1L
    private var conv: JSONObject? = null
    private var firma = ""
    private var carga = 0
    private var visible = false
    private var preguntaLocal: String? = null          // la que acabas de mandar, mientras la laptop la confirma
    private var desdeLocal = 0L
    private val desde = HashMap<Long, Long>()          // cuándo empezó a pensar cada respuesta (para el reloj)
    private val citasAbiertas = HashSet<String>()      // "id-n": las citas que desplegaste
    private var estabaPensando = false

    private lateinit var scroll: ScrollView
    private lateinit var mensajes: LinearLayout
    private lateinit var campo: EditText
    private lateinit var enviar: TextView
    private lateinit var nota: TextView
    private lateinit var tipoV: TextView
    private lateinit var tituloV: TextView
    private lateinit var metaV: TextView
    private lateinit var borrarV: TextView

    private val sondeo = Runnable { cargar() }
    private val reloj = object : Runnable {
        override fun run() {
            marcarReloj()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) {
            finish()
            return
        }
        setContentView(construir(intent.getStringExtra(EXTRA_TITULO).orEmpty()))
    }

    override fun onResume() {
        super.onResume()
        visible = true
        cargar()
    }

    override fun onPause() {
        visible = false
        ui.removeCallbacks(sondeo)
        ui.removeCallbacks(reloj)
        super.onPause()
    }

    override fun onDestroy() {
        carga++
        io.shutdownNow()
        super.onDestroy()
    }

    // ───────────────────────── Pantalla ─────────────────────────

    private fun construir(tituloInicial: String): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }

        // Arriba: volver, qué grabación es y borrar la conversación.
        val cabeza = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(label("←  Volver", 15f, C_ACCENT_SOFT, medium = true).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Volver"
            setOnClickListener { finish() }
        })
        fila.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        borrarV = label("Borrar conversación", 14f, C_MUTED, medium = true).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { confirmarBorrar() }
        }
        fila.addView(borrarV)
        cabeza.addView(fila)
        tipoV = label("", 11f, C_ACCENT_SOFT, medium = true).apply { letterSpacing = 0.18f }
        cabeza.addView(tipoV, lp(top = 2))
        tituloV = label(tituloInicial, 20f, C_TEXT, medium = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        cabeza.addView(tituloV, lp(top = 2))
        metaV = label("", 13f, C_MUTED)
        cabeza.addView(metaV, lp(top = 2))
        raiz.addView(cabeza, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        raiz.addView(linea())

        // En medio: la conversación.
        scroll = ScrollView(this).apply {
            isFillViewport = true
            // Al abrir el teclado la pantalla se achica: se sigue viendo lo último.
            addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, viejoBottom ->
                if (bottom < viejoBottom) post { scrollTo(0, mensajes.height) }
            }
        }
        mensajes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        mensajes.addView(label("Abriendo la conversación…", 15f, C_MUTED), lp(top = 8))
        scroll.addView(mensajes, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        raiz.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        raiz.addView(linea())

        // Abajo: escribir y preguntar.
        val escribir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        campo = EditText(this).apply {
            hint = "Escribe tu pregunta…"
            setHintTextColor(C_FAINT)
            setTextColor(C_TEXT)
            textSize = 16f
            background = rounded(C_SURFACE_2, 24, C_BORDER)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(48)
            // Sin salto de línea: el renglón se ajusta solo y la tecla Enter pregunta.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            isEnabled = false
            setOnEditorActionListener { _, accion, evento ->
                val esEnter = evento != null && evento.keyCode == KeyEvent.KEYCODE_ENTER && evento.action == KeyEvent.ACTION_DOWN
                if (accion == EditorInfo.IME_ACTION_SEND || esEnter) {
                    preguntar(text.toString())
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = ponerEnviar()
            })
        }
        escribir.addView(campo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        enviar = label("↑", 22f, 0xFFFFFFFF.toInt(), medium = true).apply {
            gravity = Gravity.CENTER
            background = ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_ACCENT) }, 24)
            contentDescription = "Preguntar"
            isClickable = true
            isFocusable = true
            setOnClickListener { preguntar(campo.text.toString()) }
        }
        escribir.addView(enviar, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        raiz.addView(escribir, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        nota = label("Eco responde con lo que se dijo en la grabación.", 12f, C_FAINT).apply {
            setPadding(dp(20), dp(2), dp(20), dp(10))
        }
        raiz.addView(nota, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ponerEnviar()
        return raiz
    }

    private fun ponerEnviar() {
        val puede = campo.isEnabled && campo.text.toString().isNotBlank() && !pensando()
        enviar.isEnabled = puede
        enviar.alpha = if (puede) 1f else 0.4f
    }

    private fun pensando(): Boolean = preguntaLocal != null || conv?.optBoolean("pensando", false) == true

    // ───────────────────────── Laptop ─────────────────────────

    private fun cargar() {
        ui.removeCallbacks(sondeo)
        if (prefs.server.isBlank()) {
            pintarError("Primero conecta tu laptop: en Eco, abre la sección «Tu laptop».")
            return
        }
        val mia = ++carga
        io.execute {
            val r = Net.chat(prefs.server, prefs.token, id)
            ui.post {
                if (mia != carga || isFinishing) return@post
                val j = r?.json
                when {
                    j != null -> {
                        conv = j
                        pintar()
                        if (j.optBoolean("pensando", false) && visible) ui.postDelayed(sondeo, CADA_MS)
                    }
                    conv != null -> {
                        // Ya había conversación en pantalla: un corte de red no la borra, se reintenta.
                        nota.text = "No encuentro tu laptop ahora; reintento en unos segundos."
                        if (visible) ui.postDelayed(sondeo, CADA_MS * 2)
                    }
                    r == null -> pintarError("No encuentro tu laptop. Revisa que Eco esté encendido allá y que estés en la misma red o en Tailscale.")
                    r.codigo == 401 -> pintarError("La laptop respondió, pero el token no coincide. Vuelve a conectar el teléfono desde el panel.")
                    r.codigo == 403 -> pintarError("Preguntarle a las reuniones está apagado en la laptop ([chat] activo = false en config.toml).")
                    r.codigo == 404 -> pintarError("Esta grabación ya no está en la laptop, o falta actualizar Eco allá.")
                    else -> pintarError("La laptop respondió con error ${r.codigo}. Falta actualizar Eco en la laptop.")
                }
            }
        }
    }

    private fun preguntar(texto: String) {
        val pregunta = texto.replace(Regex("\\s+"), " ").trim()
        if (pregunta.isEmpty() || pensando() || conv == null) return
        preguntaLocal = pregunta
        desdeLocal = System.currentTimeMillis()
        campo.setText("")
        pintar(abajo = true)
        val mia = ++carga
        io.execute {
            val r = Net.preguntarReunion(prefs.server, prefs.token, id, pregunta)
            ui.post {
                if (isFinishing) return@post
                preguntaLocal = null
                val j = r?.json
                if (r?.codigo == 200 && j != null) {
                    // El reloj sigue desde que tocaste "preguntar", no desde que llegó la confirmación.
                    val lista = j.optJSONArray("mensajes")
                    val ultimo = lista?.optJSONObject(lista.length() - 1)
                    if (ultimo != null) desde[ultimo.optLong("id")] = desdeLocal
                    if (mia == carga) {
                        conv = j
                        pintar(abajo = true)
                    }
                    if (visible) {
                        ui.removeCallbacks(sondeo)
                        ui.postDelayed(sondeo, CADA_MS)
                    }
                } else {
                    campo.setText(pregunta)
                    campo.setSelection(campo.text.length)
                    val motivo = j?.optString("error").orEmpty()
                    Toast.makeText(
                        this,
                        when {
                            r == null -> "No encuentro tu laptop. La pregunta sigue escrita: mándala cuando vuelvas a tu red."
                            motivo.isNotBlank() -> motivo
                            else -> "La laptop no aceptó la pregunta (error ${r.codigo})."
                        },
                        Toast.LENGTH_LONG
                    ).show()
                    pintar()
                    cargar()
                }
            }
        }
    }

    private fun confirmarBorrar() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar esta conversación?")
            .setMessage("Se borran tus preguntas y las respuestas de Eco sobre esta grabación, aquí y en el panel. La grabación y sus notas se quedan.")
            .setPositiveButton("Borrar") { _, _ ->
                io.execute {
                    val ok = Net.borrarChat(prefs.server, prefs.token, id)
                    ui.post {
                        if (isFinishing) return@post
                        if (!ok) Toast.makeText(this, "No pude borrarla: la laptop no está al alcance.", Toast.LENGTH_LONG).show()
                        firma = ""
                        cargar()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ───────────────────────── Pintar ─────────────────────────

    private fun pintarError(texto: String) {
        conv = null
        firma = ""
        campo.isEnabled = false
        ponerEnviar()
        mensajes.removeAllViews()
        val card = tarjeta()
        card.addView(label(texto, 15f, C_TEXT).apply { setLineSpacing(0f, 1.3f) })
        mensajes.addView(card, lp(top = 8))
        mensajes.addView(botonSuave("Reintentar") { cargar() }, lp(top = 12))
    }

    private fun pintar(abajo: Boolean = false) {
        val c = conv ?: return
        val r = c.optJSONObject("reunion")
        val esClase = r?.optString("tipo") == "clase"
        val cosa = if (esClase) "clase" else "reunión"
        tipoV.text = cosa.uppercase()
        if (r != null) {
            tituloV.text = r.optString("titulo").ifBlank { cosa.replaceFirstChar { it.uppercase() } }
            metaV.text = listOf(r.optString("fecha"), r.optString("hora"), duracionTexto(r.optDouble("duracion", 0.0)))
                .filter { it.isNotBlank() }.joinToString(" · ")
        }
        val lista = c.optJSONArray("mensajes")
        val n = lista?.length() ?: 0
        val piensa = pensando()
        campo.isEnabled = true
        borrarV.visibility = if (n > 0 && !piensa) View.VISIBLE else View.GONE
        ponerEnviar()

        nota.setTextColor(if (piensa && c.optBoolean("ocupada", false)) C_WARN else C_FAINT)
        nota.text = when {
            piensa && c.optBoolean("ocupada", false) -> "Tu laptop está transcribiendo otra grabación: esta respuesta va a tardar más."
            piensa -> "La IA de tu laptop está leyendo la grabación; suele tardar uno o dos minutos. Puedes salir y volver."
            r?.optBoolean("transcripcion", true) == false -> "Esta grabación no guardó lo que se dijo: Eco responde con el resumen y las notas."
            else -> "Eco responde con lo que se dijo en la grabación."
        }

        // Solo se vuelve a dibujar si algo cambió (así no salta mientras lees).
        val partes = StringBuilder()
        for (i in 0 until n) {
            val m = lista!!.getJSONObject(i)
            partes.append(m.optLong("id")).append(m.optString("estado")).append(m.optString("texto").length).append('|')
        }
        partes.append(preguntaLocal ?: "")
        val nueva = partes.toString()
        if (nueva != firma) {
            val ultimo = if (n > 0) lista!!.getJSONObject(n - 1) else null
            if (estabaPensando && !piensa && ultimo?.optString("rol") == "eco") {
                mensajes.announceForAccessibility("Eco respondió: " + ultimo.optString("texto"))
            }
            firma = nueva
            val cerca = scroll.scrollY + scroll.height >= mensajes.height - dp(160)
            mensajes.removeAllViews()
            if (n == 0 && preguntaLocal == null) {
                pintarIntro(c, cosa)
            } else {
                for (i in 0 until n) agregarMensaje(lista!!.getJSONObject(i))
                preguntaLocal?.let {
                    agregarMensaje(JSONObject().put("id", -1).put("rol", "yo").put("texto", it).put("estado", "listo"))
                    agregarMensaje(JSONObject().put("id", -2).put("rol", "eco").put("texto", "").put("estado", "pensando"))
                }
            }
            if (abajo || cerca) scroll.post { scroll.scrollTo(0, mensajes.height) }
        }
        estabaPensando = piensa
        ui.removeCallbacks(reloj)
        if (piensa && visible) reloj.run()
    }

    private fun pintarIntro(c: JSONObject, cosa: String) {
        mensajes.addView(label("Pregúntale a esta $cosa", 22f, C_TEXT, medium = true).apply {
            gravity = Gravity.CENTER
        }, lp(top = 24))
        mensajes.addView(label(
            "Escribe la pregunta tal como te la hicieron. Eco busca en toda la grabación y te da la respuesta lista para decirla.",
            15f, C_MUTED
        ).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }, lp(top = 8))
        val sugerencias = c.optJSONArray("sugerencias") ?: return
        if (sugerencias.length() == 0) return
        // Ideas para empezar, en una fila que se desliza de lado.
        val carrusel = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until sugerencias.length()) {
            val texto = sugerencias.optString(i)
            if (texto.isBlank()) continue
            fila.addView(label(texto, 14f, C_TEXT).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = ripple(rounded(C_SURFACE_2, 22, C_BORDER), 22)
                isClickable = true
                isFocusable = true
                contentDescription = "Preguntar: $texto"
                setOnClickListener { preguntar(texto) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        carrusel.addView(fila)
        mensajes.addView(carrusel, lp(top = 24))
    }

    private fun agregarMensaje(m: JSONObject) {
        val mid = m.optLong("id")
        val texto = m.optString("texto")
        val anchoMax = (resources.displayMetrics.widthPixels * 0.82f).roundToInt()
        if (m.optString("rol") == "yo") {
            val caja = LinearLayout(this).apply { gravity = Gravity.END }
            caja.addView(label(texto, 16f, 0xFFFFFFFF.toInt()).apply {
                maxWidth = anchoMax
                setLineSpacing(0f, 1.25f)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = burbuja(C_ACCENT, null, mia = true)
                setTextIsSelectable(true)
                contentDescription = "Tú preguntaste: $texto"
            })
            mensajes.addView(caja, lp(top = 16))
            return
        }
        val estado = m.optString("estado")
        val hora = m.optString("hora")
        mensajes.addView(label(if (hora.isNotBlank() && estado != "pensando") "Eco · $hora" else "Eco", 12f, C_FAINT), lp(top = 16))
        if (estado == "pensando") {
            if (!desde.containsKey(mid)) desde[mid] = if (mid == -2L) desdeLocal else System.currentTimeMillis()
            mensajes.addView(label("Buscando en la grabación…", 15f, C_MUTED).apply {
                tag = "reloj:$mid"
                maxWidth = anchoMax
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = burbuja(C_SURFACE_2, C_BORDER, mia = false)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            return
        }
        val error = estado == "error"
        mensajes.addView(label(texto, 16f, if (error) C_WARN else C_TEXT).apply {
            maxWidth = anchoMax
            setLineSpacing(0f, 1.35f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = burbuja(C_SURFACE_2, if (error) 0x66FFB547 else C_BORDER, mia = false)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // Debajo: de dónde salió (la hora en la grabación) y copiar, o volver a preguntar si falló.
        val acciones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val citasCaja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (error) {
            acciones.addView(chip("Volver a preguntar") { reintentar(mid) })
        } else {
            val citas = m.optJSONArray("citas")
            for (i in 0 until (citas?.length() ?: 0)) {
                val cita = citas!!.getJSONObject(i)
                val clave = "$mid-$i"
                val cuerpo = label("«${cita.optString("texto")}»", 14f, C_MUTED).apply {
                    setLineSpacing(0f, 1.3f)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = rounded(0x0FB8AEFF, 10)
                    visibility = if (clave in citasAbiertas) View.VISIBLE else View.GONE
                    setTextIsSelectable(true)
                }
                acciones.addView(chip("Se dijo a las ${cita.optString("hora")}") {
                    val abrir = cuerpo.visibility != View.VISIBLE
                    cuerpo.visibility = if (abrir) View.VISIBLE else View.GONE
                    if (abrir) citasAbiertas.add(clave) else citasAbiertas.remove(clave)
                })
                citasCaja.addView(cuerpo, lp(top = 8))
            }
            acciones.addView(chip("Copiar") { copiar(texto) })
        }
        val carril = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        carril.addView(acciones)
        mensajes.addView(carril, lp(top = 8))
        mensajes.addView(citasCaja, lp())
    }

    private fun reintentar(mid: Long) {
        val lista = conv?.optJSONArray("mensajes") ?: return
        var pregunta: String? = null
        for (i in 0 until lista.length()) {
            val m = lista.getJSONObject(i)
            if (m.optLong("id") == mid) break
            if (m.optString("rol") == "yo") pregunta = m.optString("texto")
        }
        pregunta?.let { preguntar(it) }
    }

    private fun copiar(texto: String) {
        val portapapeles = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        portapapeles.setPrimaryClip(ClipData.newPlainText("Respuesta de Eco", texto))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    /** "Buscando en la grabación… 42 s", con puntos que se mueven. */
    private fun marcarReloj() {
        for (i in 0 until mensajes.childCount) {
            val v = mensajes.getChildAt(i)
            val tag = v.tag as? String ?: continue
            if (!tag.startsWith("reloj:") || v !is TextView) continue
            val inicio = desde[tag.removePrefix("reloj:").toLongOrNull() ?: continue] ?: continue
            val s = ((System.currentTimeMillis() - inicio) / 1000).toInt().coerceAtLeast(0)
            val tiempo = if (s < 60) "$s s" else "${s / 60} min ${"%02d".format(s % 60)} s"
            val puntos = ".".repeat(s % 3 + 1).padEnd(3, ' ')
            v.text = "Buscando en la grabación$puntos  $tiempo"
        }
    }

    // ───────────────────────── Piezas ─────────────────────────

    private fun duracionTexto(seg: Double): String {
        val min = (seg / 60).roundToInt()
        return when {
            seg <= 0 -> ""
            min < 1 -> "menos de 1 min"
            min < 60 -> "$min min"
            else -> "${min / 60} h" + if (min % 60 > 0) " ${min % 60} min" else ""
        }
    }

    private fun burbuja(color: Int, borde: Int?, mia: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        val r = dp(18).toFloat()
        val chico = dp(6).toFloat()
        // Esquinas: arriba-izq, arriba-der, abajo-der, abajo-izq (la de "la colita" va más cerrada).
        cornerRadii = if (mia) floatArrayOf(r, r, r, r, chico, chico, r, r) else floatArrayOf(r, r, r, r, r, r, chico, chico)
        if (borde != null) setStroke(dp(1), borde)
    }

    private fun chip(texto: String, onClick: () -> Unit) = label(texto, 13f, C_MUTED, medium = true).apply {
        minHeight = dp(36)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = ripple(rounded(C_BG, 18, C_BORDER), 18)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) }
    }

    private fun botonSuave(texto: String, onClick: () -> Unit) = label(texto, 15f, C_TEXT, medium = true).apply {
        minHeight = dp(48)
        gravity = Gravity.CENTER
        background = ripple(rounded(C_SURFACE_2, 14, C_BORDER), 14)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun tarjeta() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_SURFACE, 20, C_BORDER)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun linea() = View(this).apply {
        setBackgroundColor(C_BORDER)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
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
}
