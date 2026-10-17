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
    }
}
