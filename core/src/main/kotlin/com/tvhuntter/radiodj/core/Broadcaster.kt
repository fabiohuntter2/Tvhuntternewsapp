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
    private val clientFactory: (StreamConfig, (String) -> Unit) -> StreamClient = { c, log -> StreamClient.create(c, log) },
    private val reconnectDelayMs: Long = 3000,
    /** Avisado quando a qualidade é reduzida automaticamente. */
    private val onBitrateChanged: (Int) -> Unit = {},
) {
    /** Qualidade atual (pode ser reduzida se o servidor derrubar a conexão por limite do plano). */
    @Volatile var bitrateKbps: Int = config.bitrateKbps
        private set
    private var earlyDrops = 0
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
            val cfg = config.copy(bitrateKbps = bitrateKbps)
            var liveAt = 0L
            val client = clientFactory(cfg) { line ->
                publish(status.copy(message = line))
            }
            try {
                publish(status.copy(
                    state = if (first) LiveState.CONNECTING else LiveState.RECONNECTING,
                    message = if (first) "Conectando…" else "Reconectando…",
                    reconnects = reconnects,
                ))
                client.connect()
                connectedPort = client.connectedPort
                val encoder = Mp3Encoder(bitrateKbps = bitrateKbps)
                queue.clear()
                connected = true
                first = false
                val since = System.currentTimeMillis()
                liveAt = since
                var sent = status.bytesSent
                publish(LiveStatus(LiveState.LIVE, "NO AR • ${client.description}", sent, since, reconnects))
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
                publish(LiveStatus(LiveState.ERROR, "Senha ou usuário recusados pelo servidor. Confira na aba Servidor.", status.bytesSent))
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                connected = false
                if (!running) break
                reconnects++
                var reason = describeError(e).replaceFirstChar { it.uppercase() }
                // derrubado logo depois de conectar: quase sempre é limite de qualidade do plano
                if (liveAt > 0 && System.currentTimeMillis() - liveAt < 10_000) {
                    earlyDrops++
                    val lower = BITRATES.filter { it < bitrateKbps }.maxOrNull()
                    if (earlyDrops >= 2 && lower != null) {
                        earlyDrops = 0
                        bitrateKbps = lower
                        onBitrateChanged(lower)
                        reason = "O servidor derrubou a conexão logo após conectar (limite de qualidade do plano?). Qualidade reduzida para $lower kbps"
                    } else {
                        reason = "O servidor derrubou a conexão logo após conectar"
                    }
                } else if (liveAt > 0) {
                    earlyDrops = 0
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

    companion object {
        val BITRATES = listOf(32, 48, 64, 96, 128, 192)
    }
}
