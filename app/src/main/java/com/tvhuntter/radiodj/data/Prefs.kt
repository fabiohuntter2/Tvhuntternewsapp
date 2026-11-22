package com.tvhuntter.radiodj.data

import android.content.Context
import android.content.SharedPreferences
import com.tvhuntter.radiodj.core.ServerType
import com.tvhuntter.radiodj.core.StreamConfig
import org.json.JSONArray
import org.json.JSONObject

data class Track(val uri: String, val title: String)

data class MixSettings(
    val musicVolume: Float = 0.8f,
    val micVolume: Float = 1.0f,
    val duckLevel: Float = 0.2f,
    val autoDuck: Boolean = true,
    val duckAttackMs: Int = 400,
    val duckReleaseMs: Int = 900,
    val monitor: Boolean = true,
    val repeat: Boolean = true,
    val shuffle: Boolean = false,
)

/** Configurações salvas somente no aparelho. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("radiodj", Context.MODE_PRIVATE)

    fun loadConfig(): StreamConfig = StreamConfig(
        type = runCatching { ServerType.valueOf(sp.getString("type", null) ?: "") }.getOrDefault(ServerType.SHOUTCAST),
        // Valores iniciais do painel MXCast (a senha é digitada no app e fica só no celular)
        host = sp.getString("host", "stm1.mxcast.com.br")!!,
        port = sp.getInt("port", 35104),
        mount = sp.getString("mount", "/live")!!,
        user = sp.getString("user", "aovivoapp")!!,
        password = sp.getString("password", "")!!,
        bitrateKbps = sp.getInt("bitrate", 128),
        stationName = sp.getString("station", "TV Huntter")!!,
        genre = sp.getString("genre", "Variedades")!!,
        url = sp.getString("url", "")!!,
    )

    fun saveConfig(c: StreamConfig) {
        sp.edit()
            .putString("type", c.type.name)
            .putString("host", c.host.trim())
            .putInt("port", c.port)
            .putString("mount", c.mount.trim())
            .putString("user", c.user.trim())
            .putString("password", c.password)
            .putInt("bitrate", c.bitrateKbps)
            .putString("station", c.stationName)
            .putString("genre", c.genre)
            .putString("url", c.url)
            .apply()
    }

    fun loadMix(): MixSettings = MixSettings(
        musicVolume = sp.getFloat("musicVol", 0.8f),
        micVolume = sp.getFloat("micVol", 1.0f),
        duckLevel = sp.getFloat("duck", 0.2f),
        autoDuck = sp.getBoolean("autoDuck", true),
        duckAttackMs = sp.getInt("attack", 400),
        duckReleaseMs = sp.getInt("release", 900),
        monitor = sp.getBoolean("monitor", true),
        repeat = sp.getBoolean("repeat", true),
        shuffle = sp.getBoolean("shuffle", false),
    )

    fun saveMix(m: MixSettings) {
        sp.edit()
            .putFloat("musicVol", m.musicVolume)
            .putFloat("micVol", m.micVolume)
            .putFloat("duck", m.duckLevel)
            .putBoolean("autoDuck", m.autoDuck)
            .putInt("attack", m.duckAttackMs)
            .putInt("release", m.duckReleaseMs)
            .putBoolean("monitor", m.monitor)
            .putBoolean("repeat", m.repeat)
            .putBoolean("shuffle", m.shuffle)
            .apply()
    }

    fun loadPlaylist(): List<Track> {
        val raw = sp.getString("playlist", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Track(o.getString("u"), o.getString("t"))
            }
        }.getOrDefault(emptyList())
    }

    fun savePlaylist(list: List<Track>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.uri).put("t", it.title)) }
        sp.edit().putString("playlist", arr.toString()).apply()
    }
}
