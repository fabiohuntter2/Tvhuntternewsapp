package com.tvhuntter.radiodj.core

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/** Senha/usuário recusados pelo servidor - não adianta reconectar. */
class AuthException(message: String) : IOException(message)

/** Servidor recusou por outro motivo (ponto de montagem ocupado, etc.). */
class ServerRefusedException(message: String) : IOException(message)

/** Conexão de envio (source) para o servidor de streaming. */
interface StreamClient : AutoCloseable {
    fun connect()
    fun write(data: ByteArray)
    /** Porta efetivamente usada na conexão. */
    val connectedPort: Int
    override fun close()

    companion object {
        fun create(config: StreamConfig): StreamClient = when (config.type) {
            ServerType.SHOUTCAST -> ShoutcastClient(config)
            ServerType.ICECAST -> IcecastClient(config)
        }
    }
}

private const val CONNECT_TIMEOUT_MS = 10_000
private const val USER_AGENT = "TVHuntterDJ/1.0 (Android)"

private fun openSocket(host: String, port: Int): Socket {
    val s = Socket()
    s.tcpNoDelay = true
    s.keepAlive = true
    s.soTimeout = CONNECT_TIMEOUT_MS
    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
    return s
}

private fun readLine(input: InputStream, max: Int = 1024): String {
    val sb = StringBuilder()
    while (sb.length < max) {
        val c = input.read()
        if (c == -1) {
            if (sb.isEmpty()) throw IOException("Servidor fechou a conexão")
            break
        }
        if (c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
    }
    return sb.toString()
}

/**
 * Protocolo SHOUTcast v1 (usado pela "porta DJ" do SHOUTcast, Centova Cast,
 * SonicPanel, sc_trans e também aceito pelo SHOUTcast v2).
 *
 * Se a porta informada recusar a conexão, tenta automaticamente porta+1
 * (padrão do Winamp/SHOUTcast DSP, que envia o áudio na porta base + 1).
 */
class ShoutcastClient(private val config: StreamConfig) : StreamClient {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    override var connectedPort: Int = config.port
        private set

    override fun connect() {
        val host = config.cleanHost()
        val sock = try {
            openSocket(host, config.port).also { connectedPort = config.port }
        } catch (e: ConnectException) {
            if (config.port >= 65535) throw e
            try {
                openSocket(host, config.port + 1).also { connectedPort = config.port + 1 }
            } catch (_: IOException) {
                throw e
            }
        }
        socket = sock
        try {
            val o = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
            o.write("${config.shoutcastPassword}\r\n".toByteArray(Charsets.UTF_8))
            o.flush()
            val resp = readLine(sock.getInputStream()).trim()
            if (!resp.uppercase().startsWith("OK")) {
                if (resp.contains("password", true) || resp.contains("senha", true) || resp.contains("auth", true)) {
                    throw AuthException("Senha recusada pelo servidor ($resp)")
                }
                throw ServerRefusedException("Servidor respondeu: ${resp.ifBlank { "(vazio)" }}")
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

/** Protocolo Icecast 2 (SOURCE / HTTP), usado também por AzuraCast, Liquidsoap, etc. */
class IcecastClient(private val config: StreamConfig) : StreamClient {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    override val connectedPort: Int get() = config.port

    override fun connect() {
        val host = config.cleanHost()
        val sock = openSocket(host, config.port)
        socket = sock
        try {
            val auth = Base64.getEncoder()
                .encodeToString("${config.icecastUser}:${config.password}".toByteArray(Charsets.UTF_8))
            val req = buildString {
                append("SOURCE ${config.normalizedMount} HTTP/1.0\r\n")
                append("Host: $host:${config.port}\r\n")
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
            // consome o restante dos cabeçalhos
            while (true) {
                val line = try { readLine(input) } catch (_: IOException) { "" }
                if (line.isBlank()) break
            }
            val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            when {
                code == 200 -> Unit
                code == 401 -> throw AuthException("Usuário ou senha incorretos (401)")
                code == 403 -> throw ServerRefusedException("Ponto de montagem ocupado ou não permitido (403)")
                code == 404 -> throw ServerRefusedException("Ponto de montagem não encontrado (404)")
                else -> throw ServerRefusedException("Servidor respondeu: ${status.ifBlank { "(vazio)" }}")
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
