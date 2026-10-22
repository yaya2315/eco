package com.blackholecode.eco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Avisos de emergencia. La regla de oro: Eco NUNCA manda nada solo. La laptop puede preguntarte
 * "¿estás bien?", pero el mensaje a tus contactos sale únicamente si tú mantienes presionado el
 * botón en la pantalla de emergencia.
 */
object Emergencia {
    const val CH_PELIGRO = "eco_peligro"
    const val NOTIF_PELIGRO = 20
    const val MAX_CONTACTOS = 5

    const val EXTRA_MODO = "modo"
    const val EXTRA_ID = "alerta_id"
    const val EXTRA_QUE_PASO = "que_paso"
    const val EXTRA_FRASE = "frase"
    const val EXTRA_HORA = "hora"

    const val MODO_DETECCION = "deteccion"
    const val MODO_MANUAL = "manual"
    const val MODO_PRUEBA = "prueba"

    const val ACCION_ESTOY_BIEN = "com.blackholecode.eco.ESTOY_BIEN"

    class Contacto(val nombre: String, val numero: String)

    fun contactos(prefs: Prefs): List<Contacto> = try {
        val arr = JSONArray(prefs.contactosJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val numero = o.optString("t").trim()
            if (numero.isEmpty()) null else Contacto(o.optString("n").trim().ifEmpty { numero }, numero)
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun guardarContactos(prefs: Prefs, lista: List<Contacto>) {
        val arr = JSONArray()
        lista.take(MAX_CONTACTOS).forEach { arr.put(JSONObject().put("n", it.nombre).put("t", it.numero)) }
        prefs.contactosJson = arr.toString()
    }

    /** Deja solo dígitos y el "+" inicial: "+503 7123-4567" → "+50371234567". */
    fun limpiarNumero(raw: String): String {
        val t = raw.trim()
        val digitos = t.filter { it.isDigit() }
        return if (t.startsWith("+")) "+$digitos" else digitos
    }

    fun crearCanal(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PELIGRO, "¿Estás bien?", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cuando algo que guardaste suena a peligro. Nada se manda sin que lo confirmes."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
        )
    }

    /** La pregunta que llega de la laptop. Tocarla abre la pantalla; desde aquí no se manda nada. */
    fun mostrarAviso(context: Context, id: Long, quePaso: String, frase: String, hora: String) {
        crearCanal(context)
        val abrir = Intent(context, EmergenciaActivity::class.java)
            .putExtra(EXTRA_MODO, MODO_DETECCION)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_QUE_PASO, quePaso)
            .putExtra(EXTRA_FRASE, frase)
            .putExtra(EXTRA_HORA, hora)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pAbrir = PendingIntent.getActivity(context, 30, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val bien = Intent(context, AlertaReceiver::class.java).setAction(ACCION_ESTOY_BIEN).putExtra(EXTRA_ID, id)
        val pBien = PendingIntent.getBroadcast(context, 31, bien, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val detalle = buildString {
            append(quePaso.ifBlank { "Algo de lo que guardaste suena a peligro." })
            if (frase.isNotBlank()) append("\n«").append(frase).append("»")
            append("\nToca para decidir si avisas a tus contactos. No se manda nada sin que lo confirmes.")
        }
        val n = Notification.Builder(context, CH_PELIGRO)
            .setSmallIcon(R.drawable.ic_eco)
            .setColor(0xFFFF5C7A.toInt())
            .setContentTitle("¿Estás bien?")
            .setContentText(quePaso.ifBlank { "Algo de lo que guardaste suena a peligro." })
            .setStyle(Notification.BigTextStyle().bigText(detalle))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pAbrir)
            .addAction(Notification.Action.Builder(null, "Estoy bien", pBien).build())
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60 * 60_000L)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIF_PELIGRO, n)
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones: la pregunta igual queda en Telegram.
        }
    }

    fun quitarAviso(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_PELIGRO)
    }

    /** Los SMS van sin tildes: así caben más letras por mensaje y se leen en cualquier teléfono. */
    fun sinTildes(t: String): String =
        Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace("¿", "").replace("¡", "").replace("«", "\"").replace("»", "\"")

    /**
     * El texto de la alerta. Para WhatsApp va con tildes y el título en negrita; para SMS, sin tildes
     * (caben más letras por mensaje y se lee en cualquier teléfono).
     */
    fun mensaje(modo: String, nombre: String, quePaso: String, ubicacion: Location?, bateria: Int,
                whatsapp: Boolean = false): String {
        val quien = nombre.trim().ifEmpty { "Tu contacto" }
        fun titulo(t: String) = if (whatsapp) "*$t*" else t
        if (modo == MODO_PRUEBA) {
            val t = "${titulo("PRUEBA DE ECO")}: $quien te puso como contacto de emergencia. Si algún día te llega una " +
                "ALERTA DE ECO, es real y trae su ubicación. Este mensaje es solo una prueba: no pasa nada."
            return if (whatsapp) t else sinTildes(t)
        }
        val hora = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val texto = StringBuilder()
        if (whatsapp) texto.append("🆘 ")
        if (modo == MODO_DETECCION) {
            texto.append("${titulo("ALERTA DE ECO")}: $quien podría estar en peligro.")
            if (quePaso.isNotBlank()) texto.append(" ").append(quePaso.trim().trimEnd('.')).append(".")
        } else {
            texto.append("${titulo("ALERTA DE ECO")}: $quien pidió ayuda.")
        }
        texto.append("\n")
        if (ubicacion != null) {
            val lat = "%.5f".format(Locale.US, ubicacion.latitude)
            val lon = "%.5f".format(Locale.US, ubicacion.longitude)
            val minutos = ((System.currentTimeMillis() - ubicacion.time) / 60_000).toInt()
            texto.append("Ubicación: https://maps.google.com/?q=$lat,$lon")
            val notas = mutableListOf<String>()
            if (ubicacion.hasAccuracy()) notas += "aprox. ${ubicacion.accuracy.roundToInt()} m"
            if (minutos >= 2) notas += "hace $minutos min"
            if (notas.isNotEmpty()) texto.append(" (").append(notas.joinToString(", ")).append(")")
        } else {
            texto.append("Ubicación: no disponible.")
        }
        texto.append("\nHora: $hora.")
        if (bateria in 0..100) texto.append(" Batería: $bateria%.")
        texto.append("\nLo confirmó desde su teléfono. Llámale; si no contesta, llama al 911.")
        return if (whatsapp) texto.toString() else sinTildes(texto.toString())
    }

    // ───────────────────────── WhatsApp ─────────────────────────
    // WhatsApp no deja que otra app mande mensajes sola: Eco abre el chat con el mensaje ya escrito
    // y tú tocas Enviar. Si no hay internet, WhatsApp lo guarda y lo manda cuando vuelve la conexión.

    private val PAQUETES_WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** El paquete de WhatsApp instalado (normal o Business), o null. */
    fun paqueteWhatsapp(context: Context): String? =
        PAQUETES_WHATSAPP.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    /** WhatsApp pide el número con código de país y sin "+": "7123 4567" → "50371234567" (El Salvador). */
    fun numeroWhatsapp(numero: String): String {
        var d = numero.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        if (d.length == 8) d = "503$d"
        return d
    }

    fun abrirWhatsapp(context: Context, numero: String, texto: String): Boolean {
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${numeroWhatsapp(numero)}&text=" + Uri.encode(texto))
        val paquete = paqueteWhatsapp(context)
        for (intento in listOf(paquete, null)) {
            try {
                val i = Intent(Intent.ACTION_VIEW, uri)
                if (intento != null) i.setPackage(intento)
                context.startActivity(i)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** ¿Hay internet de verdad (no solo Wi-Fi sin salida)? */
    fun hayInternet(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) {
        false
    }
}

/** "Estoy bien" desde la notificación: la quita y le cuenta a la laptop que no pasa nada. */
class AlertaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Emergencia.ACCION_ESTOY_BIEN) return
        Emergencia.quitarAviso(context)
        val id = intent.getLongExtra(Emergencia.EXTRA_ID, -1L)
        if (id <= 0) return
        val prefs = Prefs(context)
        val pendiente = goAsync()
        Thread {
            try {
                Net.responderAlerta(prefs.server, prefs.token, id, "descartada", null)
            } finally {
                pendiente.finish()
            }
        }.start()
    }
}
