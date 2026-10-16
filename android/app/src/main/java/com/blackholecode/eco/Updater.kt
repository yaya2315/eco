package com.blackholecode.eco

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualizaciones sin GitHub: tu laptop baja la versión nueva que compiló GitHub y se la
 * ofrece al teléfono. La app la baja de la laptop (con tu token), revisa que llegó entera
 * (sha256) y se la pasa al instalador de Android. Misma firma = se instala encima, sin perder nada.
 */
object Updater {
    class Info(val versionCode: Long, val versionName: String, val size: Long, val sha256: String)

    fun installedCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else legacyCode(pi)
    }

    @Suppress("DEPRECATION")
    private fun legacyCode(pi: PackageInfo): Long = pi.versionCode.toLong()

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** Pregunta a la laptop qué versión tiene lista. null = no hay o no se pudo saber. */
    fun check(server: String, token: String): Info? {
        val base = Net.baseUrl(server) ?: return null
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$base/app/version").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 8000
                setRequestProperty("X-Eco-Token", token)
            }
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            Info(j.getLong("versionCode"), j.optString("versionName"), j.optLong("tamano"), j.optString("sha256"))
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** Revisa y deja el resultado en EcoState.update (solo si es más nueva que la instalada). */
    fun refresh(ctx: Context, server: String, token: String): Info? {
        if (server.isBlank()) return null
        val info = check(server, token)
        EcoState.update = if (info != null && info.versionCode > installedCode(ctx)) info else null
        return EcoState.update
    }

    fun download(ctx: Context, server: String, token: String, info: Info, progress: (Int) -> Unit): File {
        val base = Net.baseUrl(server) ?: throw IOException("Falta la dirección de tu laptop")
        val dest = File(ctx.cacheDir, "eco-actualizacion.apk")
        val c = (URL("$base/app/eco.apk").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 30000
            setRequestProperty("X-Eco-Token", token)
        }
        try {
            if (c.responseCode != 200) throw IOException("La laptop respondió con error ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else -1
                        if (pct != last) {
                            last = pct
                            progress(pct)
                        }
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotEmpty() && !hash.equals(info.sha256, ignoreCase = true)) {
                dest.delete()
                throw IOException("El archivo llegó incompleto. Inténtalo otra vez.")
            }
            return dest
        } finally {
            c.disconnect()
        }
    }

    /** Le pasa el APK al instalador de Android. Android pide confirmar con un toque. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Si Eco ya se instaló a sí mismo antes, Android puede no volver a preguntar.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("eco.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(ctx, 0, intent, flags).intentSender)
        }
    }
}

/** Respuesta del instalador de Android. */
class UpdateReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.blackholecode.eco.INSTALL_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                EcoState.updateStatus = "Confirma en la ventana de Android…"
                try {
                    context.startActivity(confirm)
                } catch (_: Exception) {
                    EcoState.updating = false
                    EcoState.updateStatus = "Abre Eco y toca Actualizar otra vez"
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                EcoState.updating = false
                EcoState.updateStatus = "Listo"
            }
            else -> {
                EcoState.updating = false
                EcoState.updateStatus = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    "Cancelaste la actualización"
                } else {
                    "No se pudo instalar (" +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status") + ")"
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_INTENT)
}

/**
 * Después de actualizarse, Android cierra Eco. Si estaba escuchando, te deja un aviso para
 * volver a encenderlo con un toque (desde segundo plano Android no deja abrir el micrófono).
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.cacheDir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
        EcoWidget.refresh(context)
        if (!Prefs(context).activo) return
        EcoService.createChannels(context)
        val open = PendingIntent.getActivity(
            context, 6,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, EcoService.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_eco)
            .setColor(0xFF6B5BF5.toInt())
            .setContentTitle("Eco se actualizó (versión ${Updater.installedName(context)})")
            .setContentText("Toca para volver a escuchar.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(EcoService.NOTIF_UPDATED, n)
    }
}
