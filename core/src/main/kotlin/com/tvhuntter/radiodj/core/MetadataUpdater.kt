package com.tvhuntter.radiodj.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/** Atualiza o "tocando agora" no servidor (melhor esforço; erros são ignorados). */
object MetadataUpdater {

    fun buildUrl(config: StreamConfig, title: String, port: Int = config.port): String {
        val song = URLEncoder.encode(title, "UTF-8")
        val host = config.cleanHost()
        return when (config.type) {
            ServerType.SHOUTCAST -> {
                val pass = URLEncoder.encode(config.shoutcastPassword, "UTF-8")
                "http://$host:$port/admin.cgi?pass=$pass&mode=updinfo&song=$song"
            }
            ServerType.ICECAST -> {
                val mount = URLEncoder.encode(config.normalizedMount, "UTF-8")
                "http://$host:$port/admin/metadata?mount=$mount&mode=updinfo&song=$song"
            }
        }
    }

    /**
     * Envia o título. Para SHOUTcast, quando o áudio foi para porta+1, o painel
     * administrativo fica na porta base.
     */
    fun send(config: StreamConfig, title: String, connectedPort: Int): Boolean {
        val ports = when (config.type) {
            ServerType.SHOUTCAST ->
                if (connectedPort != config.port) listOf(config.port) else listOf(config.port, config.port - 1)
            ServerType.ICECAST -> listOf(config.port)
        }.filter { it in 1..65535 }
        for (p in ports) {
            try {
                val conn = URL(buildUrl(config, title, p)).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (TVHuntterDJ; SHOUTcast compatible)")
                if (config.type == ServerType.ICECAST) {
                    val auth = Base64.getEncoder()
                        .encodeToString("${config.icecastUser}:${config.password}".toByteArray(Charsets.UTF_8))
                    conn.setRequestProperty("Authorization", "Basic $auth")
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return true
            } catch (_: Exception) {
                // tenta a próxima porta
            }
        }
        return false
    }
}
