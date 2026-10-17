package com.blackholecode.eco

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.min

/**
 * Doble toque a "subir volumen" = Guardar momento, también con la pantalla apagada y el
 * teléfono en la bolsa.
 *
 * Por qué así: con la pantalla apagada Android solo hace caso a los botones de volumen si hay
 * audio multimedia sonando. Mientras Eco escucha, reproduce silencio absoluto (ceros) en bucle;
 * con eso cada toque mueve el volumen multimedia y Eco ve el cambio. Exactamente dos subidas
 * rápidas = guardar momento, y el volumen vuelve a donde estaba. Un toque, tres o el botón
 * sostenido son volumen normal.
 *
 * (La versión 1.3 usaba una "sesión multimedia remota". Android 12+ la ignora en muchos
 * teléfonos, entre ellos los Honor: por eso no reaccionaba.)
 *
 * Si el volumen multimedia está al máximo no hay hacia dónde subir y no se vería ningún toque,
 * así que Eco lo baja 2 pasos mientras no suena nada y te los devuelve en cuanto pones música.
 * Todo corre en el hilo principal.
 */
class VolumeKeys(private val context: Context, private val onDoubleUp: () -> Unit) {

    companion object {
        /** Máximo entre el primer y el segundo toque (el botón sostenido repite a los ~400 ms). */
        private const val DOBLE_MS = 380L
        /** Silencio que cierra la serie de toques (si llega un tercero, no era doble toque). */
        private const val SERIE_MS = 450L
        /** Cambios de volumen que hace Eco mismo: no son toques tuyos. */
        private const val PROPIO_MS = 400L
        /** Después de que para la música, espera esto antes de volver a tomar los botones. */
        private const val TRAS_MUSICA_MS = 1_500L
        /** Pasos libres que hacen falta por encima del volumen actual para ver dos toques. */
        private const val MARGEN_PASOS = 2

        private const val ACCION_VOLUMEN = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_TIPO = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val MUSICA = AudioManager.STREAM_MUSIC
        private const val HZ = 16_000

        /** Bluetooth clásico, audífonos para sordera, BLE audífonos/parlante/difusión. */
        private val TIPOS_BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 23, 26, 27, 30)
        /** Audífonos de cable o USB. */
        private val TIPOS_CABLE = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = Prefs(context)

    private var escuchando = false
    private var habilitado = false
    /** Eco tiene los botones: el silencio suena y los toques cuentan. */
    private var activo = false
    private var escuchasPuestas = false

    private var pista: AudioTrack? = null
    private var pistaPorAltavoz = false
    private var salida = ""
    private var musicaVistaMs = 0L

    private var conocido = -1
    // Casi todos los teléfonos tienen 15–25 niveles y un toque = 1 nivel; con más niveles se aprende.
    private val serie = SerieDeToques(
        DOBLE_MS,
        pasoFijo = if ((try { audio.getStreamMaxVolume(MUSICA) } catch (_: Exception) { 15 }) <= 30) 1 else 0,
    )
    private var propioHasta = 0L

    init {
        // Si Eco se cerró de golpe con pasos de volumen prestados, los devuelve.
        devolverVolumen()
        EcoState.volumenEstado = "apagado"
    }

    // ───────────────────────── Lo que llama el servicio ─────────────────────────

    /** Se llama cada segundo desde el servicio. */
    fun actualizar(escuchando: Boolean, habilitado: Boolean) {
        this.escuchando = escuchando
        this.habilitado = habilitado
        revisar()
    }

    fun cerrar() {
        escuchando = false
        habilitado = false
        if (activo) apagar() else devolverVolumen()
        ponerEscuchas(false)
        EcoState.volumenEstado = "apagado"
    }

    // ───────────────────────── Decidir si Eco toma los botones ─────────────────────────

    private fun revisar() {
        val quiere = escuchando && habilitado
        ponerEscuchas(quiere)
        val ahora = SystemClock.uptimeMillis()
        val llamada = audio.mode != AudioManager.MODE_NORMAL
        val musica = quiere && !llamada && suenaOtraCosa()
        if (musica) musicaVistaMs = ahora
        val tomar = quiere && !llamada && !musica && ahora - musicaVistaMs > TRAS_MUSICA_MS

        if (tomar != activo) {
            if (tomar) encender() else apagar()
        } else if (activo && serie.vacia) {
            conocido = volumenActual() // por si cambió sin aviso
            if (pista?.playState != AudioTrack.PLAYSTATE_PLAYING) reproducir()
        }

        EcoState.volumenEstado = when {
            !habilitado -> "apagado"
            !escuchando -> "espera"
            llamada -> "llamada"
            musica || (quiere && !activo) -> "musica"
            pista == null -> "sin_audio"
            else -> "listo"
        }
    }

