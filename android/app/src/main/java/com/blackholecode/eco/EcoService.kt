package com.blackholecode.eco

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.min

/**
 * Corazón de Eco: mantiene el micrófono abierto y guarda los últimos minutos
 * en un búfer circular EN MEMORIA que se sobrescribe solo. Nada toca el disco
 * hasta que tú pides "Guardar momento"; entonces se escribe un .wav con lo de
 * antes + lo de después, y se sube a tu laptop en cuanto esté al alcance.
 */
class EcoService : Service() {

    companion object {
        const val ACTION_START = "com.blackholecode.eco.START"
        const val ACTION_SAVE = "com.blackholecode.eco.SAVE"
        const val ACTION_PAUSE = "com.blackholecode.eco.PAUSE"
        const val ACTION_RELOAD = "com.blackholecode.eco.RELOAD"
        const val ACTION_UPLOAD = "com.blackholecode.eco.UPLOAD"
        const val ACTION_STOP = "com.blackholecode.eco.STOP"
        const val ACTION_REUNION_INICIAR = "com.blackholecode.eco.REUNION_INICIAR"
        const val ACTION_REUNION_TERMINAR = "com.blackholecode.eco.REUNION_TERMINAR"

        /** Modo reunión: AAC mono a 32 kbps (~15 MB por hora), una parte cada 10 minutos. */
        private const val REUNION_PARTE_MS = 10 * 60_000L
        private const val REUNION_MAX_MS = 8 * 60 * 60_000L

        const val SAMPLE_RATE = 16_000
        private const val CH_ACTIVE = "eco_activo"
        const val CH_ALERTS = "eco_avisos"
        private const val NOTIF_ID = 7
        private const val NOTIF_STOPPED = 8
        private const val NOTIF_UPDATE = 9
        const val NOTIF_UPDATED = 10
        private const val UPLOAD_EVERY_MS = 120_000L
        /** Cada cuántas vueltas de subida (de 2 min) se pregunta a la laptop si hay app nueva: 3 h. */
        private const val UPDATE_EVERY_ROUNDS = 90
        /** Cada cuántas vueltas se avisa a la laptop "sigo vivo": 10 min. */
        private const val LATIDO_EVERY_ROUNDS = 5
        private const val ACCENT = 0xFF6B5BF5.toInt()

        /** Pausa automática por batería: a este % sin cargador se pausa; vuelve al cargar o al pasar de 25 %. */
        const val BATERIA_PAUSA = 20
        const val BATERIA_REANUDAR = 25

        fun send(context: Context, action: String) {
            val intent = Intent(context, EcoService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_REUNION_INICIAR) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun pendingDir(context: Context): File = File(context.filesDir, "pendientes").apply { mkdirs() }
        fun meetingDir(context: Context): File = File(context.filesDir, "reunion").apply { mkdirs() }

        fun isUploadable(f: File) = f.name.endsWith(".wav") || f.name.endsWith(".m4a")

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_ACTIVE, "Eco activo", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aviso fijo mientras Eco tiene el micrófono abierto"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERTS, "Avisos de Eco", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Cuando Eco se detiene, se actualiza o necesita que lo reactives"
                }
            )
        }
    }

    private class Clip(val pcm: ShortArray, val startMs: Long, val location: Location?)

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    // Búfer circular. Solo se toca con `lock` tomado.
    private val lock = Any()
    private var ring = ShortArray(0)
    private var total = 0L          // muestras escritas desde que se abrió el micrófono
    private var saveStart = -1L     // rango del momento pendiente [saveStart, saveEnd)
    private var saveEnd = -1L
    private var activeBefore = Prefs.DEFAULT_BEFORE
    private var activeAfter = Prefs.DEFAULT_AFTER

    @Volatile private var capturing = false
    private var recorder: AudioRecord? = null
    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastLocation: Location? = null
    private var tickCount = 0

    // Modo reunión
    private var grabadora: MediaRecorder? = null
    private var reunionClave = ""
    private var parteArchivo: File? = null
    private var parteInicioMs = 0L
    private var estabaEnPausa = false

    // Batería, actualizaciones, latido y botón de volumen
    private var bateriaRegistrada = false
    private var uploadRounds = 0
    private var volumen: VolumeKeys? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels(this)
        volumen = VolumeKeys(this) { requestSave() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            // Android reinició el servicio después de cerrarlo. Desde segundo plano no se
            // puede reabrir el micrófono, así que avisamos y esperamos a que lo reactives.
            notifyStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!EcoState.running && (action == ACTION_SAVE || action == ACTION_PAUSE)) {
            // Se tocó el widget o la notificación, pero Android ya había cerrado Eco.
            notifyStopped()
            EcoWidget.refresh(this)
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> startCapture()
            ACTION_SAVE -> requestSave()
            ACTION_PAUSE -> togglePause()
            ACTION_RELOAD -> reload()
            ACTION_UPLOAD -> uploadOnce()
            ACTION_REUNION_INICIAR -> startMeeting()
            ACTION_REUNION_TERMINAR -> stopMeeting()
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        if (!EcoState.running && action != ACTION_UPLOAD) stopSelf()
        return if (EcoState.running) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (EcoState.reunionInicioMs > 0) {
            main.removeCallbacks(rotarParte)
            closePart(final = true)
            EcoState.reunionInicioMs = 0
        }
        if (capturing) stopRecorder(flushSave = true)
        EcoState.running = false
        EcoState.paused = false
        EcoState.pausaBateria = false
        unregisterBattery()
        volumen?.cerrar()
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        EcoWidget.refresh(this)
        super.onDestroy()
    }

    // ───────────────────────── Ciclo de vida ─────────────────────────

    private fun startCapture() {
        prefs.activo = true
        if (EcoState.running) {
            if (!EcoState.paused && !capturing && EcoState.reunionInicioMs == 0L) startRecorder()
            refreshNotification()
            return
        }
        if (!ensureForeground()) return
        startRecorder()
        refreshNotification()
    }

    /** Pone a Eco en primer plano (notificación fija) si todavía no lo está. */
    private fun ensureForeground(): Boolean {
        if (EcoState.running) return true
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            EcoState.micError = "Falta el permiso del micrófono"
            return false
        }
        try {
            goForeground()
        } catch (e: Exception) {
            EcoState.micError = "Android no dejó iniciar Eco (${e.javaClass.simpleName})"
            return false
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_STOPPED)
        EcoState.running = true
        EcoState.paused = false
        EcoState.pausaBateria = false
        registerBattery()
        main.removeCallbacks(tick)
        main.post(tick)
        main.removeCallbacks(uploadLoop)
        main.postDelayed(uploadLoop, 3_000)
        return true
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasLocationPermission()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun togglePause() {
        if (!EcoState.running || EcoState.reunionInicioMs > 0) return
        if (EcoState.paused) {
            if (EcoState.pausaBateria) {
                // Reanudaste a mano con poca batería: respetamos tu decisión hasta que cargues.
                EcoState.pausaBateria = false
                EcoState.bateriaPermitida = true
            }
            EcoState.paused = false
            startRecorder()
        } else {
            stopRecorder(flushSave = true)
            EcoState.paused = true
            EcoState.pausaBateria = false
        }
        refreshNotification()
    }

    /** Aplica nuevos segundos de antes/después sin apagar Eco. */
    private fun reload() {
        if (!EcoState.running || EcoState.paused || EcoState.reunionInicioMs > 0) return
        stopRecorder(flushSave = true)
        startRecorder()
        refreshNotification()
    }

    private fun shutdown() {
        prefs.activo = false
        if (EcoState.reunionInicioMs > 0) stopMeeting()
        stopRecorder(flushSave = true)
        EcoState.running = false
        EcoState.paused = false
        EcoState.pausaBateria = false
        unregisterBattery()
        main.removeCallbacks(tick)
        main.removeCallbacks(uploadLoop)
        stopForeground(STOP_FOREGROUND_REMOVE)
        volumen?.actualizar(escuchando = false, habilitado = false)
        EcoWidget.refresh(this)
        runIo {
            uploadPending()
            latido("apagado")
        }
        stopSelf()
    }

    // ───────────────────────── Batería ─────────────────────────

    private val bateria = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBatteryIntent(intent)
    }

    private fun registerBattery() {
        if (bateriaRegistrada) return
        val filtro = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bateria, filtro, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(bateria, filtro)
        bateriaRegistrada = true
    }

    private fun unregisterBattery() {
        if (!bateriaRegistrada) return
        try { unregisterReceiver(bateria) } catch (_: Exception) {}
        bateriaRegistrada = false
    }

    /** Lee la batería ahora mismo (el aviso de Android solo llega cuando cambia). */
    private fun checkBatteryNow() {
        val actual = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        onBatteryIntent(actual)
    }

    private fun onBatteryIntent(intent: Intent) {
        val nivel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (nivel < 0 || escala <= 0) return
        onBattery(nivel * 100 / escala, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    private fun onBattery(pct: Int, cargando: Boolean) {
        val cambio = pct != EcoState.batteryPct || cargando != EcoState.charging
        EcoState.batteryPct = pct
        EcoState.charging = cargando
        if (!EcoState.running) return
        val recuperada = cargando || pct >= BATERIA_REANUDAR
        if (recuperada) EcoState.bateriaPermitida = false
        if (EcoState.pausaBateria) {
            if (recuperada) {
                EcoState.pausaBateria = false
                if (EcoState.paused && EcoState.reunionInicioMs == 0L) {
                    EcoState.paused = false
                    startRecorder()
                }
                refreshNotification()
            }
            return
        }
        val listening = !EcoState.paused && EcoState.reunionInicioMs == 0L
        if (listening && !cargando && pct <= BATERIA_PAUSA && !EcoState.bateriaPermitida) {
            stopRecorder(flushSave = true)  // si estabas guardando un momento, se guarda lo que había
            EcoState.paused = true
            EcoState.pausaBateria = true
            refreshNotification()
        } else if (cambio && EcoState.reunionInicioMs > 0) {
            refreshNotification()  // la reunión sigue; solo actualizamos el aviso de batería
        }
    }

    // ───────────────────────── Micrófono ─────────────────────────

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        if (capturing) return
        activeBefore = prefs.beforeSec
        activeAfter = prefs.afterSec
        EcoState.beforeSec = activeBefore
        synchronized(lock) {
            // Espacio para "antes" + "después" + 5 s de margen.
            ring = ShortArray((activeBefore + activeAfter + 5) * SAMPLE_RATE)
            total = 0
            saveStart = -1
            saveEnd = -1
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, SAMPLE_RATE * 2)
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            EcoState.micError = "No se pudo abrir el micrófono (¿otra app lo está usando?)"
            return
        }
        recorder = rec
        EcoState.micError = null
        capturing = true
        acquireWakeLock()
        rec.startRecording()
        audioThread = Thread({ audioLoop(rec) }, "eco-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopRecorder(flushSave: Boolean) {
        if (flushSave) finishPendingSaveNow()
        capturing = false
        audioThread?.join(1_500)
        audioThread = null
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        recorder = null
        synchronized(lock) {
            // Privacidad: al pausar o apagar, lo que había en memoria se descarta.
            ring = ShortArray(0)
            total = 0
            saveStart = -1
            saveEnd = -1
        }
        EcoState.bufferedSec = 0
        EcoState.savingRemainingSec = -1
        releaseWakeLock()
    }

    private fun audioLoop(rec: AudioRecord) {
        val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
        while (capturing) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) {
                if (n < 0) {
                    EcoState.micError = "El micrófono dejó de responder ($n)"
                    SystemClock.sleep(200)
                }
                continue
            }
            var finished: Clip? = null
            synchronized(lock) {
                if (ring.isNotEmpty()) {
                    writeToRingLocked(chunk, n)
                    if (saveEnd in 0..total) finished = takeClipLocked()
                }
            }
            val clip = finished
            if (clip != null) runIo { writeClip(clip) }
        }
    }

    private fun writeToRingLocked(chunk: ShortArray, n: Int) {
        val size = ring.size
        var pos = (total % size).toInt()
        var i = 0
        while (i < n) {
            val c = min(n - i, size - pos)
            System.arraycopy(chunk, i, ring, pos, c)
            i += c
            pos = (pos + c) % size
        }
        total += n
    }

    private fun takeClipLocked(): Clip {
        val size = ring.size
        val end = min(saveEnd, total)
        val start = max(saveStart, total - size)
        val len = (end - start).coerceAtLeast(0).toInt()
        val out = ShortArray(len)
        var src = (start % size).toInt()
        var o = 0
        while (o < len) {
            val c = min(len - o, size - src)
            System.arraycopy(ring, src, out, o, c)
            o += c
            src = (src + c) % size
        }
        val startMs = System.currentTimeMillis() - (total - start) * 1000L / SAMPLE_RATE
        saveStart = -1
        saveEnd = -1
        return Clip(out, startMs, lastLocation)
    }

    private fun finishPendingSaveNow() {
        var clip: Clip? = null
        synchronized(lock) {
            if (saveEnd >= 0 && ring.isNotEmpty()) {
                saveEnd = total
                clip = takeClipLocked()
            }
        }
        val c = clip
        if (c != null) runIo { writeClip(c) }
    }

    // ───────────────────────── Guardar momento ─────────────────────────

    private fun requestSave() {
        if (!EcoState.running || EcoState.paused || !capturing) return
        synchronized(lock) {
            if (ring.isEmpty()) return
            val maxLen = ring.size.toLong() - SAMPLE_RATE
            if (saveEnd < 0) {
                saveStart = max(0L, total - activeBefore.toLong() * SAMPLE_RATE)
                saveEnd = total + activeAfter.toLong() * SAMPLE_RATE
            } else {
                // Tocar otra vez mientras se guarda = alargar el momento.
                saveEnd = min(saveStart + maxLen, total + activeAfter.toLong() * SAMPLE_RATE)
            }
        }
        EcoState.lastSaved = ""
        buzz()
        refreshLocation()
        updateLiveState()
        refreshNotification()
    }

    private fun writeClip(clip: Clip) {
        if (clip.pcm.size < SAMPLE_RATE) return // menos de 1 segundo: nada útil
        val dir = pendingDir(this)
        var name = "eco_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(clip.startMs))
        if (File(dir, "$name.wav").exists()) name += "_" + (System.currentTimeMillis() % 1000)

        val meta = JSONObject()
            .put("inicio_ms", clip.startMs)
            .put("duracion_s", clip.pcm.size.toDouble() / SAMPLE_RATE)
            .put("app", "eco-android-1")
        clip.location?.let {
            meta.put("lat", it.latitude)
                .put("lon", it.longitude)
                .put("precision_m", it.accuracy.toDouble())
                .put("loc_ms", it.time)
        }
        File(dir, "$name.json").writeText(meta.toString())
        val part = File(dir, "$name.wav.part")
        Wav.write(part, clip.pcm, SAMPLE_RATE)
        part.renameTo(File(dir, "$name.wav"))

        val hora = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        EcoState.lastSaved = "Guardado a las $hora · ${formatDuration(clip.pcm.size / SAMPLE_RATE)}"
        main.post { refreshNotification() }
        uploadPending()
    }

    // ───────────────────────── Modo reunión ─────────────────────────

    private fun startMeeting() {
        if (EcoState.reunionInicioMs > 0) return
        estabaEnPausa = EcoState.paused
        if (!ensureForeground()) return
        // El búfer de momentos suelta el micrófono: la reunión lo graba todo.
        if (capturing) stopRecorder(flushSave = true)
        EcoState.paused = false
        val ahora = System.currentTimeMillis()
        reunionClave = "reu_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ahora))
        EcoState.reunionParte = 0
        EcoState.reunionError = null
        refreshLocation()
        if (!openPart()) {
            EcoState.reunionInicioMs = 0
            if (!estabaEnPausa) startRecorder()
            refreshNotification()
            return
        }
        EcoState.reunionInicioMs = ahora
        buzz()
        main.postDelayed(rotarParte, REUNION_PARTE_MS)
        refreshNotification()
    }

    private fun stopMeeting() {
        if (EcoState.reunionInicioMs == 0L) return
        main.removeCallbacks(rotarParte)
        closePart(final = true)
        EcoState.reunionInicioMs = 0
        buzz()
        Toast.makeText(this, "Reunión guardada. Se sube a tu laptop.", Toast.LENGTH_SHORT).show()
        EcoState.paused = estabaEnPausa
        if (!EcoState.paused) startRecorder()
        checkBatteryNow()
        runIo { uploadPending() }
        refreshNotification()
    }

    /** Cada 10 minutos: cierra la parte actual (para subirla) y abre la siguiente. */
    private val rotarParte = object : Runnable {
        override fun run() {
            if (EcoState.reunionInicioMs == 0L) return
            if (System.currentTimeMillis() - EcoState.reunionInicioMs > REUNION_MAX_MS) {
                stopMeeting()  // tope de seguridad: 8 horas
                return
            }
            closePart(final = false)
            if (!openPart()) {
                // No se pudo seguir: lo grabado ya quedó guardado; cerramos la reunión con una marca final.
                finishMarker()
                EcoState.reunionInicioMs = 0
                EcoState.paused = estabaEnPausa
                if (!EcoState.paused) startRecorder()
                refreshNotification()
                return
            }
            refreshNotification()
            runIo { uploadPending() }
            main.postDelayed(this, REUNION_PARTE_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun openPart(): Boolean {
        val n = EcoState.reunionParte + 1
        val file = File(meetingDir(this), "%s_p%03d.m4a".format(Locale.US, reunionClave, n))
        val mr = newRecorder()
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(SAMPLE_RATE)
            mr.setAudioEncodingBitRate(32_000)
            mr.setOutputFile(file.absolutePath)
            mr.setOnErrorListener { _, what, _ ->
                EcoState.reunionError = "El micrófono se detuvo ($what)"
                main.post { stopMeeting() }
            }
            mr.prepare()
            mr.start()
            grabadora = mr
            parteArchivo = file
            parteInicioMs = System.currentTimeMillis()
            EcoState.reunionParte = n
            acquireWakeLock()
            true
        } catch (e: Exception) {
            mr.release()
            file.delete()
            EcoState.reunionError = "No se pudo grabar la reunión (${e.javaClass.simpleName})"
            false
        }
    }

    private fun closePart(final: Boolean) {
        val mr = grabadora ?: return
        val file = parteArchivo
        grabadora = null
        parteArchivo = null
        val ok = try {
            mr.stop()
            true
        } catch (e: Exception) {
            false  // parte demasiado corta o micrófono interrumpido
        } finally {
            mr.release()
            if (EcoState.reunionInicioMs == 0L || final) releaseWakeLock()
        }
        val n = EcoState.reunionParte
        if (ok && file != null && file.length() > 0) {
            val destino = File(pendingDir(this), file.name)
            writePartMeta(destino.nameWithoutExtension, n, final, parteInicioMs, System.currentTimeMillis())
            if (!file.renameTo(destino)) {
                file.copyTo(destino, overwrite = true)
                file.delete()
            }
        } else {
            file?.delete()
            if (final) finishMarker()
        }
    }

    /** Si la última parte salió vacía, mandamos un pedacito de silencio marcado como final
     *  para que la laptop sepa que la reunión terminó. */
    private fun finishMarker() {
        val n = EcoState.reunionParte
        val nombre = "%s_p%03d".format(Locale.US, reunionClave, n)
        val ahora = System.currentTimeMillis()
        Wav.write(File(pendingDir(this), "$nombre.wav"), ShortArray(SAMPLE_RATE / 5), SAMPLE_RATE)
        writePartMeta(nombre, n, true, ahora, ahora)
    }

    private fun writePartMeta(nombre: String, n: Int, final: Boolean, inicio: Long, fin: Long) {
        val meta = JSONObject()
            .put("inicio_ms", inicio)
            .put("duracion_s", (fin - inicio) / 1000.0)
            .put("app", "eco-android-1")
            .put(
                "sesion", JSONObject()
                    .put("clave", reunionClave)
                    .put("tipo", "reunion")
                    .put("parte", n)
                    .put("final", final)
            )
        lastLocation?.let {
            meta.put("lat", it.latitude).put("lon", it.longitude)
                .put("precision_m", it.accuracy.toDouble()).put("loc_ms", it.time)
        }
        File(pendingDir(this), "$nombre.json").writeText(meta.toString())
    }

    // ───────────────────────── Subida a la laptop ─────────────────────────

    private val uploadLoop = object : Runnable {
        override fun run() {
            if (!EcoState.running) return
            val ronda = uploadRounds++
            runIo {
                uploadPending()
                if (ronda % LATIDO_EVERY_ROUNDS == 0) latido(estadoTexto())
                if (ronda % UPDATE_EVERY_ROUNDS == 0) checkForUpdate()
            }
            main.postDelayed(this, UPLOAD_EVERY_MS)
        }
    }

    private fun estadoTexto(): String = when {
        !EcoState.running -> "apagado"
        EcoState.reunionInicioMs > 0 -> "reunion"
        EcoState.pausaBateria -> "pausa_bateria"
        EcoState.paused -> "pausa"
        else -> "escuchando"
    }

    /** "Sigo vivo": si deja de llegar, la laptop te avisa por Telegram. */
    private fun latido(estado: String) {
        if (prefs.server.isBlank()) return
        val pendientes = pendingDir(this).listFiles()?.count { isUploadable(it) } ?: 0
        val json = JSONObject()
            .put("estado", estado)
            .put("bateria", EcoState.batteryPct)
            .put("cargando", EcoState.charging)
            .put("pendientes", pendientes)
            .put("version", try { Updater.installedName(this) } catch (_: Exception) { "" })
        Net.latido(prefs.server, prefs.token, json.toString())
    }

    /** ¿Tu laptop tiene una versión nueva de Eco? Avisa una sola vez por versión. */
    private fun checkForUpdate() {
        val info = Updater.refresh(this, prefs.server, prefs.token) ?: return
        if (info.versionCode <= prefs.avisoVersion) return
        prefs.avisoVersion = info.versionCode
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ACTUALIZAR, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_eco)
            .setColor(ACCENT)
            .setContentTitle("Hay una versión nueva de Eco")
            .setContentText("Versión ${info.versionName}. Toca para instalarla (no pierdes tus ajustes).")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_UPDATE, n)
    }

    private fun uploadOnce() {
        runIo {
            uploadPending()
            main.post { if (!EcoState.running) stopSelf() }
        }
    }

    private fun uploadPending() {
        val dir = pendingDir(this)
        val files = (dir.listFiles() ?: emptyArray()).filter { isUploadable(it) }.sortedBy { it.name }
        if (files.isEmpty()) return
        if (prefs.server.isBlank()) {
            EcoState.lastUpload = "Falta la dirección de tu laptop"
            EcoState.lastUploadOk = false
            return
        }
        for (wav in files) {
            val meta = File(dir, wav.nameWithoutExtension + ".json")
            val result = Net.upload(prefs.server, prefs.token, wav, if (meta.exists()) meta.readText() else null)
            if (result.ok) {
                wav.delete()
                meta.delete()
                val hora = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                EcoState.lastUpload = "Llegó a tu laptop a las $hora"
                EcoState.lastUploadOk = true
            } else {
                EcoState.lastUpload = result.message
                EcoState.lastUploadOk = false
                break
            }
        }
    }

    private fun runIo(block: () -> Unit) {
        try {
            io.execute {
                try { block() } catch (_: Exception) {}
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    // ───────────────────────── Estado y notificación ─────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            if (!EcoState.running) return
            updateLiveState()
            val escuchando = !EcoState.paused && EcoState.reunionInicioMs == 0L && capturing
            volumen?.actualizar(escuchando, prefs.dobleVolumen)
            tickCount++
            if (EcoState.savingRemainingSec >= 0 && tickCount % 5 == 0) refreshNotification()
            main.postDelayed(this, 1_000)
        }
    }

    private fun updateLiveState() {
        synchronized(lock) {
            EcoState.bufferedSec = (min(total, activeBefore.toLong() * SAMPLE_RATE) / SAMPLE_RATE).toInt()
            EcoState.savingRemainingSec =
                if (saveEnd >= 0) ((saveEnd - total).coerceAtLeast(0) / SAMPLE_RATE).toInt() else -1
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val save = PendingIntent.getService(this, 1, Intent(this, EcoService::class.java).setAction(ACTION_SAVE), flags)
        val pause = PendingIntent.getService(this, 2, Intent(this, EcoService::class.java).setAction(ACTION_PAUSE), flags)
        val icon = Icon.createWithResource(this, R.drawable.ic_eco)

        val saving = EcoState.savingRemainingSec >= 0
        val enReunion = EcoState.reunionInicioMs > 0
        val title: String
        val text: String
        when {
            enReunion -> {
                title = "Grabando reunión"
                val pct = EcoState.batteryPct
                text = if (pct in 0..BATERIA_PAUSA && !EcoState.charging) {
                    "Batería en $pct %: la reunión sigue grabando. Conecta el cargador si puedes."
                } else {
                    "Parte ${EcoState.reunionParte} · cada 10 min se sube sola a tu laptop"
                }
            }
            EcoState.pausaBateria -> {
                title = "Eco en pausa: batería baja"
                text = "Tienes ${EcoState.batteryPct} %. Vuelve a escuchar solo cuando conectes el cargador."
            }
            EcoState.paused -> {
                title = "Eco en pausa"
                text = "Micrófono apagado. Nada se está escuchando."
            }
            saving -> {
                title = "Guardando momento…"
                text = "Faltan ${formatDuration(EcoState.savingRemainingSec)} · toca Guardar otra vez para alargar"
            }
            EcoState.lastSaved.isNotEmpty() -> {
                title = "Eco escuchando"
                text = "✓ ${EcoState.lastSaved}"
            }
            else -> {
                title = "Eco escuchando"
                text = "Últimos ${formatDuration(activeBefore)} en memoria · se borran solos"
            }
        }

        val builder = Notification.Builder(this, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_eco)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (enReunion) {
            val terminar = PendingIntent.getService(
                this, 5, Intent(this, EcoService::class.java).setAction(ACTION_REUNION_TERMINAR), flags
            )
            builder.addAction(Notification.Action.Builder(icon, "Terminar reunión", terminar).build())
            builder.setUsesChronometer(true).setWhen(EcoState.reunionInicioMs).setShowWhen(true)
        } else {
            if (!EcoState.paused) {
                builder.addAction(Notification.Action.Builder(icon, if (saving) "Alargar" else "Guardar momento", save).build())
            }
            builder.addAction(Notification.Action.Builder(icon, if (EcoState.paused) "Reanudar" else "Pausar", pause).build())
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun refreshNotification() {
        EcoWidget.refresh(this)
        if (!EcoState.running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun notifyStopped() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_AUTOSTART, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(
            this, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_eco)
            .setColor(ACCENT)
            .setContentTitle("Eco se detuvo")
            .setContentText("Android lo cerró. Toca para volver a escuchar.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_STOPPED, n)
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission")
    private fun refreshLocation() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val providers = listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER
        )
        val known = providers.mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }
        if (known != null) lastLocation = known
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> return
            }
            try {
                lm.getCurrentLocation(provider, null, mainExecutor) { loc -> if (loc != null) lastLocation = loc }
            } catch (_: Exception) {
            }
        }
    }

    private fun buzz() {
        try {
            vibrator()?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eco:microfono")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
