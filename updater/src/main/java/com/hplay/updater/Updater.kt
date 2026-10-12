package com.hplay.updater

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Actualizaciones de HPlay. Una sola fuente de verdad: el último release de GitHub.
 *  - https://github.com/PolHernaez/hplay/releases/latest/download/update.json  (versión, mínimo obligatorio, tamaño y SHA-256 de cada fichero)
 *  - …/update.json.sig: firma RSA-SHA256 de update.json con la clave privada que solo tiene Pol (HPlay-claus).
 *    Sin firma válida no se hace caso al manifiesto: aunque alguien entrara en la cuenta de GitHub no podría colar una APK.
 *  - Baixada: 4 intentos con espera creciente, reanudación con Range, tamaño exacto y SHA-256 del manifiesto.
 * Esta parte no depende de Android (se prueba en la JVM).
 */
object Updater {
    const val BASE = "https://github.com/PolHernaez/hplay/releases/latest/download/"
    const val ATTEMPTS = 4

    /** Clave pública de tools/update-public.pem (RSA 3072). */
    private const val PUBLIC_KEY =
        "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA9DOhVLEt3KmMsYLpoq/TNef9kBmFl5CRxhZtQ94nxLOELlZCq/M7m1eglau0hXAJ8SKDin7XXwSpBorwnzKmZQll5Xvj" +
            "yYgRtR91UW6YK0uSwy8M9sFOiVp0Xy2veq9se3ItPilDWBLa27yknrBYlEn9kLry2h439rdQOY5yZ7uD9uVAbgElEN5jMXsyK7RgZNo/VYbRAziE02p/MTAdrRVhGYTN9w7XS58x" +
            "LlZEsFutzBCJ0uiRn0FZ/luyADbMu1du3QKCNXfCdNOiRnO+7/toaRK9yxvEEM9Sg+T3LenMCFwq+Zhj60La5BepzQNpUnnXXyprv4Mq9lKWhA4aMNkPcRMBGvfk2OpUbrpX2PQ/" +
            "UzyvGQV9VaaO4Y6G4eYtcNu+Sm+lp7JqNhRmffPOMZ4GC8Te+pnRK83b9oThNKv3ggQeoa/S4i2J7TiODGuJjhEFiq8iL0taorsCTjc57n3AqrCDXj2hkhlJ43mNrl/T2OBlYqZa" +
            "jyhJnACHIRKzAgMBAAE="

    data class FileInfo(val size: Long, val sha256: String, val packageName: String?)

    data class Manifest(
        val versionName: String,
        val versionCode: Long,
        val minRequired: Long,
        val notes: String,
        val files: Map<String, FileInfo>,
    )

    enum class State { LATEST, NEWER, FORCED }

    fun state(installedCode: Long, m: Manifest?): State = when {
        m == null || m.versionCode <= installedCode -> State.LATEST
        installedCode < m.minRequired -> State.FORCED
        else -> State.NEWER
    }

    fun parse(json: String): Manifest {
        val o = JSONObject(json)
        val f = o.getJSONObject("files")
        val files = f.keys().asSequence().associateWith { k ->
            val e = f.getJSONObject(k)
            FileInfo(e.getLong("size"), e.getString("sha256").lowercase(), e.optString("package").ifBlank { null })
        }
        return Manifest(o.getString("version"), o.getLong("versionCode"), o.optLong("minRequired", 0), o.optString("notes"), files)
    }

    fun verifySignature(data: ByteArray, sig: ByteArray, publicKeyB64: String = PUBLIC_KEY): Boolean = runCatching {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance("SHA256withRSA").run { initVerify(key); update(data); verify(sig) }
    }.getOrDefault(false)

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String, ua: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", ua)
        try {
            if (c.responseCode != 200) throw IOException("http_${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Bloqueante. Manifiesto con firma comprobada, o excepción. */
    fun fetchManifest(ua: String, base: String = BASE): Manifest {
        val json = get(base + "update.json", ua)
        val sig = get(base + "update.json.sig", ua)
        if (!verifySignature(json, sig)) throw SecurityException("bad_signature")
        return parse(String(json, Charsets.UTF_8))
    }

    fun interface Progress { fun update(percent: Int, attempt: Int) }

    /**
     * Bloqueante. Baja [asset] a [target]: hasta [ATTEMPTS] intentos (2 s, 4 s, 6 s), sigue donde lo dejó si el
     * servidor acepta Range y solo devuelve el fichero si coincide en tamaño y SHA-256 con el manifiesto firmado.
     */
    fun download(asset: String, info: FileInfo, target: File, ua: String, progress: Progress, base: String = BASE): File {
        target.parentFile?.mkdirs()
        target.delete()
        var error = "error"
        for (attempt in 1..ATTEMPTS) {
            if (attempt > 1) Thread.sleep(2_000L * (attempt - 1))
            var conn: HttpURLConnection? = null
            try {
                var have = if (target.exists()) target.length() else 0L
                if (have >= info.size) break
                conn = (URL(base + asset).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", ua)
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("http_$code")
                if (code == 200) have = 0L // el servidor no reanuda: desde cero
                conn.inputStream.use { input ->
                    FileOutputStream(target, have > 0).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = have
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = (done * 100 / info.size).toInt().coerceAtMost(99)
                            if (pct != last) { last = pct; progress.update(pct, attempt) }
                        }
                    }
                }
                if (target.length() != info.size) throw IOException("incomplete ${target.length()}/${info.size}")
                error = ""
                break
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                if (target.length() > info.size) target.delete()
            } finally {
                conn?.disconnect()
            }
        }
        if (error.isNotEmpty() && target.length() != info.size) { target.delete(); throw IOException(error) }
        if (sha256(target) != info.sha256) { target.delete(); throw SecurityException("bad_hash") }
        progress.update(100, 0)
        return target
    }
}
