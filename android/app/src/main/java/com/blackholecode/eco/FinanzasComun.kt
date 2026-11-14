package com.blackholecode.eco

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lo que comparten las pantallas de Finanzas (y el selector «Eco | Finanzas» de la pantalla principal):
 * colores, el ícono de cada categoría, cómo se escribe el dinero y los dos gráficos, que se dibujan a mano
 * (sin librerías). Los datos (la copia guardada en el teléfono y lo que espera para subir) están en [FinLocal].
 */
internal object Fin {
    const val C_BG = 0xFF0A0A12.toInt()
    const val C_SURFACE = 0xFF14141F.toInt()
    const val C_SURFACE_2 = 0xFF1C1C2A.toInt()
    const val C_BORDER = 0xFF2A2A3D.toInt()
    const val C_TRACK = 0xFF2E2E44.toInt()
    const val C_TEXT = 0xFFEEEEF6.toInt()
    const val C_MUTED = 0xFFA3A3BA.toInt()
    const val C_FAINT = 0xFF8C8CA6.toInt()
    const val C_ACCENT = 0xFF6B5BF5.toInt()
    const val C_ACCENT_SOFT = 0xFFB8AEFF.toInt()
    const val C_WARN = 0xFFFFB547.toInt()
    const val C_REC = 0xFFFF5C7A.toInt()
    const val C_LIVE = 0xFF3DDC97.toInt()
    const val C_BARRA = 0xFF3A3A55.toInt()
    const val C_OTRAS = 0xFF5A5A72.toInt()
    const val BLANCO = 0xFFFFFFFF.toInt()

    /** Preferencias de Finanzas: la cuenta y el periodo que estabas viendo, y la cola de lo que falta subir. */
    const val LOCAL = "eco_finanzas"

    val MESES = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    private val ICONOS = mapOf(
        "comida" to "🛒", "restaurantes" to "🍔", "transporte" to "🚌", "educacion" to "🎓", "salud" to "💊",
        "trabajo" to "💼", "tecnologia" to "💻", "suscripciones" to "📺", "hogar" to "🏠", "ropa" to "👕",
        "ocio" to "🎮", "regalos" to "🎁", "recargas" to "📱", "deudas" to "💳", "publicidad" to "📣",
        "impuestos" to "📄", "otros" to "✨", "ahorro" to "🐷",
        "clientes" to "🤝", "sueldo" to "💵", "mesada" to "👪", "ventas" to "🛍", "regalo" to "🎁",
        "reembolso" to "🔄", "otros_ingresos" to "✨",
    )

    /** El dibujito de una categoría (va dentro de un círculo de su color; el nombre siempre va escrito). */
    fun icono(clave: String): String = ICONOS[clave] ?: "✨"

    /** Centavos → "$1,486.00" (con signo: "+$450.00" o "−$38.40"). */
    fun dinero(c: Long, moneda: String, signo: Boolean = false): String {
        val n = String.format(Locale.US, "%,.2f", abs(c) / 100.0)
        return (if (c < 0) "−" else if (signo && c > 0) "+" else "") + moneda + n
    }

    /** Para encima de las barras: 30800 → "308", 131538 → "1.3k". */
    fun dineroCorto(c: Long): String {
        val n = c / 100.0
        return when {
            n >= 10000 -> "${(n / 1000).roundToInt()}k"
            n >= 1000 -> String.format(Locale.US, "%.1fk", n / 1000).replace(".0k", "k")
            n >= 100 || c % 100 == 0L -> n.roundToInt().toString()
            else -> String.format(Locale.US, "%.2f", n)
        }
    }

    /** Centavos como se escriben en un campo: 2500 → "25.00". */
    fun plano(c: Long): String = String.format(Locale.US, "%.2f", c / 100.0)

    fun color(hex: String): Int = try { Color.parseColor(hex) } catch (_: Exception) { C_FAINT }

    /** La fecha de hoy menos esos días, como "2026-11-13". */
    fun isoHace(dias: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -dias)
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** "2026-11-13" → "13 nov" (o "hoy"; con el año si no es el de hoy). */
    fun fechaCorta(iso: String, hoy: String): String {
        if (iso.length < 10) return ""
        if (iso == hoy) return "hoy"
        val mes = iso.substring(5, 7).toIntOrNull() ?: return iso
        val dia = iso.substring(8, 10).toIntOrNull() ?: return iso
        val base = "$dia ${MESES[(mes - 1).coerceIn(0, 11)]}"
        return if (hoy.length >= 4 && iso.substring(0, 4) != hoy.substring(0, 4)) "$base ${iso.substring(0, 4)}" else base
    }

    // ── Piezas de interfaz que usan varias pantallas ──

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()

    fun rounded(c: Context, fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, radius).toFloat()
        if (stroke != null) setStroke(dp(c, 1), stroke)
    }

    fun ripple(c: Context, content: Drawable, radius: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(c, BLANCO, radius))

    fun label(c: Context, text: String, size: Float, color: Int, medium: Boolean = false) = TextView(c).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** El círculo de color con el ícono de una categoría. */
    fun circulo(c: Context, clave: String, color: Int, lado: Int): TextView = label(c, icono(clave), lado * 0.42f, BLANCO).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * El selector de arriba, igual que en el panel de la laptop: «Eco | Finanzas».
     * [enFinanzas] dice cuál está elegido; al tocar el otro se llama a [alCambiar].
     */
    fun selector(c: Context, enFinanzas: Boolean, alCambiar: () -> Unit): View {
        val caja = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(c, C_SURFACE, 24, C_BORDER)
            setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4))
        }
        for ((nombre, esFinanzas) in listOf("Eco" to false, "Finanzas" to true)) {
            val activo = esFinanzas == enFinanzas
            val v = label(c, nombre, 15f, if (activo) C_TEXT else C_MUTED, medium = true).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(c, 44)
                setPadding(dp(c, 24), 0, dp(c, 24), 0)
                background = ripple(c, rounded(c, if (activo) C_SURFACE_2 else C_SURFACE, 20, if (activo) C_BORDER else null), 20)
                isClickable = true
                isFocusable = true
                contentDescription = if (activo) "$nombre, seleccionado" else "Ir a $nombre"
                if (!activo) setOnClickListener { alCambiar() }
            }
            caja.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        return caja
    }
}

/** La dona de gastos (o ingresos) por categoría: un arco por categoría, con un huequito entre uno y otro. */
internal class DonaView(context: Context) : View(context) {
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
        val grosor = lado * 0.15f
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
internal class BarrasView(context: Context) : View(context) {
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
                barra.color = if (esActual) Fin.C_ACCENT else Fin.C_BARRA
                forma.set(cx - ancho / 2f, base - alto, cx + ancho / 2f, base)
                canvas.drawRoundRect(forma, radio, radio, barra)
                // La base va recta: solo la punta de arriba es redondeada.
                canvas.drawRect(cx - ancho / 2f, base - minOf(alto, radio), cx + ancho / 2f, base, barra)
            }
            letra.color = if (esActual) Fin.C_TEXT else Fin.C_FAINT
            letra.isFakeBoldText = esActual
            if (i < etiquetas.size) canvas.drawText(etiquetas[i], cx, height - renglon * 0.3f, letra)
            if (i < textos.size && textos[i].isNotEmpty()) canvas.drawText(textos[i], cx, base - alto - 5f * d, letra)
        }
    }
}
