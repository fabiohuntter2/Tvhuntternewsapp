package com.tvhuntter.radiodj.core

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64

/** Senha/usuário recusados pelo servidor. */
class AuthException(message: String) : IOException(message)

/** Servidor recusou por outro motivo (ponto de montagem ocupado, protocolo errado, etc.). */
class ServerRefusedException(message: String) : IOException(message)

/** Conexão de envio (source) para o servidor de streaming. */
interface StreamClient : AutoCloseable {
    fun connect()
    fun write(data: ByteArray)
    /** Porta efetivamente usada na conexão. */
    val connectedPort: Int
    /** Descrição do modo que conectou (para mostrar ao usuário). */
    val description: String
    override fun close()

    companion object {
        fun create(config: StreamConfig, log: (String) -> Unit = {}): StreamClient = AutoStreamClient(config, log)
    }
}

private const val CONNECT_TIMEOUT_MS = 8_000
private const val USER_AGENT = "TVHuntterDJ/1.0 (Android)"

/** Tempo máximo esperando a resposta do servidor no aperto de mão. */
@Volatile var handshakeTimeoutMs = 6_000

private fun openSocket(host: String, port: Int): Socket {
    val s = Socket()
    s.tcpNoDelay = true
    s.keepAlive = true
    s.soTimeout = handshakeTimeoutMs
    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
    return s
}

