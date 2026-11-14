package com.blackholecode.eco

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Comunicación con el servidor de la laptop (HTTP dentro de tu Wi-Fi). */
object Net {
    const val DEFAULT_PORT = 8765

    class Result(val ok: Boolean, val message: String)

    class Pairing(val server: String, val token: String)

    /** Lee el enlace del QR (eco://conectar?d=…&t=…). Devuelve null si no es de Eco o no es seguro. */
    fun parsePairing(raw: String?): Pairing? {
        if (raw.isNullOrBlank()) return null
        val uri = try { android.net.Uri.parse(raw.trim()) } catch (_: Exception) { return null }
        if (uri.scheme != "eco" || uri.host != "conectar") return null
        val server = (uri.getQueryParameter("d") ?: uri.getQueryParameter("w"))?.trim().orEmpty()
        val token = uri.getQueryParameter("t")?.trim().orEmpty()
        if (server.isEmpty() || token.length < 8 || !isPrivateAddress(server)) return null
        return Pairing(server, token)
    }

    /**
     * Solo aceptamos direcciones privadas: Tailscale (100.64.0.0/10), redes de casa
     * (10.x, 172.16-31.x, 192.168.x) o nombres de Tailscale (*.ts.net). Así un QR o enlace
     * falso no puede mandar tus audios a un servidor de internet.
     */
    fun isPrivateAddress(server: String): Boolean {
        val host = server.removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
        if (host.endsWith(".ts.net")) return true
        val partes = host.split('.')
        if (partes.size != 4) return false
        val n = partes.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return when {
            n[0] == 10 -> true
            n[0] == 192 && n[1] == 168 -> true
            n[0] == 172 && n[1] in 16..31 -> true
            n[0] == 100 && n[1] in 64..127 -> true
            else -> false
        }
    }

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

    /** "Sigo vivo": la laptop te avisa por Telegram si el teléfono deja de reportarse. */
    fun latido(server: String, token: String, json: String): Boolean {
        val base = baseUrl(server) ?: return false
        var c: HttpURLConnection? = null
        return try {
            val cuerpo = json.toByteArray(Charsets.UTF_8)
            c = (URL("$base/latido").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 6000
                setFixedLengthStreamingMode(cuerpo.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Eco-Token", token)
            }
            c.outputStream.use { it.write(cuerpo) }
            c.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            c?.disconnect()
        }
    }

    /** ¿La laptop escuchó algo que suena a peligro? Devuelve la respuesta tal cual (o null si no hay conexión). */
    fun alerta(server: String, token: String): JSONObject? {
        val base = baseUrl(server) ?: return null
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$base/alerta").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                setRequestProperty("X-Eco-Token", token)
            }
            if (c.responseCode != 200) return null
            JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** Le cuenta a la laptop qué decidiste: "enviada" (y a cuántos) o "descartada". */
    fun responderAlerta(server: String, token: String, id: Long, estado: String, enviados: Int?): Boolean {
        val json = JSONObject().put("id", id).put("estado", estado)
        if (enviados != null) json.put("enviados", enviados)
        val base = baseUrl(server) ?: return false
        var c: HttpURLConnection? = null
        return try {
            val cuerpo = json.toString().toByteArray(Charsets.UTF_8)
            c = (URL("$base/alerta").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 6000
                setFixedLengthStreamingMode(cuerpo.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Eco-Token", token)
            }
            c.outputStream.use { it.write(cuerpo) }
            c.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            c?.disconnect()
        }
    }

    /** Lo que contestó la laptop a una pregunta: el código HTTP y el JSON (si lo hubo). */
    class Respuesta(val codigo: Int, val json: JSONObject?)

    private fun leer(server: String, token: String, ruta: String): Respuesta? {
        val base = baseUrl(server) ?: return null
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$base$ruta").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 10000
                setRequestProperty("X-Eco-Token", token)
            }
            val codigo = c.responseCode
            val json = if (codigo == 200) {
                JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } else null
            Respuesta(codigo, json)
        } catch (_: Exception) {
            null  // sin conexión con la laptop
        } finally {
            c?.disconnect()
        }
    }

    /** Las reuniones recientes (y las que la laptop todavía está procesando). */
    fun reuniones(server: String, token: String): Respuesta? = leer(server, token, "/reuniones")

    /** Una reunión con su crónica, por id o por la clave con que se grabó. */
    fun reunion(server: String, token: String, id: Long? = null, clave: String? = null): Respuesta? {
        val consulta = if (id != null) "id=$id" else "clave=" + java.net.URLEncoder.encode(clave.orEmpty(), "UTF-8")
        return leer(server, token, "/reunion?$consulta")
    }

