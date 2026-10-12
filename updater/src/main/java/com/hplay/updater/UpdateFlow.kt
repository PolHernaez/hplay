package com.hplay.updater

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Parte Android del actualizador: comprobar (con caché de 30 min), diálogo con progreso, permiso de instalar,
 * comprobación de la APK (paquete, versión y misma firma que la instalada) e instalación con PackageInstaller.
 * Sirve igual en el móvil y en la tele (diálogo del sistema, manejable con el mando).
 */
object UpdateFlow {
    private const val PREFS = "hplay_update"
    private const val CACHE_MS = 30 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    private var current: AlertDialog? = null

    fun installedCode(ctx: Context): Long {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }

    private fun installedName(ctx: Context) = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun ua(ctx: Context) = "HPlay/${installedName(ctx)} (${ctx.packageName})"

    /**
     * Comprueba en segundo plano y responde en el hilo principal. Sin red usa el último manifiesto bueno
     * (así una versión obligatoria sigue siéndolo aunque no haya conexión). [force] salta la caché.
     */
    fun check(act: Activity, force: Boolean = false, onResult: (Updater.State, Updater.Manifest?) -> Unit) {
        val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        thread(name = "hplay-update-check") {
            val cached = prefs.getString("json", null)
            val fresh = !force && System.currentTimeMillis() - prefs.getLong("ts", 0) < CACHE_MS
            val m = if (fresh && cached != null) runCatching { Updater.parse(cached) }.getOrNull()
            else runCatching {
                Updater.fetchManifest(ua(act)).also { mf ->
                    // Se guarda solo lo que ya pasó la firma.
                    prefs.edit().putString("json", toJson(mf)).putLong("ts", System.currentTimeMillis()).apply()
                }
            }.getOrElse { cached?.let { c -> runCatching { Updater.parse(c) }.getOrNull() } }
            val st = Updater.state(installedCode(act), m)
            main.post { if (!act.isFinishing) onResult(st, m) }
        }
    }

    private fun toJson(m: Updater.Manifest): String {
        val files = org.json.JSONObject()
        m.files.forEach { (k, v) ->
            files.put(k, org.json.JSONObject().put("size", v.size).put("sha256", v.sha256).apply { v.packageName?.let { put("package", it) } })
        }
        return org.json.JSONObject().put("version", m.versionName).put("versionCode", m.versionCode)
            .put("minRequired", m.minRequired).put("notes", m.notes).put("files", files).toString()
    }

    /** Diálogo de actualización. Si [forced], no se puede cerrar: la versión instalada ya no se admite. */
    fun show(act: Activity, asset: String, m: Updater.Manifest, forced: Boolean) {
        val info = m.files[asset] ?: return
        if (current?.isShowing == true) return // onResume puede volver a llamar: un solo diálogo
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val text = TextView(act).apply { textSize = 16f }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(text)
            addView(bar)
        }
        val body = if (forced) act.getString(R.string.upd_forced, m.versionName) else act.getString(R.string.upd_body, m.versionName, installedName(act))
        text.text = if (m.notes.isBlank()) body else body + "\n\n" + m.notes
        val b = AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.upd_title, m.versionName))
            .setView(box)
            .setCancelable(!forced)
            .setPositiveButton(R.string.upd_btn, null)
        if (!forced) b.setNegativeButton(R.string.upd_later, null)
        val dialog = b.create()
        dialog.setOnShowListener {
            val go = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            go.setOnClickListener {
                if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
                    text.text = act.getString(R.string.upd_perm)
                    go.setText(R.string.upd_perm_btn)
                    go.setOnClickListener {
                        val ok = runCatching {
                            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                        }.isSuccess
                        if (!ok) text.text = act.getString(R.string.upd_perm_manual)
                        dialog.dismiss() // al volver de ajustes, check() vuelve a abrir el diálogo
                    }
                    return@setOnClickListener
                }
                go.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                bar.visibility = View.VISIBLE
                bar.progress = 0
                text.text = act.getString(R.string.upd_downloading, 0)
                thread(name = "hplay-update") {
                    val result = runCatching {
                        val apk = Updater.download(asset, info, File(act.cacheDir, "hplay-update/$asset"), ua(act), { pct, attempt ->
                            main.post {
                                bar.progress = pct
                                text.text = if (attempt > 1 && pct == 0) act.getString(R.string.upd_retrying, attempt, Updater.ATTEMPTS)
                                else act.getString(R.string.upd_downloading, pct)
                            }
                        })
                        verifyApk(act, apk, m.versionCode)
                        apk
                    }
                    main.post {
                        result.onSuccess { apk ->
                            text.text = act.getString(R.string.upd_installer)
                            InstallReceiver.onFailure = { msg ->
                                text.text = act.getString(R.string.upd_install_failed, msg)
                                go.isEnabled = true
                                go.setText(R.string.upd_retry)
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
                            }
                            runCatching { install(act, apk) }.onFailure { InstallReceiver.onFailure?.invoke(it.message ?: "error") }
                        }.onFailure { e ->
                            bar.visibility = View.GONE
                            text.text = act.getString(if (e is SecurityException) R.string.upd_bad else R.string.upd_fail)
                            go.isEnabled = true
                            go.setText(R.string.upd_retry)
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
                        }
                    }
                }
            }
        }
        current = dialog
        dialog.show()
    }

    /** La APK debe ser este mismo paquete, la versión anunciada y firmada con la misma clave que la instalada. */
    private fun verifyApk(ctx: Context, apk: File, versionCode: Long) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw SecurityException("not_apk")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (archive.packageName != ctx.packageName || code != versionCode) throw SecurityException("wrong_package")
        val mine = certs(pm.getPackageInfo(ctx.packageName, flags))
        if (mine.isEmpty() || mine.intersect(certs(archive)).isEmpty()) throw SecurityException("wrong_signer")
    }

    private fun certs(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = p.signingInfo ?: return emptySet()
            // signingCertificateHistory incluye claves anteriores si algún día se rota la clave.
            s.apkContentsSigners.orEmpty().toList() + (if (s.hasMultipleSigners()) emptyList() else s.signingCertificateHistory.orEmpty().toList())
        } else @Suppress("DEPRECATION") p.signatures.orEmpty().toList()
        return sigs.map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("hplay.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

/** Respuesta del instalador de Android: pide confirmación al usuario o informa del fallo. */
class InstallReceiver : BroadcastReceiver() {
    companion object {
        @Volatile var onFailure: ((String) -> Unit)? = null
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android reinicia la app con la versión nueva
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Handler(Looper.getMainLooper()).post { onFailure?.invoke(msg) }
            }
        }
    }
}
