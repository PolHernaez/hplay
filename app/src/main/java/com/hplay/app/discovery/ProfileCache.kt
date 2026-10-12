package com.hplay.app.discovery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perfil cacheado por IP: lo que dijo la tele por UPnP y lo que dijo su navegador al conectarse.
 * Sirve para elegir resolución/perfil H.264 la próxima vez sin volver a preguntar.
 */
class ProfileCache(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    data class Profile(
        val sinks: List<String> = emptyList(),
        val mse: Boolean? = null,
        val high: Boolean? = null,
        val aac: Boolean? = null,
        val screenLong: Int? = null,
        val userAgent: String? = null,
    )

    fun get(ip: String): Profile? = prefs.getString(ip, null)?.let { s ->
        runCatching {
            val o = JSONObject(s)
            val arr = o.optJSONArray("sinks") ?: JSONArray()
            Profile(
                sinks = List(arr.length()) { arr.getString(it) },
                mse = if (o.has("mse")) o.getBoolean("mse") else null,
                high = if (o.has("high")) o.getBoolean("high") else null,
                aac = if (o.has("aac")) o.getBoolean("aac") else null,
                screenLong = if (o.has("screenLong")) o.getInt("screenLong") else null,
                userAgent = o.optString("ua").ifBlank { null },
            )
        }.getOrNull()
    }

    private fun put(ip: String, p: Profile) {
        val o = JSONObject().put("sinks", JSONArray(p.sinks))
        p.mse?.let { o.put("mse", it) }
        p.high?.let { o.put("high", it) }
        p.aac?.let { o.put("aac", it) }
        p.screenLong?.let { o.put("screenLong", it) }
        p.userAgent?.let { o.put("ua", it) }
        prefs.edit().putString(ip, o.toString()).apply()
    }

    fun updateSinks(ip: String, sinks: List<String>) {
        if (sinks.isNotEmpty()) put(ip, (get(ip) ?: Profile()).copy(sinks = sinks))
    }

    /** Mensaje «hello» del receptor web: {caps:{mse,base,high,aac,hls}, w, h, ua}. */
    fun updateFromBrowser(ip: String, hello: JSONObject) {
        val c = hello.optJSONObject("caps") ?: JSONObject()
        val long = maxOf(hello.optInt("w"), hello.optInt("h")).takeIf { it > 0 }
        put(
            ip, (get(ip) ?: Profile()).copy(
                mse = c.optBoolean("mse") && c.optBoolean("base"),
                high = c.optBoolean("high"),
                aac = c.optBoolean("aac"),
                screenLong = long,
                userAgent = hello.optString("ua").take(300),
            )
        )
    }
}
