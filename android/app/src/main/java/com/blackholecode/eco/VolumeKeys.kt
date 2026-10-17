package com.blackholecode.eco

import android.content.Context
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Doble toque a "subir volumen" = Guardar momento, también con la pantalla apagada y el
 * teléfono en la bolsa.
 *
 * Cómo: mientras Eco escucha, una sesión multimedia propia recibe los botones de volumen
 * (Android se los manda a la sesión activa). Dos toques seguidos guardan el momento; un toque
 * suelto se devuelve enseguida al volumen normal, así que los botones siguen sirviendo.
 * Si suena música (Spotify, YouTube…), Eco suelta los botones para no estorbar, y los vuelve
 * a tomar 10 minutos después de que la música para.
 */
class VolumeKeys(private val context: Context, private val onDoubleUp: () -> Unit) {

    companion object {
        /** Tiempo máximo entre los dos toques. */
        private const val DOBLE_MS = 450L
        /** Más rápido que esto no es una persona tocando dos veces: es el botón sostenido (repetición). */
        private const val REPETICION_MS = 120L
        private const val PAUSA_POR_MUSICA_MS = 10 * 60_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var session: MediaSession? = null
    private var activo = false
    private var musicaHasta = 0L
    private var ultimoArriba = 0L
    private var esperandoSegundo = false

    /** Toque suelto: pasado el tiempo del doble toque, sube el volumen como siempre. */
    private val subirSuelto = Runnable {
        esperandoSegundo = false
        ajustar(AudioManager.ADJUST_RAISE)
    }

    private val proveedor = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
        override fun onAdjustVolume(direction: Int) {
            main.post { recibir(direction) }
        }
    }

    private fun recibir(direction: Int) {
        if (direction < 0) {
            ajustar(AudioManager.ADJUST_LOWER)
            return
        }
        if (direction == 0) return
        val ahora = SystemClock.uptimeMillis()
        val desde = ahora - ultimoArriba
        ultimoArriba = ahora
        when {
            desde < REPETICION_MS -> {
                // Botón sostenido: es para subir el volumen, no un doble toque.
                if (esperandoSegundo) {
                    main.removeCallbacks(subirSuelto)
                    esperandoSegundo = false
                    ajustar(AudioManager.ADJUST_RAISE)
                }
                ajustar(AudioManager.ADJUST_RAISE)
            }
            esperandoSegundo && desde <= DOBLE_MS -> {
                main.removeCallbacks(subirSuelto)
                esperandoSegundo = false
                ultimoArriba = 0L
                onDoubleUp()
            }
            else -> {
                esperandoSegundo = true
                main.postDelayed(subirSuelto, DOBLE_MS)
            }
        }
    }

    private fun ajustar(direccion: Int) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direccion, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {
        }
    }

    /** Se llama cada segundo desde el servicio. */
    fun actualizar(escuchando: Boolean, habilitado: Boolean) {
        val ahora = SystemClock.uptimeMillis()
        if (audio.isMusicActive) musicaHasta = ahora + PAUSA_POR_MUSICA_MS
        val quiere = escuchando && habilitado && ahora > musicaHasta
        if (quiere == activo) return
        activo = quiere
        if (quiere) {
            val s = session ?: MediaSession(context, "eco-volumen").also { session = it }
            s.setPlaybackToRemote(proveedor)
            s.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            s.isActive = true
        } else {
            if (esperandoSegundo) {
                main.removeCallbacks(subirSuelto)
                esperandoSegundo = false
            }
            session?.let {
                it.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
                it.isActive = false
            }
        }
    }

    fun cerrar() {
        main.removeCallbacks(subirSuelto)
        session?.release()
        session = null
        activo = false
    }
}
