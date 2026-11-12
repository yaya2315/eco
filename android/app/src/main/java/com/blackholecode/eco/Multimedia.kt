package com.blackholecode.eco

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray

/**
 * Vigila si otra app (YouTube, TikTok, música, un juego) está sonando por el altavoz del teléfono.
 *
 * El micrófono de Eco oye ese altavoz como si fuera una conversación. Mientras suena, el servicio
 * graba silencio en lugar de lo que oye el micrófono, y aquí se apuntan los tramos para que la
 * laptop también deje fuera lo que cayó dentro (por ejemplo en el modo reunión, que graba con otro
 * micrófono). Con audífonos puestos no hace nada: el micrófono no los oye.
 */
class Multimedia(context: Context, private val alCambiar: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** [desde, hasta] en milisegundos de reloj; hasta = 0 mientras sigue sonando. */
    private val lista = ArrayList<LongArray>()
    private var activo = false

    /** true mientras otra app suena por el altavoz del teléfono. */
    @Volatile
    var sonando = false
        private set

    private val alSonar = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = revisar()
    }

    // Por si Android no avisa (o conectas audífonos con algo sonando): se revisa cada pocos segundos.
    private val vigilante = object : Runnable {
        override fun run() {
            if (!activo) return
            revisar()
            main.postDelayed(this, REVISAR_MS)
        }
    }

    fun iniciar() {
        if (activo) return
        activo = true
        try { audio.registerAudioPlaybackCallback(alSonar, main) } catch (_: Exception) {}
        revisar()
        main.postDelayed(vigilante, REVISAR_MS)
    }

    fun detener() {
        if (!activo) return
        activo = false
        main.removeCallbacks(vigilante)
        try { audio.unregisterAudioPlaybackCallback(alSonar) } catch (_: Exception) {}
        poner(false)
    }

    /** Tramos de [desdeMs, hastaMs] en que sonó otra app, como [[desde, hasta], …] (vacío si ninguno). */
    fun tramosEntre(desdeMs: Long, hastaMs: Long): JSONArray {
        val salida = JSONArray()
        val ahora = System.currentTimeMillis()
        synchronized(lista) {
            for (t in lista) {
                val fin = if (t[1] == 0L) ahora else t[1]
                if (fin <= desdeMs || t[0] >= hastaMs) continue
                salida.put(JSONArray().put(maxOf(t[0], desdeMs)).put(minOf(fin, hastaMs)))
            }
        }
        return salida
    }

    private fun revisar() {
        val suena = try {
            !conAudifonos() && audio.activePlaybackConfigurations.any { esDeOtraApp(it) }
        } catch (_: Exception) {
            false
        }
        poner(suena)
    }

    private fun poner(suena: Boolean) {
        val cambio = suena != sonando
        val ahora = System.currentTimeMillis()
        synchronized(lista) {
            if (cambio) {
                if (suena) lista.add(longArrayOf(ahora, 0L))
                else lista.lastOrNull()?.let { if (it[1] == 0L) it[1] = ahora }
            }
            lista.removeAll { it[1] != 0L && it[1] < ahora - GUARDAR_MS }
        }
        sonando = suena
        EcoState.multimedia = suena
        if (cambio) alCambiar()
    }

    /** ¿Hay audífonos o un parlante Bluetooth/USB? Entonces el sonido no sale por el altavoz del teléfono. */
    private fun conAudifonos(): Boolean {
        val tipos = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val extra = if (Build.VERSION.SDK_INT >= 31) intArrayOf(AudioDeviceInfo.TYPE_BLE_HEADSET) else intArrayOf()
        return tipos.any { it in AUDIFONOS || it in extra }
    }

    /** Música, video o juego de otra app. El silencio propio de Eco (para los botones de volumen) no cuenta. */
    private fun esDeOtraApp(c: AudioPlaybackConfiguration): Boolean {
        val a = c.audioAttributes
        val nuestra = a.usage == AudioAttributes.USAGE_MEDIA &&
            a.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION &&
            (Build.VERSION.SDK_INT < 29 || a.allowedCapturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        if (nuestra) return false
        return a.usage == AudioAttributes.USAGE_MEDIA || a.usage == AudioAttributes.USAGE_GAME ||
            a.usage == AudioAttributes.USAGE_UNKNOWN
    }

    companion object {
        private const val REVISAR_MS = 3_000L
        private const val GUARDAR_MS = 12L * 60 * 60 * 1000 // los tramos se guardan 12 horas
        private val AUDIFONOS = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
