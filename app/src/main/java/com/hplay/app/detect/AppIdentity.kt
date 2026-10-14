package com.hplay.app.detect

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import java.net.InetSocketAddress

/**
 * Qué app es dueña de una conexión. Android solo lo responde a la app que tiene la VPN activa
 * (`getConnectionOwnerUid`, API 29): con el monitor DNS encendido, HPlay puede nombrar a la app que pregunta un
 * dominio o que se conecta a su receptor DLNA. Sin VPN propia activa devuelve null: no hay otra vía legítima.
 */
object AppIdentity {
    fun owner(ctx: Context, protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): String? {
        if (!MediaDetector.dnsOn.value) return null
        val uid = runCatching { ctx.getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(protocol, local, remote) }
            .getOrNull() ?: return null
        if (uid == Process.INVALID_UID) return null
        return label(ctx, uid)
    }

    fun label(ctx: Context, uid: Int): String {
        val pm = ctx.packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return pm.getNameForUid(uid) ?: "uid $uid"
        val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .recoverCatching { if (it is PackageManager.NameNotFoundException) pkg else throw it }.getOrDefault(pkg)
        return if (name == pkg) pkg else "$name ($pkg)"
    }
}