private fun readLine(input: InputStream, max: Int = 1024): String {
    val sb = StringBuilder()
    while (sb.length < max) {
        val c = input.read()
        if (c == -1) {
            if (sb.isEmpty()) throw IOException("Servidor fechou a conexão sem responder")
            break
        }
        if (c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
    }
    return sb.toString()
}

fun describeError(e: Exception): String = when (e) {
    is UnknownHostException -> "servidor não encontrado (confira o endereço)"
    is ConnectException -> "porta fechada / conexão recusada"
    is SocketTimeoutException -> "servidor não respondeu (tempo esgotado)"
    is AuthException -> "senha recusada" + (e.message?.let { " — $it" } ?: "")
    is java.net.SocketException -> when {
        e.message?.contains("pipe", true) == true || e.message?.contains("reset", true) == true ->
            "o servidor encerrou a conexão"
        else -> e.message ?: "falha de rede"
    }
    else -> e.message ?: e.javaClass.simpleName
}

/**
 * Protocolo SHOUTcast v1 (Winamp DSP, porta DJ do SHOUTcast/Centova/sc_trans;
 * aceito também pelo SHOUTcast v2 e pelo Liquidsoap na porta+1).
 */
class ShoutcastClient(
    private val config: StreamConfig,
    private val port: Int = config.port,
    private val password: String = config.shoutcastPassword,
) : StreamClient {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    override val connectedPort: Int get() = port
    override val description: String get() = "SHOUTcast na porta $port"

    override fun connect() {
        val host = config.cleanHost()
        val sock = openSocket(host, port)
        socket = sock
        try {
            val o = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
            o.write("$password\r\n".toByteArray(Charsets.UTF_8))
            o.flush()
            val resp = readLine(sock.getInputStream()).trim()
            val up = resp.uppercase()
            if (!up.startsWith("OK")) {
                when {
                    up.startsWith("HTTP/") || up.startsWith("ICY 4") ->
                        throw ServerRefusedException("porta não aceita SHOUTcast (respondeu \"${resp.take(40)}\")")
                    up.contains("PASSWORD") || up.contains("SENHA") || up.contains("AUTH") || up.contains("DENIED") ->
                        throw AuthException(resp.take(60))
                    else -> throw ServerRefusedException("servidor respondeu \"${resp.ifBlank { "(vazio)" }.take(60)}\"")
                }
            }
            val headers = buildString {
                append("content-type:audio/mpeg\r\n")
                append("icy-name:${config.stationName}\r\n")
                append("icy-genre:${config.genre}\r\n")
                append("icy-url:${config.url}\r\n")
                append("icy-pub:${if (config.isPublic) 1 else 0}\r\n")
                append("icy-br:${config.bitrateKbps}\r\n")
                append("\r\n")
            }
            o.write(headers.toByteArray(Charsets.UTF_8))
            o.flush()
            sock.soTimeout = 30_000
            out = o
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override fun write(data: ByteArray) {
        val o = out ?: throw IOException("Não conectado")
        o.write(data)
        o.flush()
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        out = null
    }
}

/** Protocolo Icecast 2 (SOURCE / HTTP) — Icecast, AzuraCast, Liquidsoap (harbor). */
class IcecastClient(
    private val config: StreamConfig,
    private val mount: String = config.normalizedMount,
    private val user: String = config.icecastUser,
    private val port: Int = config.port,
) : StreamClient {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    override val connectedPort: Int get() = port
    override val description: String get() = "Icecast na porta $port, ponto $mount, usuário $user"

    override fun connect() {
        val host = config.cleanHost()
        val sock = openSocket(host, port)
        socket = sock
        try {
            val auth = Base64.getEncoder()
                .encodeToString("$user:${config.password}".toByteArray(Charsets.UTF_8))
            val req = buildString {
                append("SOURCE $mount HTTP/1.0\r\n")
                append("Host: $host:$port\r\n")
                append("Authorization: Basic $auth\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("Content-Type: audio/mpeg\r\n")
                append("Ice-Name: ${config.stationName}\r\n")
                append("Ice-Genre: ${config.genre}\r\n")
                append("Ice-Url: ${config.url}\r\n")
                append("Ice-Public: ${if (config.isPublic) 1 else 0}\r\n")
                append("Ice-Bitrate: ${config.bitrateKbps}\r\n")
                append("Ice-Audio-Info: ice-samplerate=${AudioFormat.SAMPLE_RATE};ice-bitrate=${config.bitrateKbps};ice-channels=${AudioFormat.CHANNELS}\r\n")
                append("\r\n")
            }
            val o = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
            o.write(req.toByteArray(Charsets.UTF_8))
            o.flush()
            val input = sock.getInputStream()
            val status = readLine(input).trim()
            while (true) {
                val line = try { readLine(input) } catch (_: IOException) { "" }
                if (line.isBlank()) break
            }
            val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            when {
                code in 200..299 -> Unit
                code == 401 -> throw AuthException("usuário ou senha incorretos (401)")
                code == 403 -> throw ServerRefusedException("ponto de montagem ocupado ou não permitido (403)")
                code == 404 -> throw ServerRefusedException("ponto de montagem não existe (404)")
                status.uppercase().startsWith("INVALID") || status.uppercase().contains("PASSWORD") ->
                    throw ServerRefusedException("porta não aceita Icecast (respondeu \"${status.take(40)}\")")
                else -> throw ServerRefusedException("servidor respondeu \"${status.ifBlank { "(vazio)" }.take(60)}\"")
            }
            sock.soTimeout = 30_000
            out = o
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override fun write(data: ByteArray) {
        val o = out ?: throw IOException("Não conectado")
        o.write(data)
        o.flush()
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        out = null
    }
}

/**
 * Descobre sozinho como o servidor quer receber o DJ: tenta SHOUTcast e Icecast,
 * na porta informada e na porta+1, com "usuário:senha" e só a senha.
 * A combinação que funcionou é tentada primeiro nas próximas vezes.
 */
class AutoStreamClient(
    private val config: StreamConfig,
    private val log: (String) -> Unit = {},
) : StreamClient {
    private var inner: StreamClient? = null
    override val connectedPort: Int get() = inner?.connectedPort ?: config.port
    override val description: String get() = inner?.description ?: "—"

    data class Candidate(val key: String, val make: () -> StreamClient)

    fun candidates(): List<Candidate> {
        val p = config.port
        val user = config.user.trim()
        val pass = config.password
        val p1 = if (p < 65535) p + 1 else p

        val sc = mutableListOf<Candidate>()
        val passwords = if (user.isNotBlank()) listOf("$user:$pass", pass) else listOf(pass)
        passwords.forEachIndexed { i, pw ->
            for (port in listOf(p, p1).distinct()) {
                sc += Candidate("sc:$port:$i") { ShoutcastClient(config, port, pw) }
            }
        }

        val ic = mutableListOf<Candidate>()
        val mounts = buildList {
            if (config.type == ServerType.ICECAST) add(config.normalizedMount)
            addAll(listOf("/", "/live", "/stream", "/autodj"))
            if (config.type != ServerType.ICECAST) add(config.normalizedMount)
        }.distinct()
        val users = if (user.isNotBlank()) listOf(user, "source") else listOf("source")
        for (u in users) for (m in mounts) {
            ic += Candidate("ic:$p:$m:$u") { IcecastClient(config, m, u, p) }
        }

        val ordered = if (config.type == ServerType.ICECAST) ic + sc else {
            // SHOUTcast primeiro com usuário:senha, depois Icecast nas montagens comuns, depois o resto
            val firstSc = sc.take(2)
            firstSc + ic.take(2) + sc.drop(2) + ic.drop(2)
        }
        val good = lastGood[configKey()]
        return if (good != null) ordered.sortedBy { if (it.key == good) 0 else 1 } else ordered
    }

    private fun configKey() = "${config.cleanHost()}:${config.port}:${config.user}:${config.type}"

    override fun connect() {
        if (config.cleanHost().isBlank()) throw IOException("Servidor não configurado")
        var authErr: AuthException? = null
        var lastErr: Exception? = null
        var hostMissing = false
        for (c in candidates()) {
            if (Thread.currentThread().isInterrupted) throw IOException("Cancelado")
            val client = c.make()
            log("Tentando ${client.description}…")
            try {
                client.connect()
                inner = client
                lastGood[configKey()] = c.key
                log("✔ Conectado: ${client.description}")
                return
            } catch (e: Exception) {
                log("✘ ${describeError(e)}")
                when (e) {
                    is AuthException -> authErr = e
                    is UnknownHostException -> { hostMissing = true; lastErr = e }
                    else -> lastErr = e
                }
                if (hostMissing) break
            }
        }
        throw authErr ?: (lastErr as? IOException) ?: IOException(lastErr?.message ?: "Não foi possível conectar")
    }

    override fun write(data: ByteArray) {
        (inner ?: throw IOException("Não conectado")).write(data)
    }

    override fun close() {
        inner?.close()
        inner = null
    }

    companion object {
        private val lastGood = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Testa a conexão (conecta e solta em seguida) e devolve o passo a passo. */
        fun test(config: StreamConfig): Pair<Boolean, List<String>> {
            val lines = mutableListOf<String>()
            val c = AutoStreamClient(config) { lines += it }
            return try {
                c.connect()
                c.close()
                true to lines
            } catch (e: Exception) {
                lines += "Resultado: ${describeError(e)}"
                false to lines
            }
        }
    }
}
