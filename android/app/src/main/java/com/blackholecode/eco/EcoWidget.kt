package com.blackholecode.eco

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Widget de la pantalla de inicio: cómo está Eco y un botón grande de "Guardar momento".
 * El servicio lo repinta cada vez que cambia algo (escuchar, pausa, guardar, reunión).
 */
class EcoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, build(context))
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = try {
                manager.getAppWidgetIds(ComponentName(context, EcoWidget::class.java))
            } catch (_: Exception) {
                return
            }
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, build(context))
        }

        private fun build(c: Context): RemoteViews {
            val s = EcoState
            val v = RemoteViews(c.packageName, R.layout.widget_eco)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val abrir = PendingIntent.getActivity(
                c, 20,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            val encender = PendingIntent.getActivity(
                c, 21,
                Intent(c, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            fun servicio(action: String, req: Int) =
                PendingIntent.getService(c, req, Intent(c, EcoService::class.java).setAction(action), flags)

            val punto: Int
            val titulo: String
            val detalle: String
            val boton: String
            val fondoBoton: Int
            val accion: PendingIntent
            when {
                s.reunionInicioMs > 0 -> {
                    punto = R.drawable.widget_punto_rec
                    titulo = "Grabando reunión"
                    detalle = "Parte ${s.reunionParte} · se sube sola a tu laptop"
                    boton = "Abrir Eco"
                    fondoBoton = R.drawable.widget_boton_suave
                    accion = abrir
                }
                !s.running -> {
                    punto = R.drawable.widget_punto_apagado
                    titulo = "Eco apagado"
                    detalle = "No está escuchando"
                    boton = "Encender Eco"
                    fondoBoton = R.drawable.widget_boton
                    accion = encender
                }
                s.paused -> {
                    punto = R.drawable.widget_punto_pausa
                    titulo = if (s.pausaBateria) "En pausa: batería baja" else "Eco en pausa"
                    detalle = if (s.pausaBateria) "Vuelve sola al conectar el cargador" else "Micrófono apagado"
                    boton = "Reanudar"
                    fondoBoton = R.drawable.widget_boton_suave
                    accion = servicio(EcoService.ACTION_PAUSE, 22)
                }
                s.savingRemainingSec >= 0 -> {
                    punto = R.drawable.widget_punto_rec
                    titulo = "Guardando momento…"
                    detalle = "Toca otra vez para alargarlo"
                    boton = "Alargar"
                    fondoBoton = R.drawable.widget_boton
                    accion = servicio(EcoService.ACTION_SAVE, 23)
                }
                else -> {
                    punto = R.drawable.widget_punto_vivo
                    titulo = "Eco escuchando"
                    detalle = if (s.lastSaved.isNotEmpty()) "✓ ${s.lastSaved}"
                    else "Últimos ${formatDuration(s.beforeSec)} en memoria"
                    boton = "Guardar momento"
                    fondoBoton = R.drawable.widget_boton
                    accion = servicio(EcoService.ACTION_SAVE, 23)
                }
            }
            v.setImageViewResource(R.id.widget_punto, punto)
            v.setTextViewText(R.id.widget_titulo, titulo)
            v.setTextViewText(R.id.widget_detalle, detalle)
            v.setTextViewText(R.id.widget_boton, boton)
            v.setContentDescription(R.id.widget_boton, boton)
            v.setInt(R.id.widget_boton, "setBackgroundResource", fondoBoton)
            v.setOnClickPendingIntent(R.id.widget_boton, accion)
            v.setOnClickPendingIntent(R.id.widget_raiz, abrir)
            return v
        }
    }
}
