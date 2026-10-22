package com.blackholecode.eco

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Envío automático por WhatsApp en una emergencia.
 *
 * WhatsApp no deja que otra app mande mensajes: solo abre el chat con el texto listo. Este servicio
 * de accesibilidad toca «Enviar» por ti, pero SOLO cuando la pantalla de emergencia lo "arma" para un
 * contacto, justo después de que mantuviste el botón rojo. Fuera de eso no hace nada: no lee tus
 * chats, no guarda nada y se apaga solo a los 20 segundos de cada envío.
 */
object EnvioWhatsapp {
    private const val LIMITE_MS = 20_000L

    /** Índice del contacto que se está enviando ahora (-1 = desarmado: el servicio no toca nada). */
    @Volatile var armado = -1
        private set
    @Volatile private var hasta = 0L
    @Volatile var servicio: EnvioWhatsappService? = null

    val enviados: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())
    val fallidos: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    fun armar(indice: Int) {
        armado = indice
        hasta = SystemClock.uptimeMillis() + LIMITE_MS
        servicio?.vigilarLimite()
    }

    fun vencido(): Boolean = armado >= 0 && SystemClock.uptimeMillis() > hasta

    fun desarmar() {
        armado = -1
    }

    fun reiniciar() {
        desarmar()
        enviados.clear()
        fallidos.clear()
    }

    /** ¿Activaste el servicio en Ajustes › Accesibilidad? */
    fun activo(context: Context): Boolean {
        val componente = ComponentName(context, EnvioWhatsappService::class.java)
        val activos = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return activos.split(':').any {
            it.equals(componente.flattenToString(), ignoreCase = true) ||
                it.equals(componente.flattenToShortString(), ignoreCase = true)
        }
    }
}

class EnvioWhatsappService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    private val revisarLimite = object : Runnable {
        override fun run() {
            val i = EnvioWhatsapp.armado
            if (i < 0) return
            if (EnvioWhatsapp.vencido()) {
                // No apareció el botón (¿ese número no tiene WhatsApp?): se sigue con el siguiente.
                EnvioWhatsapp.desarmar()
                EnvioWhatsapp.fallidos += i
                volverAEco()
            } else {
                intentar()
                main.postDelayed(this, 1_000)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        EnvioWhatsapp.servicio = this
    }

    override fun onDestroy() {
        if (EnvioWhatsapp.servicio === this) EnvioWhatsapp.servicio = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun vigilarLimite() {
        main.post {
            main.removeCallbacks(revisarLimite)
            main.postDelayed(revisarLimite, 1_000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (EnvioWhatsapp.armado >= 0) intentar()
    }

    override fun onInterrupt() {}

    /** Si el chat ya cargó el mensaje, toca Enviar una sola vez. */
    private fun intentar() {
        val i = EnvioWhatsapp.armado
        if (i < 0 || EnvioWhatsapp.vencido()) return
        val raiz = rootInActiveWindow ?: return
        val paquete = raiz.packageName?.toString() ?: return
        if (!paquete.startsWith("com.whatsapp")) return
        // Esperar a que el texto de la alerta esté en la caja (WhatsApp lo pone al abrir el chat).
        val caja = raiz.findAccessibilityNodeInfosByViewId("$paquete:id/entry").firstOrNull()
        if (caja != null && caja.text.isNullOrBlank()) return
        val boton = raiz.findAccessibilityNodeInfosByViewId("$paquete:id/send").firstOrNull { it.isEnabled }
            ?: buscarPorDescripcion(raiz)
            ?: return
        if (boton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            EnvioWhatsapp.desarmar()
            EnvioWhatsapp.enviados += i
            volverAEco()
        }
    }

    private fun buscarPorDescripcion(nodo: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = nodo.contentDescription?.toString()?.trim()?.lowercase()
        if ((desc == "enviar" || desc == "send") && nodo.isClickable && nodo.isEnabled) return nodo
        for (k in 0 until nodo.childCount) {
            val hijo = nodo.getChild(k) ?: continue
            buscarPorDescripcion(hijo)?.let { return it }
        }
        return null
    }

    /** De vuelta a la pantalla de emergencia, que abre el chat del siguiente contacto. */
    private fun volverAEco() {
        main.removeCallbacks(revisarLimite)
        main.postDelayed({
            try {
                startActivity(
                    Intent(this, EmergenciaActivity::class.java)
                        .putExtra(EmergenciaActivity.EXTRA_VOLVER, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (_: Exception) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, 900)
    }
}
