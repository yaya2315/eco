package com.blackholecode.eco

import android.content.Context

/** Ajustes guardados en el teléfono. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("eco", Context.MODE_PRIVATE)

    /** IP y puerto de la laptop, por ejemplo 192.168.1.50:8765 */
    var server: String
        get() = sp.getString(K_SERVER, "") ?: ""
        set(value) = sp.edit().putString(K_SERVER, value.trim()).apply()

    /** Clave compartida con el servidor (la misma que pusiste en config.toml). */
    var token: String
        get() = sp.getString(K_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(K_TOKEN, value.trim()).apply()

    /** Cuántos segundos hacia atrás se guardan al tocar "Guardar momento". */
    var beforeSec: Int
        get() = sp.getInt(K_BEFORE, DEFAULT_BEFORE)
        set(value) = sp.edit().putInt(K_BEFORE, value.coerceIn(MIN_BEFORE, MAX_SECONDS)).apply()

    /** Cuántos segundos se siguen grabando después de tocarlo. */
    var afterSec: Int
        get() = sp.getInt(K_AFTER, DEFAULT_AFTER)
        set(value) = sp.edit().putInt(K_AFTER, value.coerceIn(0, MAX_SECONDS)).apply()

    /** Eco estaba encendido (para ofrecer volver a encenderlo después de una actualización). */
    var activo: Boolean
        get() = sp.getBoolean(K_ACTIVO, false)
        set(value) = sp.edit().putBoolean(K_ACTIVO, value).apply()

    /** Doble toque a "subir volumen" guarda un momento. */
    var dobleVolumen: Boolean
        get() = sp.getBoolean(K_DOBLE_VOLUMEN, true)
        set(value) = sp.edit().putBoolean(K_DOBLE_VOLUMEN, value).apply()

    /** Pasos de volumen multimedia que Eco bajó para ver el doble toque (se devuelven al sonar música). */
    var volumenPrestado: Int
        get() = sp.getInt(K_PRESTADO, 0)
        set(value) = sp.edit().putInt(K_PRESTADO, value.coerceAtLeast(0)).apply()

    /** Contactos de emergencia (nombre y número), en JSON. Solo viven en el teléfono. */
    var contactosJson: String
        get() = sp.getString(K_CONTACTOS, "[]") ?: "[]"
        set(value) = sp.edit().putString(K_CONTACTOS, value).apply()

    /** Preguntarte "¿estás bien?" cuando la laptop escucha algo que suena a peligro. */
    var detectarPeligro: Boolean
        get() = sp.getBoolean(K_PELIGRO, true)
        set(value) = sp.edit().putBoolean(K_PELIGRO, value).apply()

    /** Avisar también por WhatsApp (abre cada chat con el mensaje listo; tú tocas Enviar). */
    var usarWhatsapp: Boolean
        get() = sp.getBoolean(K_WHATSAPP, true)
        set(value) = sp.edit().putBoolean(K_WHATSAPP, value).apply()

    /** Mandar SMS aunque haya internet (llegan sin tocar nada más). Sin internet, siempre hay SMS. */
    var smsSiempre: Boolean
        get() = sp.getBoolean(K_SMS_SIEMPRE, true)
        set(value) = sp.edit().putBoolean(K_SMS_SIEMPRE, value).apply()

    /** La última pregunta de la laptop que ya te mostramos (para no repetirla). */
    var alertaVista: Long
        get() = sp.getLong(K_ALERTA_VISTA, 0L)
        set(value) = sp.edit().putLong(K_ALERTA_VISTA, value).apply()

    /** Tu nombre en el mensaje de emergencia. */
    var nombre: String
        get() = sp.getString(K_NOMBRE, "") ?: ""
        set(value) = sp.edit().putString(K_NOMBRE, value.trim().take(40)).apply()

    /** Última versión nueva de la que ya avisamos (para no repetir el aviso). */
    var avisoVersion: Long
        get() = sp.getLong(K_AVISO, 0L)
        set(value) = sp.edit().putLong(K_AVISO, value).apply()

    companion object {
        const val DEFAULT_BEFORE = 180
        const val DEFAULT_AFTER = 60
        const val MIN_BEFORE = 15
        const val MAX_SECONDS = 300

        private const val K_SERVER = "server"
        private const val K_TOKEN = "token"
        private const val K_BEFORE = "before_sec"
        private const val K_AFTER = "after_sec"
        private const val K_ACTIVO = "activo"
        private const val K_AVISO = "aviso_version"
        private const val K_DOBLE_VOLUMEN = "doble_volumen"
        private const val K_PRESTADO = "volumen_prestado"
        private const val K_CONTACTOS = "contactos_emergencia"
        private const val K_PELIGRO = "detectar_peligro"
        private const val K_ALERTA_VISTA = "alerta_vista"
        private const val K_NOMBRE = "nombre"
        private const val K_WHATSAPP = "usar_whatsapp"
        private const val K_SMS_SIEMPRE = "sms_siempre"
    }
}