    /** Una respuesta que es una lista (tus pendientes): el código HTTP y la lista (si la hubo). */
    class Lista(val codigo: Int, val json: JSONArray?)

    private fun leerLista(server: String, token: String, ruta: String): Lista? {
        val base = baseUrl(server) ?: return null
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$base$ruta").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 10000
                setRequestProperty("X-Eco-Token", token)
            }
            val codigo = c.responseCode
            val json = if (codigo == 200) {
                JSONArray(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } else null
            Lista(codigo, json)
        } catch (_: Exception) {
            null  // sin conexión con la laptop
        } finally {
            c?.disconnect()
        }
    }

    /** Lo mismo que hace el panel de la laptop: mandar un cambio (marcar una tarea, agregar un pendiente). */
    private fun enviar(server: String, token: String, ruta: String, datos: JSONObject): Respuesta? {
        val base = baseUrl(server) ?: return null
        var c: HttpURLConnection? = null
        return try {
            val cuerpo = datos.toString().toByteArray(Charsets.UTF_8)
            c = (URL("$base$ruta").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 10000
                setFixedLengthStreamingMode(cuerpo.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Eco-Token", token)
                setRequestProperty("X-Eco-Panel", "1")  // la laptop la pide en todo lo que cambia datos
            }
            c.outputStream.use { it.write(cuerpo) }
            val codigo = c.responseCode
            val json = if (codigo == 200) {
                JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } else null
            Respuesta(codigo, json)
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** Todo lo que tienes por hacer (lo mismo que la columna Pendientes del panel). */
    fun pendientes(server: String, token: String): Lista? = leerLista(server, token, "/api/pendientes")

    /** Lo que dijiste para no olvidar (datos e ideas). */
    fun recordar(server: String, token: String): Lista? = leerLista(server, token, "/api/recordar")

    /** Marca una tarea como hecha (o la desmarca). true si la laptop lo guardó. */
    fun marcarTarea(server: String, token: String, id: Long, hecho: Boolean): Boolean =
        enviar(server, token, "/api/tarea", JSONObject().put("id", id).put("hecho", hecho))?.codigo == 200

    /** Agrega un pendiente escrito en el teléfono; fecha "2026-11-13" y hora "14:00" son opcionales. */
    fun nuevoPendiente(server: String, token: String, texto: String, fecha: String?, hora: String?): Respuesta? {
        val datos = JSONObject().put("texto", texto)
        if (fecha != null) datos.put("fecha", fecha)
        if (fecha != null && hora != null) datos.put("hora", hora)
        return enviar(server, token, "/api/pendiente", datos)
    }

    /** Finanzas: los números de una cuenta en un periodo ("dia", "semana", "mes" o "anio"); ref = un día de ese periodo. */
    fun finanzas(server: String, token: String, cuenta: Long, periodo: String, ref: String?): Respuesta? {
        var ruta = "/api/finanzas?periodo=$periodo"
        if (cuenta > 0) ruta += "&cuenta=$cuenta"
        if (!ref.isNullOrEmpty()) ruta += "&ref=$ref"
        return leer(server, token, ruta)
    }

    /**
     * Finanzas completas (cuentas, movimientos, presupuestos, metas y pagos fijos), para guardar una copia en el
     * teléfono. Con [version] (la de la copia que ya tienes) la laptop solo contesta «igual» si nada cambió.
     */
    fun finanzasTodo(server: String, token: String, version: String): Respuesta? {
        val v = if (version.isEmpty()) "" else "?v=" + java.net.URLEncoder.encode(version, "UTF-8")
        return leer(server, token, "/api/finanzas/todo$v")
    }

    /** Un cambio en Finanzas: "movimiento", "movimiento/borrar", "presupuesto", "meta", "fijo" o "cuenta". */
    fun finanzasCambio(server: String, token: String, accion: String, datos: JSONObject): Respuesta? =
        enviar(server, token, "/api/finanzas/$accion", datos)

    fun upload(server: String, token: String, wav: File, metaJson: String?): Result {
        val tipo = if (wav.name.endsWith(".m4a")) "audio/mp4" else "audio/wav"
        val base = baseUrl(server) ?: return Result(false, "Falta la dirección de tu laptop")
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$base/subir").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 60000
                setFixedLengthStreamingMode(wav.length())
                setRequestProperty("Content-Type", tipo)
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
