package com.blackholecode.eco

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Botón rápido del panel de notificaciones (desliza hacia abajo → "Guardar momento").
 * Funciona incluso con la pantalla bloqueada, sin abrir la app.
 */
class EcoTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val listening = EcoState.running && !EcoState.paused
        if (listening) {
            EcoService.send(this, EcoService.ACTION_SAVE)
            qsTile?.apply {
                label = "Guardando…"
                updateTile()
            }
            return
        }
        // Eco está apagado o en pausa: abrimos la app para que lo reactives.
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTOSTART, true)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            startLegacy(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun startLegacy(intent: Intent) = startActivityAndCollapse(intent)

    private fun refresh() {
        val tile = qsTile ?: return
        val listening = EcoState.running && !EcoState.paused
        tile.state = if (listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Guardar momento"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                listening -> "Eco escuchando"
                EcoState.paused -> "Eco en pausa"
                else -> "Eco apagado"
            }
        }
        tile.updateTile()
    }
}