    private fun encender() {
        activo = true
        serie.cortar()
        reproducir()
        conocido = volumenActual()
        dejarMargen()
    }

    private fun apagar() {
        activo = false
        main.removeCallbacks(cerrarSerie)
        serie.cortar()
        soltarPista()
        devolverVolumen()
    }

    /** ¿Suena música, un video o un juego de otra app? (lo nuestro no cuenta). */
    private fun suenaOtraCosa(): Boolean = try {
        // A las apps normales Android solo les muestra lo que está sonando de verdad.
        audio.activePlaybackConfigurations.any { esDeOtraApp(it) }
    } catch (_: Exception) {
        false
    }

    private fun esDeOtraApp(c: AudioPlaybackConfiguration): Boolean {
        val a = c.audioAttributes
        val nuestra = a.usage == AudioAttributes.USAGE_MEDIA &&
            a.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION &&
            (Build.VERSION.SDK_INT < 29 || a.allowedCapturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        if (nuestra) return false
        return a.usage == AudioAttributes.USAGE_MEDIA || a.usage == AudioAttributes.USAGE_GAME ||
            a.usage == AudioAttributes.USAGE_UNKNOWN
    }

    // ───────────────────────── Avisos de Android ─────────────────────────

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACCION_VOLUMEN -> if (i.getIntExtra(EXTRA_TIPO, -1) == MUSICA) cambioDeVolumen()
                // Con la pantalla apagada nadie ve el volumen: buen momento para dejar margen.
                Intent.ACTION_SCREEN_OFF -> if (activo && serie.vacia) dejarMargen()
            }
        }
    }

    private val alSonar = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = revisar()
    }

    private val alCambiarSalida = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = salidaCambio()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = salidaCambio()
    }

    private fun ponerEscuchas(si: Boolean) {
        if (si == escuchasPuestas) return
        escuchasPuestas = si
        if (si) {
            salida = tipoDeSalida()
            val filtro = IntentFilter(ACCION_VOLUMEN).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            try {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receptor, filtro, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(receptor, filtro)
            } catch (_: Exception) {
            }
            try { audio.registerAudioPlaybackCallback(alSonar, main) } catch (_: Exception) {}
            try { audio.registerAudioDeviceCallback(alCambiarSalida, main) } catch (_: Exception) {}
        } else {
            try { context.unregisterReceiver(receptor) } catch (_: Exception) {}
            try { audio.unregisterAudioPlaybackCallback(alSonar) } catch (_: Exception) {}
            try { audio.unregisterAudioDeviceCallback(alCambiarSalida) } catch (_: Exception) {}
        }
    }

    /** Conectaste o quitaste audífonos: cada salida tiene su propio volumen. */
    private fun salidaCambio() {
        val nueva = tipoDeSalida()
        if (nueva == salida) return
        salida = nueva
        // Lo prestado era del volumen de la otra salida: devolverlo aquí subiría el equivocado.
        prefs.volumenPrestado = 0
        if (!activo) return
        main.removeCallbacks(cerrarSerie)
        serie.cortar()
        reproducir()
        conocido = volumenActual()
        dejarMargen()
    }

    private fun tipoDeSalida(): String {
        val tipos = try {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        } catch (_: Exception) {
            emptyList()
        }
        return when {
            tipos.any { it in TIPOS_BLUETOOTH } -> "bluetooth"
            tipos.any { it in TIPOS_CABLE } -> "cable"
            else -> "altavoz"
        }
    }

    // ───────────────────────── Contar toques ─────────────────────────

    private fun cambioDeVolumen() {
        val v = volumenActual()
        val antes = conocido
        conocido = v
        if (!activo || antes < 0 || v < 0 || v == antes) return
        val ahora = SystemClock.uptimeMillis()
        if (ahora < propioHasta) return
        main.removeCallbacks(cerrarSerie)
        if (!serie.cambio(ahora, antes, v)) return // bajaste el volumen: se corta la serie
        EcoState.volumenToqueMs = System.currentTimeMillis()
        EcoState.volumenToques = serie.cuantos
        main.postDelayed(cerrarSerie, SERIE_MS)
    }

    private val cerrarSerie = Runnable {
        val volver = serie.volumenInicial
        if (serie.cerrar() && activo) {
            fijarVolumen(volver)
            EcoState.volumenDobleMs = System.currentTimeMillis()
            onDoubleUp()
        }
    }

    // ───────────────────────── Volumen ─────────────────────────

    private fun volumenActual(): Int = try {
        audio.getStreamVolume(MUSICA)
    } catch (_: Exception) {
        -1
    }

    private fun fijarVolumen(v: Int) {
        propioHasta = SystemClock.uptimeMillis() + PROPIO_MS
        try {
            audio.setStreamVolume(MUSICA, v, 0)
        } catch (_: Exception) {
        }
        conocido = volumenActual()
    }

    /** Deja al menos dos pasos libres hacia arriba para poder ver el doble toque. */
    private fun dejarMargen() {
        if (!activo) return
        val max = try { audio.getStreamMaxVolume(MUSICA) } catch (_: Exception) { return }
        val tope = max - MARGEN_PASOS * serie.paso
        val v = volumenActual()
        if (tope < 1 || v <= tope) return
        prefs.volumenPrestado = prefs.volumenPrestado + (v - tope)
        fijarVolumen(tope)
    }

    /** Devuelve los pasos prestados (al sonar música, al pausar o al apagar Eco). */
    private fun devolverVolumen() {
        val prestado = prefs.volumenPrestado
        if (prestado <= 0) return
        prefs.volumenPrestado = 0
        val v = volumenActual()
        if (v < 0) return
        val max = try { audio.getStreamMaxVolume(MUSICA) } catch (_: Exception) { return }
        fijarVolumen(min(max, v + prestado))
    }

    // ───────────────────────── El silencio ─────────────────────────

    private fun reproducir() {
        // Con Bluetooth el silencio va al altavoz del teléfono, para no tener tus audífonos
        // "reproduciendo" todo el día (y gastando su batería).
        val porAltavoz = salida == "bluetooth"
        val actual = pista
        if (actual != null && (pistaPorAltavoz != porAltavoz || actual.state != AudioTrack.STATE_INITIALIZED)) soltarPista()
        if (pista == null) {
            pista = crearPista(porAltavoz)
            pistaPorAltavoz = porAltavoz
        }
        val p = pista ?: return
        try {
            if (p.playState != AudioTrack.PLAYSTATE_PLAYING) p.play()
        } catch (_: Exception) {
            soltarPista()
        }
    }

    private fun crearPista(porAltavoz: Boolean): AudioTrack? {
        val cuadros = HZ / 2 // medio segundo de ceros, en bucle sin fin
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        // Esta combinación es la "firma" de Eco: así no confunde su silencio con tu música.
        if (Build.VERSION.SDK_INT >= 29) atributos.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(atributos.build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(HZ)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(cuadros * 2)
                .build()
        } catch (_: Exception) {
            return null
        }
        return try {
            t.write(ShortArray(cuadros), 0, cuadros)
            t.setLoopPoints(0, cuadros, -1)
            if (porAltavoz) altavoz()?.let { t.setPreferredDevice(it) }
            if (t.state == AudioTrack.STATE_INITIALIZED) t else {
                t.release()
                null
            }
        } catch (_: Exception) {
            t.release()
            null
        }
    }

    private fun altavoz(): AudioDeviceInfo? = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    } catch (_: Exception) {
        null
    }

    private fun soltarPista() {
        val p = pista ?: return
        pista = null
        try { p.stop() } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
    }
}

