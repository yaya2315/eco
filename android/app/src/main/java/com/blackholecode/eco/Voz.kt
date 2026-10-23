package com.blackholecode.eco

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.sqrt

/**
 * «Eco, recuérdame…»: activar Eco con la voz.
 *
 * Un modelo chiquito de reconocimiento (Vosk, español, sin internet) escucha SOLO si dices "Eco" seguido
 * de una orden ("recuérdame", "anota", "haz"…). Todo pasa dentro del teléfono: nada sale hasta que lo
 * dices. Al oírlo, Eco vibra dos veces y guarda lo que ya estaba escuchando más lo que le pides, hasta
 * que te callas. En la laptop, Whisper confirma que de verdad dijiste "Eco" y anota la orden.
 */
object VozModelo {
    const val NOMBRE = "vosk-model-small-es-0.42"

    fun carpeta(ctx: Context) = File(ctx.filesDir, "voz/$NOMBRE")
    fun listo(ctx: Context) = File(carpeta(ctx), "listo").exists()

    @Volatile private var bajando = false

    /** Si falta el modelo, lo pide a la laptop en segundo plano y luego avisa al servicio. */
    fun asegurar(ctx: Context) {
        val app = ctx.applicationContext
        if (listo(app) || bajando) return
        bajando = true
        Thread({
            try {
                descargar(app)
                EcoState.vozError = null
                if (EcoState.running) EcoService.send(app, EcoService.ACTION_VOZ)
            } catch (e: Exception) {
                EcoState.vozError = e.message ?: "No se pudo bajar el modelo de voz"
            } finally {
                EcoState.vozDescarga = -1
                bajando = false
            }
        }, "eco-voz-modelo").start()
    }

    private fun abrir(base: String, ruta: String, token: String, lectura: Int): HttpURLConnection =
        (URL("$base$ruta").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = lectura
            setRequestProperty("X-Eco-Token", token)
        }

