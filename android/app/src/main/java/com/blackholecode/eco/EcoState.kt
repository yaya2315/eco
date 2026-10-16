package com.blackholecode.eco

/**
 * Estado en vivo que comparten el servicio, la pantalla y el botón rápido.
 * Todo corre en el mismo proceso, así que basta con un objeto en memoria.
 */
object EcoState {
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var micError: String? = null

    /** Segundos de audio que hay ahora mismo en el búfer (hasta el máximo de "antes"). */
    @Volatile var bufferedSec = 0
    @Volatile var beforeSec = 180

    /** Segundos que faltan para cerrar el momento que se está guardando; -1 si no hay ninguno. */
    @Volatile var savingRemainingSec = -1

    @Volatile var lastSaved = ""
    @Volatile var lastUpload = "Todavía no se ha subido nada"
    @Volatile var lastUploadOk = true
}

/** 125 -> "2:05" */
fun formatDuration(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