/**
 * Cuenta las subidas de volumen de una serie y decide si fue un doble toque.
 * No usa nada de Android, así que se puede probar en la computadora.
 */
internal class SerieDeToques(private val dobleMs: Long, private val pasoFijo: Int = 1) {
    private val toques = ArrayList<Long>()

    /**
     * Cuánto mueve un toque el volumen. Con [pasoFijo] = 0 se aprende: el salto más chico visto
     * (para teléfonos raros con muchos niveles, donde un toque mueve varios).
     */
    var paso = if (pasoFijo > 0) pasoFijo else 1
        private set
    private var pasoVisto = pasoFijo > 0

    /** Volumen antes del primer toque de la serie (a donde se vuelve tras un doble toque). */
    var volumenInicial = 0
        private set

    val vacia get() = toques.isEmpty()
    val cuantos get() = toques.size

    /** Un cambio de volumen de [antes] a [ahoraVol]. Devuelve false si fue una bajada (corta la serie). */
    fun cambio(ms: Long, antes: Int, ahoraVol: Int): Boolean {
        val delta = ahoraVol - antes
        if (delta == 0) return true
        if (delta < 0) {
            toques.clear()
            return false
        }
        if (!pasoVisto || delta < paso) {
            paso = delta
            pasoVisto = true
        }
        // Si Android juntó dos toques en un solo aviso, el salto es de dos pasos.
        val n = ((delta + paso / 2) / paso).coerceIn(1, 3)
        if (toques.isEmpty()) volumenInicial = antes
        repeat(n) { toques.add(ms) }
        return true
    }

    /** Cierra la serie: true si fueron exactamente dos toques lo bastante seguidos. */
    fun cerrar(): Boolean {
        val doble = toques.size == 2 && toques[1] - toques[0] <= dobleMs
        toques.clear()
        return doble
    }

    fun cortar() = toques.clear()
}
