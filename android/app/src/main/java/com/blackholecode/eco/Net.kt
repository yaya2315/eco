package com.blackholecode.eco

import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Comunicación con el servidor de la laptop (HTTP dentro de tu Wi-Fi). */
object Net {
    const val DEFAULT_PORT = 8765

    class Result(val ok: Boolean, val message: String)

    /** "192.168.1.50" -> "http://192.168.1.50:8765" */
    fun baseUrl(raw: String): String? {
        var s = raw.trim().removeSuffix("/")
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val hostAndPort = s.substringAfter("://")
        if (!hostAndPort.contains(':')) s = "$s:$DEFAULT_PORT"
        return s
    }

    fun ping(server: String, token: String): Result {
        val base = baseUrl(server) ?: return Result(false, "Escribe la dirección de tu laptop")
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$base/estado").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                setRequestProperty("X-Eco-Token", token)
            }
            when (val code = c.responseCode) {
                200 -> Result(true, "Conectado con tu laptop")
                401 -> Result(false, "La laptop respondió, pero el token no coincide")
                else -> Result(false, "La laptop respondió con error $code")
            }
        } catch (e: Exception) {
            Result(false, "No encuentro la laptop en $base")
        } finally {
            c?.disconnect()
        }
    }

    fun upload(server: String, token: String, wav: File, metaJson: String?): Result {
        val base = baseUrl(server) ?: return Result(false, "Falta la dirección de tu laptop")
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$base/subir").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 60000
                setFixedLengthStreamingMode(wav.length())
                setRequestProperty("Content-Type", "audio/wav")
                setRequestProperty("X-Eco-Token", token)
                setRequestProperty("X-Eco-Archivo", wav.name)
                if (metaJson != null) {
                    val b64 = Base64.encodeToString(metaJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    setRequestProperty("X-Eco-Meta", b64)
                }
            }
            c.outputStream.use { out -> wav.inputStream().use { it.copyTo(out, 64 * 1024) } }
            when (val code = c.responseCode) {
                200 -> Result(true, "Subido")
                401 -> Result(false, "Token incorrecto")
                else -> Result(false, "La laptop respondió con error $code")
            }
        } catch (e: Exception) {
            Result(false, "Tu laptop no está al alcance; lo intento luego")
        } finally {
            c?.disconnect()
        }
    }
}
