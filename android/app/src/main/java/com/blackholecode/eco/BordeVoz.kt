package com.blackholecode.eco

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * El borde azul difuminado que aparece alrededor de la pantalla cuando Eco te escucha decir
 * «Eco, …», y se va cuando te callas. Va encima de cualquier app y no bloquea los toques.
 *
 * Necesita «Mostrar sobre otras apps». Si no lo diste pero activaste el envío automático por
 * WhatsApp (servicio de accesibilidad), usa ese permiso; si no hay ninguno, solo vibra.
 */
object BordeVoz {
    private val main = Handler(Looper.getMainLooper())
    private var vista: Brillo? = null
    private var wm: WindowManager? = null
    private val ocultarSolo: Runnable = Runnable { ocultarYa() }

    /** ¿Se puede dibujar el borde? */
    fun disponible(ctx: Context): Boolean =
        Settings.canDrawOverlays(ctx) || EnvioWhatsapp.servicio != null

    fun mostrar(ctx: Context) {
        main.post { mostrarYa(ctx) }
    }

    private fun mostrarYa(ctx: Context) {
        main.removeCallbacks(ocultarSolo)
        main.postDelayed(ocultarSolo, (DetectorVoz.ORDEN_MAX_S + 5) * 1000L)  // por si nunca llega el final
        vista?.let { it.entrar(); return }
        val (contexto, tipo) = when {
            Settings.canDrawOverlays(ctx) -> ctx.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            EnvioWhatsapp.servicio != null -> EnvioWhatsapp.servicio!! to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else -> return
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, tipo,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Eco te escucha"
            // Android 12+: una capa encima de otra app deja pasar los toques solo si es bastante transparente.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0  // también sobre la barra de arriba y la de abajo
            }
        }
        val gestor = contexto.getSystemService(WindowManager::class.java) ?: return
        val nueva = Brillo(contexto)
        try {
            gestor.addView(nueva, p)
        } catch (_: Exception) {
            return  // Android no dejó dibujar (permiso quitado): queda la vibración
        }
        vista = nueva
        wm = gestor
        nueva.entrar()
    }

    fun ocultar() {
        main.post { ocultarYa() }
    }

    private fun ocultarYa() {
        main.removeCallbacks(ocultarSolo)
        val v = vista ?: return
        v.salir {
            try {
                wm?.removeView(v)
            } catch (_: Exception) {
            }
            if (vista === v) {
                vista = null
                wm = null
            }
        }
    }

    /**
     * El brillo: un degradado suave desde cada orilla hacia adentro (azul intenso → azul claro →
     * transparente), con las esquinas en degradado redondo para que no se note la unión.
     */
    private class Brillo(ctx: Context) : View(ctx) {
        private val g = 44f * resources.displayMetrics.density   // qué tan adentro llega el brillo
        private val posiciones = floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f)
        private val colores = intArrayOf(
            0xF22F6BFF.toInt(), 0x9E4A86FF.toInt(), 0x476A9FFF.toInt(), 0x147FB2FF, 0x007FB2FF,
        )
        private val coloresAlReves = colores.reversedArray()
        private val posicionesAlReves = posiciones.map { 1f - it }.reversed().toFloatArray()
        private val bordes = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }
        private val esquinas = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }
        private var visible = 0f      // 0 → 1 al aparecer
        private var pulso = 0f        // 0 → 1 → 0 mientras escucha
        private var entrada: ValueAnimator? = null
        private val respiro = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pulso = it.animatedValue as Float; invalidate() }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val W = w.toFloat()
            val H = h.toFloat()
            fun lineal(x0: Float, y0: Float, x1: Float, y1: Float) =
                LinearGradient(x0, y0, x1, y1, colores, posiciones, Shader.TileMode.CLAMP)
            bordes[0].shader = lineal(0f, 0f, 0f, g)          // arriba
            bordes[1].shader = lineal(0f, H, 0f, H - g)       // abajo
            bordes[2].shader = lineal(0f, 0f, g, 0f)          // izquierda
            bordes[3].shader = lineal(W, 0f, W - g, 0f)       // derecha
            // En la esquina, el centro del degradado está hacia adentro: lo de afuera brilla más.
            val centros = arrayOf(floatArrayOf(g, g), floatArrayOf(W - g, g), floatArrayOf(g, H - g), floatArrayOf(W - g, H - g))
            centros.forEachIndexed { i, c ->
                esquinas[i].shader = RadialGradient(c[0], c[1], g, coloresAlReves, posicionesAlReves, Shader.TileMode.CLAMP)
            }
        }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun entrar() {
            entrada?.cancel()
            entrada = ValueAnimator.ofFloat(visible, 1f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { visible = it.animatedValue as Float; invalidate() }
                start()
            }
            if (!respiro.isStarted) respiro.start()
        }

        fun salir(listo: () -> Unit) {
            var hecho = false
            val terminar = {
                if (!hecho) {
                    hecho = true
                    respiro.cancel()
                    listo()
                }
            }
            entrada?.cancel()
            entrada = ValueAnimator.ofFloat(visible, 0f).apply {
                duration = 450
                addUpdateListener {
                    visible = it.animatedValue as Float
                    invalidate()
                    if (it.animatedFraction >= 1f) terminar()
                }
                start()
            }
            // Si las animaciones del sistema están apagadas, el animador termina al instante.
            if (!ValueAnimator.areAnimatorsEnabled()) terminar()
        }

        override fun onDraw(canvas: Canvas) {
            if (visible <= 0f) return
            val W = width.toFloat()
            val H = height.toFloat()
            val alfa = (255 * visible * (0.72f + 0.28f * pulso)).toInt().coerceIn(0, 255)
            bordes.forEach { it.alpha = alfa }
            esquinas.forEach { it.alpha = alfa }
            canvas.drawRect(g, 0f, W - g, g, bordes[0])
            canvas.drawRect(g, H - g, W - g, H, bordes[1])
            canvas.drawRect(0f, g, g, H - g, bordes[2])
            canvas.drawRect(W - g, g, W, H - g, bordes[3])
            canvas.drawRect(0f, 0f, g, g, esquinas[0])
            canvas.drawRect(W - g, 0f, W, g, esquinas[1])
            canvas.drawRect(0f, H - g, g, H, esquinas[2])
            canvas.drawRect(W - g, H - g, W, H, esquinas[3])
        }

        override fun onDetachedFromWindow() {
            respiro.cancel()
            entrada?.cancel()
            super.onDetachedFromWindow()
        }
    }
}
