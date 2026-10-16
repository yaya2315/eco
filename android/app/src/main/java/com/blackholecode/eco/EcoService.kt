package com.blackholecode.eco

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

        const val SAMPLE_RATE = 16_000
        private const val CH_ACTIVE = "eco_activo"
        private const val CH_ALERTS = "eco_avisos"
        private const val NOTIF_ID = 7
        private const val NOTIF_STOPPED = 8
        private const val UPLOAD_EVERY_MS = 120_000L
        private const val ACCENT = 0xFF6B5BF5.toInt()

        fun send(context: Context, action: String) {
            val intent = Intent(context, EcoService::class.java).setAction(action)
            if (action == ACTION_START) context.startForegroundService(intent) else context.startService(intent)
        }

        fun pendingDir(context: Context): File = File(context.filesDir, "pendientes").apply { mkdirs() }
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels()
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
        when (action) {
            ACTION_START -> startCapture()
            ACTION_SAVE -> requestSave()
            ACTION_PAUSE -> togglePause()
            ACTION_RELOAD -> reload()
            ACTION_UPLOAD -> uploadOnce()
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        if (!EcoState.running && action != ACTION_UPLOAD) stopSelf()
        return if (EcoState.running) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (capturing) stopRecorder(flushSave = true)
        EcoState.running = false
        EcoState.paused = false
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    // ───────────────────────── Ciclo de vida ─────────────────────────

    private fun startCapture() {
        if (EcoState.running) {
            if (!EcoState.paused && !capturing) startRecorder()
            refreshNotification()
            return
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            EcoState.micError = "Falta el permiso del micrófono"
            return
        }
        try {
            goForeground()
        } catch (e: Exception) {
            EcoState.micError = "Android no dejó iniciar Eco (${e.javaClass.simpleName})"
            return
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_STOPPED)
        EcoState.running = true
        EcoState.paused = false
        startRecorder()
        main.removeCallbacks(tick)
        main.post(tick)
        main.removeCallbacks(uploadLoop)
        main.postDelayed(uploadLoop, 3_000)
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
        if (!EcoState.running) return
        if (EcoState.paused) {
            EcoState.paused = false
            startRecorder()
        } else {
            stopRecorder(flushSave = true)
            EcoState.paused = true
        }
        refreshNotification()
    }

    /** Aplica nuevos segundos de antes/después sin apagar Eco. */
    private fun reload() {
        if (!EcoState.running || EcoState.paused) return
        stopRecorder(flushSave = true)
        startRecorder()
        refreshNotification()
    }

    private fun shutdown() {
        stopRecorder(flushSave = true)
        EcoState.running = false
        EcoState.paused = false
        main.removeCallbacks(tick)
        main.removeCallbacks(uploadLoop)
        stopForeground(STOP_FOREGROUND_REMOVE)
        runIo { uploadPending() }
        stopSelf()
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

    // ───────────────────────── Subida a la laptop ─────────────────────────

    private val uploadLoop = object : Runnable {
        override fun run() {
            if (!EcoState.running) return
            runIo { uploadPending() }
            main.postDelayed(this, UPLOAD_EVERY_MS)
        }
    }

    private fun uploadOnce() {
        runIo {
            uploadPending()
            main.post { if (!EcoState.running) stopSelf() }
        }
    }

    private fun uploadPending() {
        val dir = pendingDir(this)
        val files = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".wav") }.sortedBy { it.name }
        if (files.isEmpty()) return
        if (prefs.server.isBlank()) {
            EcoState.lastUpload = "Falta la dirección de tu laptop"
            EcoState.lastUploadOk = false
            return
        }
        for (wav in files) {
            val meta = File(dir, wav.name.removeSuffix(".wav") + ".json")
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

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ACTIVE, "Eco activo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras Eco tiene el micrófono abierto"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Avisos de Eco", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Cuando Eco se detiene y necesita que lo reactives"
            }
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val save = PendingIntent.getService(this, 1, Intent(this, EcoService::class.java).setAction(ACTION_SAVE), flags)
        val pause = PendingIntent.getService(this, 2, Intent(this, EcoService::class.java).setAction(ACTION_PAUSE), flags)
        val icon = Icon.createWithResource(this, R.drawable.ic_eco)

        val saving = EcoState.savingRemainingSec >= 0
        val title: String
        val text: String
        when {
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
        if (!EcoState.paused) {
            builder.addAction(Notification.Action.Builder(icon, if (saving) "Alargar" else "Guardar momento", save).build())
        }
        builder.addAction(Notification.Action.Builder(icon, if (EcoState.paused) "Reanudar" else "Pausar", pause).build())
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun refreshNotification() {
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
