package com.hplay.app.cast

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Kodi por su API JSON-RPC (HTTP, «Permitir control remoto por HTTP» en Kodi). Las cabeceras viajan en la propia URL
 * con la sintaxis de Kodi `url|Referer=…&User-Agent=…`, así que no hace falta el proxy del móvil.
 */
class KodiRemote(private val ip: String, private val port: Int = 8080) : Remote {
    fun load(url: String, headers: Map<String, String>) {
        // shortcut: Kodi solo garantiza User-Agent, Referer y Cookie; con otras cabeceras, usar el proxy.
        val opts = headers.filterKeys { it.lowercase() in setOf("referer", "user-agent", "cookie", "origin") }
            .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        rpc("Player.Open", JSONObject().put("item", JSONObject().put("file", if (opts.isEmpty()) url else "$url|$opts")))
    }

    override fun play() { if (!status().playing) rpc("Player.PlayPause", player()) }
    override fun pause() { if (status().playing) rpc("Player.PlayPause", player()) }
    override fun stop() { rpc("Player.Stop", player()) }
    override fun seek(sec: Long) { rpc("Player.Seek", player().put("value", JSONObject().put("seconds", sec))) }
    override fun setVolume(v: Int) { rpc("Application.SetVolume", JSONObject().put("volume", v)) }

    override fun status(): Remote.Status {
        val r = rpc("Player.GetProperties", player().put("properties", org.json.JSONArray(listOf("time", "totaltime", "speed")))).optJSONObject("result")
            ?: return Remote.Status(0, 0, false, null)
        fun secs(o: JSONObject?) = o?.let { it.optLong("hours") * 3600 + it.optLong("minutes") * 60 + it.optLong("seconds") } ?: 0
        val vol = rpc("Application.GetProperties", JSONObject().put("properties", org.json.JSONArray(listOf("volume")))).optJSONObject("result")?.optInt("volume")
        return Remote.Status(secs(r.optJSONObject("time")), secs(r.optJSONObject("totaltime")), r.optInt("speed") != 0, vol)
    }

    /** El reproductor de vídeo de Kodi es el 1. */
    private fun player() = JSONObject().put("playerid", 1)

    private fun rpc(method: String, params: JSONObject): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString().toByteArray()
        val c = URL("http://$ip:$port/jsonrpc").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 3_000
        c.readTimeout = 6_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        return try {
            c.outputStream.use { it.write(body) }
            JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).also { r ->
                r.optJSONObject("error")?.let { error("Kodi $method: ${it.optString("message")}") }
            }
        } finally { c.disconnect() }
    }
}