    private fun descargar(ctx: Context) {
        val prefs = Prefs(ctx)
        val base = Net.baseUrl(prefs.server) ?: throw IOException("Primero conecta Eco con tu laptop")
        // 1) ¿La laptop ya lo tiene? La primera vez lo baja de internet (unos 40 MB): esperamos.
        var info: JSONObject? = null
        val hasta = System.currentTimeMillis() + 15 * 60_000L
        while (info == null) {
            EcoState.vozDescarga = 0
            val c = abrir(base, "/app/voz", prefs.token, 10_000)
            val j = try {
                if (c.responseCode == 404) throw IOException("Actualiza Eco en tu laptop para usar la voz")
                if (c.responseCode != 200) throw IOException("La laptop respondió con error ${c.responseCode}")
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch (e: IOException) {
                throw if (e.message?.startsWith("La laptop") == true || e.message?.startsWith("Actualiza") == true) e
                else IOException("No encuentro tu laptop. Revisa que esté encendida (y Tailscale, si estás fuera).")
            } finally {
                c.disconnect()
            }
            if (j.optBoolean("listo")) {
                info = j
            } else {
                if (!j.optBoolean("bajando") && !j.isNull("error")) throw IOException(j.optString("error"))
                if (System.currentTimeMillis() > hasta) throw IOException("La laptop no terminó de bajar el modelo de voz. Prueba más tarde.")
                EcoState.vozError = "Tu laptop está bajando el modelo de voz (40 MB)…"
                Thread.sleep(10_000)
            }
        }
        EcoState.vozError = null
        // 2) Bajarlo de la laptop y revisar que llegó entero.
        val zip = File(ctx.cacheDir, "voz.zip")
        val c = abrir(base, "/app/voz.zip", prefs.token, 30_000)
        try {
            if (c.responseCode != 200) throw IOException("La laptop respondió con error ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.optLong("tamano")
            val md = MessageDigest.getInstance("SHA-256")
            var hecho = 0L
            c.inputStream.use { entrada ->
                zip.outputStream().use { salida ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        salida.write(buf, 0, n)
                        md.update(buf, 0, n)
                        hecho += n
                        if (total > 0) EcoState.vozDescarga = (hecho * 100 / total).toInt().coerceIn(0, 99)
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            val esperado = info.optString("sha256")
            if (esperado.isNotEmpty() && !hash.equals(esperado, ignoreCase = true)) {
                throw IOException("El modelo llegó incompleto. Inténtalo otra vez.")
            }
        } finally {
            c.disconnect()
        }
        // 3) Descomprimir en una carpeta temporal y moverla de un solo paso.
        try {
            descomprimir(ctx, zip)
        } finally {
            zip.delete()
        }
    }

    private fun descomprimir(ctx: Context, zip: File) {
        val raiz = File(ctx.filesDir, "voz")
        val tmp = File(raiz, "tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val limite = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val destino = File(tmp, e.name)
                if (!destino.canonicalPath.startsWith(limite)) throw IOException("Archivo de modelo no válido")
                if (e.isDirectory) destino.mkdirs() else {
                    destino.parentFile?.mkdirs()
                    destino.outputStream().use { z.copyTo(it) }
                }
            }
        }
        // El zip trae una carpeta con el nombre del modelo; si no, se usa tal cual.
        val dentro = File(tmp, NOMBRE).takeIf { it.isDirectory } ?: tmp
        if (!File(dentro, "am/final.mdl").exists()) throw IOException("El modelo de voz no está completo")
        val final = carpeta(ctx)
        final.deleteRecursively()
        final.parentFile?.mkdirs()
        if (!dentro.renameTo(final)) throw IOException("No pude guardar el modelo de voz")
        File(final, "listo").writeText(NOMBRE)
        tmp.deleteRecursively()
    }
}

/**
 * Escucha "Eco + orden" en el audio que ya captura el servicio (no abre el micrófono otra vez).
 * Para no gastar batería, el reconocedor solo trabaja cuando hay voz cerca; con silencio descansa.
 *
 * alActivar(frase, desde): oyó la orden; [desde] es la muestra donde más o menos empezó a hablar.
 * alCallar(): después de la orden, te callaste (ahí termina lo que se guarda).
 */
class DetectorVoz(
    private val ctx: Context,
    private val alActivar: (frase: String, desde: Long) -> Unit,
    private val alCallar: () -> Unit,
) {
    private class Trozo(val pcm: ShortArray, val fin: Long)

    private val cola = ArrayBlockingQueue<Trozo>(60)
    @Volatile private var activo = false
    private var hilo: Thread? = null
    private var modelo: Model? = null

    val escuchando get() = activo

    fun iniciar() {
        if (activo || !VozModelo.listo(ctx)) return
        activo = true
        cola.clear()
        EcoState.vozEstado = "cargando"
        hilo = Thread({ bucle() }, "eco-voz").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun detener() {
        activo = false
        hilo?.join(2_000)
        hilo = null
        cola.clear()
        if (EcoState.vozEstado != "error") EcoState.vozEstado = "apagado"
    }

    /** Libera el modelo (unos 50 MB de memoria) cuando el servicio se cierra. */
    fun cerrar() {
        detener()
        modelo?.close()
        modelo = null
    }

    /** Lo llama el hilo del micrófono con cada pedacito de 100 ms. Nunca lo frena: si va atrasado, suelta. */
    fun alimentar(pcm: ShortArray, n: Int, fin: Long) {
        if (activo) cola.offer(Trozo(pcm.copyOf(n), fin))
    }

    private fun bucle() {
        val rec = try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val m = modelo ?: Model(VozModelo.carpeta(ctx).absolutePath).also { modelo = it }
            Recognizer(m, EcoService.SAMPLE_RATE.toFloat(), GRAMATICA)
        } catch (e: Throwable) {
            EcoState.vozEstado = "error"
            EcoState.vozError = "No pude cargar el modelo de voz (${e.javaClass.simpleName})"
            activo = false
            return
        }
        EcoState.vozEstado = "escuchando"
        EcoState.vozError = null
        val previo = ArrayDeque<Trozo>()       // 400 ms antes de que empezaras a hablar
        var ruido = 120.0                       // nivel del silencio, se ajusta solo
        var hablando = false
        var silencio = 0                        // trozos seguidos sin voz
        var inicioVoz = 0L
        var ultimaParcial = ""
        var trozos = 0
        var activadoEn = -1L                    // muestra en la que oyó la orden
        var pausaHasta = 0L                     // tras una orden, no vuelve a activarse enseguida
        try {
            while (activo) {
                val t = cola.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val nivel = rms(t.pcm)
                val hayVoz = nivel > max(ruido * 3.0, 150.0)
                if (!hayVoz) ruido = ruido * 0.97 + nivel * 0.03 else ruido = ruido * 0.999 + nivel * 0.001
                if (hayVoz) EcoState.vozOyendoMs = System.currentTimeMillis()

                // Después de una orden: ¿ya te callaste? (2 s de silencio y al menos 3 s de orden)
                if (activadoEn >= 0) {
                    silencio = if (hayVoz) 0 else silencio + 1
                    val dura = (t.fin - activadoEn) / EcoService.SAMPLE_RATE
                    if ((silencio >= 20 && dura >= 3) || dura >= ORDEN_MAX_S) {
                        activadoEn = -1
                        alCallar()
                    }
                    continue  // mientras dictas la orden, el reconocedor descansa
                }

                if (!hablando) {
                    previo.addLast(t)
                    if (previo.size > 4) previo.removeFirst()
                    if (!hayVoz) continue
                    hablando = true
                    silencio = 0
                    inicioVoz = t.fin - previo.sumOf { it.pcm.size }
                    previo.forEach { rec.acceptWaveForm(it.pcm, it.pcm.size) }
                    previo.clear()
                    continue
                }
                silencio = if (hayVoz) 0 else silencio + 1
                val fin = rec.acceptWaveForm(t.pcm, t.pcm.size)
                var frase: String? = null
                if (fin) {
                    frase = orden(texto(rec.result, "text"))
                } else if (++trozos % 2 == 0) {
                    // Parcial: se exige que salga igual dos veces seguidas (evita falsas alarmas).
                    val p = orden(texto(rec.partialResult, "partial"))
                    if (p != null && p == ultimaParcial) frase = p
                    ultimaParcial = p ?: ""
                }
                if (frase == null && silencio >= 8) {  // 0.8 s callado: se cierra esa frase
                    frase = orden(texto(rec.finalResult, "text"))
                    rec.reset()
                    hablando = false
                    ultimaParcial = ""
                }
                if (frase != null && t.fin >= pausaHasta) {
                    rec.reset()
                    hablando = false
                    ultimaParcial = ""
                    activadoEn = t.fin
                    silencio = 0
                    pausaHasta = t.fin + 5L * EcoService.SAMPLE_RATE
                    // La orden empezó un poco antes de que la reconociera (máx. 3 s atrás).
                    alActivar(frase, max(inicioVoz, t.fin - 3L * EcoService.SAMPLE_RATE))
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            rec.close()
            if (EcoState.vozEstado == "escuchando" || EcoState.vozEstado == "cargando") EcoState.vozEstado = "apagado"
        }
    }

    companion object {
        const val ORDEN_MAX_S = 30

        /** Lo que activa a Eco: "Eco" + una de estas palabras (con y sin tilde, de tú y de vos). */
        val VERBOS = listOf(
            "recuérdame", "recuerdame", "recordame", "acuérdame", "acordate", "acuérdate", "recuerda",
            "anota", "anotá", "anótame", "anotame", "apunta", "apuntá", "apúntame", "apuntame",
            "guarda", "guardá", "guárdame", "guardame", "haz", "hazme", "hacé", "haceme",
            "pon", "poné", "ponme", "poneme", "avísame", "avisame", "agenda", "agendá", "agéndame",
            "escribe", "escribí", "toma", "busca", "buscá",
        )
        private val VERBOS_PLANOS = VERBOS.map { plano(it) }.toSet()

        /** Gramática corta: el modelo solo puede entender estas frases o "[unk]" (cualquier otra cosa). */
        val GRAMATICA: String = JSONArray().apply {
            VERBOS.forEach { put("eco $it") }
            put("[unk]")
        }.toString()

        fun plano(t: String): String =
            Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        /** "eco recuérdame" → la frase; cualquier otra cosa → null. */
        fun orden(texto: String): String? {
            val palabras = plano(texto).split(' ').filter { it.isNotBlank() && it != "[unk]" }
            for (i in 0 until palabras.size - 1) {
                if (palabras[i] == "eco" && palabras[i + 1] in VERBOS_PLANOS) return "eco ${palabras[i + 1]}"
            }
            return null
        }

        private fun texto(json: String?, clave: String): String = try {
            JSONObject(json ?: "{}").optString(clave)
        } catch (_: Exception) {
            ""
        }

        private fun rms(pcm: ShortArray): Double {
            if (pcm.isEmpty()) return 0.0
            var suma = 0.0
            for (s in pcm) suma += s.toDouble() * s
            return sqrt(suma / pcm.size)
        }

        /** Para encender o apagar el detector desde la pantalla. */
        fun aplicar(ctx: Context) {
            if (Prefs(ctx).vozActiva) VozModelo.asegurar(ctx)
            if (EcoState.running) EcoService.send(ctx, EcoService.ACTION_VOZ)
        }
    }
}
