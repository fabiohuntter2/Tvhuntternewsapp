package com.tvhuntter.radiodj.core

enum class ServerType(val label: String) {
    SHOUTCAST("SHOUTcast / Porta DJ"),
    ICECAST("Icecast / AzuraCast"),
}

data class StreamConfig(
    val type: ServerType = ServerType.SHOUTCAST,
    val host: String = "",
    val port: Int = 8000,
    /** Somente Icecast: ponto de montagem (ex.: /live). */
    val mount: String = "/live",
    /** Usuário do DJ (opcional no SHOUTcast; no Icecast o padrão é "source"). */
    val user: String = "",
    val password: String = "",
    val bitrateKbps: Int = 128,
    val stationName: String = "Minha Rádio",
    val genre: String = "Variedades",
    val url: String = "",
    val isPublic: Boolean = true,
) {
    val isValid: Boolean get() = host.isNotBlank() && port in 1..65535 && password.isNotBlank()

    /** Senha enviada ao SHOUTcast: "usuario:senha" quando houver usuário de DJ. */
    val shoutcastPassword: String
        get() = if (user.isNotBlank()) "${user.trim()}:$password" else password

    val icecastUser: String get() = user.ifBlank { "source" }

    val normalizedMount: String
        get() = mount.trim().let { if (it.startsWith("/")) it else "/$it" }.ifBlank { "/" }

    fun cleanHost(): String = host.trim()
        .removePrefix("http://").removePrefix("https://")
        .substringBefore("/").substringBefore(":")
}
