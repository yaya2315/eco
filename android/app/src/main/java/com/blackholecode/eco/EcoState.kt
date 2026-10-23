package com.blackholecode.eco

/**
 * Estado en vivo que comparten el servicio, la pantalla, el widget y el botón rápido.
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

    /** Modo reunión: cuándo empezó (0 = no hay reunión) y cuántas partes van. */
    @Volatile var reunionInicioMs = 0L
    @Volatile var reunionParte = 0
    @Volatile var reunionError: String? = null

    /** Batería: Eco se pausa solo al 20 % (sin cargador) y vuelve al conectarlo. */
    @Volatile var batteryPct = -1
    @Volatile var charging = false
    @Volatile var pausaBateria = false
    /** Reanudaste a mano con la batería baja: no volvemos a pausar hasta que cargues. */
    @Volatile var bateriaPermitida = false

    /** Versión nueva de la app que ofrece tu laptop (null = estás al día). */
    @Volatile var update: Updater.Info? = null
    @Volatile var updateStatus: String? = null
    @Volatile var updating = false

    /**
     * Doble toque a subir volumen: "listo", "musica", "llamada", "espera" (Eco no escucha),
     * "apagado" o "sin_audio" (el teléfono no dejó preparar el silencio).
     */
    @Volatile var volumenEstado = "apagado"
    /** Último toque a subir volumen que vio Eco y cuántos llevaba seguidos (para la prueba). */
    @Volatile var volumenToqueMs = 0L
    @Volatile var volumenToques = 0
    /** Último doble toque que guardó un momento. */
    @Volatile var volumenDobleMs = 0L

    /** «Eco, recuérdame…»: "apagado", "cargando", "escuchando" o "error". */
    @Volatile var vozEstado = "apagado"
    @Volatile var vozError: String? = null
    /** Bajando el modelo de voz desde la laptop: 0-99 %, o -1 si no se está bajando. */
    @Volatile var vozDescarga = -1
    /** Última vez que te oyó decir "Eco, …" y qué frase reconoció. */
    @Volatile var vozUltimaMs = 0L
    @Volatile var vozFrase = ""
    /** Qué pasó la última vez que se quiso mostrar el borde azul: "ok", "bloqueada", "sin_permiso", "error:…". */
    @Volatile var bordeNota = ""
    @Volatile var bordeNotaMs = 0L
    /** Última vez que el detector oyó voz cerca (para la prueba en pantalla). */
    @Volatile var vozOyendoMs = 0L

    @Volatile var lastSaved = ""
    @Volatile var lastUpload = "Todavía no se ha subido nada"
    @Volatile var lastUploadOk = true
}

/** 125 -> "2:05" */
fun formatDuration(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
