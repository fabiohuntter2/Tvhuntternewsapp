package com.tvhuntter.radiodj.core

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class LiveState { OFF, CONNECTING, LIVE, RECONNECTING, ERROR }

data class LiveStatus(
    val state: LiveState = LiveState.OFF,
    val message: String = "Desconectado",
    val bytesSent: Long = 0,
    val liveSinceMs: Long = 0,
    val reconnects: Int = 0,
)

/**
 * Recebe blocos PCM do mixer, codifica em MP3 e envia ao servidor, reconectando
 * sozinho se a internet cair.
 */
class Broadcaster(
    private val config: StreamConfig,
    private val onStatus: (LiveStatus) -> Unit,
    private val clientFactory: (StreamConfig) -> StreamClient = { StreamClient.create(it) },
    private val reconnectDelayMs: Long = 3000,
) {
    // ~5 s de áudio em blocos de 1152 quadros
    private val queue = ArrayBlockingQueue<ShortArray>(200)
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var connectedPort = config.port
    private var thread: Thread? = null
    private val metaExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "metadata").apply { isDaemon = true } }
    @Volatile private var lastTitle: String? = null
    @Volatile private var status = LiveStatus()

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread(::runLoop, "broadcaster").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        metaExecutor.shutdownNow()
    }

    /** Entrega um bloco PCM estéreo (cópia). Nunca bloqueia o mixer. */
    fun submit(pcm: ShortArray) {
        if (!running || !connected) return
        if (!queue.offer(pcm)) {
            queue.poll() // rede lenta: descarta o bloco mais antigo
            queue.offer(pcm)
        }
    }

    fun updateTitle(title: String) {
        lastTitle = title
        if (connected) sendTitleAsync(title)
    }

    private fun sendTitleAsync(title: String) {
        if (metaExecutor.isShutdown) return
        metaExecutor.execute { MetadataUpdater.send(config, title, connectedPort) }
    }

    private fun publish(s: LiveStatus) {
        status = s
        onStatus(s)
    }

    private fun runLoop() {
        var reconnects = 0
        var first = true
        while (running) {
            val client = clientFactory(config)
            try {
                publish(status.copy(
                    state = if (first) LiveState.CONNECTING else LiveState.RECONNECTING,
                    message = if (first) "Conectando…" else "Reconectando…",
                    reconnects = reconnects,
                ))
                client.connect()
                connectedPort = client.connectedPort
                val encoder = Mp3Encoder(bitrateKbps = config.bitrateKbps)
                queue.clear()
                connected = true
                first = false
                val since = System.currentTimeMillis()
                var sent = status.bytesSent
                publish(LiveStatus(LiveState.LIVE, "NO AR", sent, since, reconnects))
                lastTitle?.let { sendTitleAsync(it) }
                var lastReport = 0L
                while (running) {
                    val block = queue.poll(2, TimeUnit.SECONDS) ?: continue
                    val mp3 = encoder.encode(block, block.size / AudioFormat.CHANNELS)
                    if (mp3.isNotEmpty()) {
                        client.write(mp3)
                        sent += mp3.size
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 1000) {
                        lastReport = now
                        publish(status.copy(bytesSent = sent))
                    }
                }
            } catch (e: AuthException) {
                connected = false
                running = false
                publish(LiveStatus(LiveState.ERROR, e.message ?: "Senha incorreta", status.bytesSent))
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                connected = false
                if (!running) break
                reconnects++
                val reason = when (e) {
                    is java.net.UnknownHostException -> "Servidor não encontrado"
                    is java.net.ConnectException -> "Conexão recusada (verifique servidor/porta)"
                    is java.net.SocketTimeoutException -> "Tempo esgotado"
                    is IOException -> e.message ?: "Falha de rede"
                    else -> e.message ?: e.javaClass.simpleName
                }
                publish(status.copy(
                    state = LiveState.RECONNECTING,
                    message = "$reason - nova tentativa em ${reconnectDelayMs / 1000}s",
                    reconnects = reconnects,
                ))
                try {
                    Thread.sleep(reconnectDelayMs)
                } catch (_: InterruptedException) {
                    break
                }
            } finally {
                connected = false
                client.close()
            }
        }
        connected = false
        if (status.state != LiveState.ERROR) {
            publish(LiveStatus(LiveState.OFF, "Desconectado", status.bytesSent))
        }
    }
}
