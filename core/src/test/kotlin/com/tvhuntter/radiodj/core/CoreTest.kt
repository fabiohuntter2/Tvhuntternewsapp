package com.tvhuntter.radiodj.core

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {

    private fun sine(frames: Int, freq: Double = 440.0, amp: Double = 0.5, start: Int = 0): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * freq * (i + start) / AudioFormat.SAMPLE_RATE) * amp * 32767).toInt().toShort()
            out[i * 2] = v; out[i * 2 + 1] = v
        }
        return out
    }

    private fun countMp3Frames(data: ByteArray): Int {
        var n = 0
        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && (data[i + 1].toInt() and 0xFE) == 0xFA) n++
        }
        return n
    }

    @Test
    fun encodesMp3() {
        val enc = Mp3Encoder(bitrateKbps = 128)
        val bos = ByteArrayOutputStream()
        val t0 = System.nanoTime()
        repeat(200) { k -> bos.write(enc.encode(sine(1152, start = k * 1152), 1152)) }
        bos.write(enc.flush())
        val ms = (System.nanoTime() - t0) / 1e6
        val bytes = bos.toByteArray()
        // 200 * 1152 / 44100 = 5.22 s a 128 kbps ~= 83 KB
        println("MP3: ${bytes.size} bytes em ${ms}ms (5.2 s de áudio)")
        assertTrue(bytes.size in 70_000..100_000, "tamanho ${bytes.size}")
        assertTrue(countMp3Frames(bytes) >= 190)
    }

    @Test
    fun ducksMusicWhenMicOpens() {
        val mixer = DuckingMixer()
        mixer.musicVolume = 1f
        mixer.duckLevel = 0.2f
        val music = sine(1152, amp = 0.5)
        val out = ShortArray(1152 * 2)
        repeat(5) { mixer.mix(music, null, out, 1152) }
        assertEquals(1f, mixer.currentDuckGain)
        mixer.micOn = true
        repeat(40) { mixer.mix(music, null, out, 1152) } // ~1 s
        assertEquals(0.2f, mixer.currentDuckGain, 0.001f)
        assertTrue(mixer.musicPeak < 0.12f)
        mixer.micOn = false
        repeat(60) { mixer.mix(music, null, out, 1152) }
        assertEquals(1f, mixer.currentDuckGain)
    }

    @Test
    fun callerOnAirDucksAndMixMinus() {
        val mixer = DuckingMixer()
        mixer.musicVolume = 1f
        val music = sine(1152, amp = 0.5)
        val caller = sine(1152, freq = 1000.0, amp = 0.5)
        val mic = sine(1152, freq = 300.0, amp = 0.5)
        val out = ShortArray(1152 * 2)
        val feed = ShortArray(1152)
        // fora do ar: ouvinte não sai no programa, e ele só ouve o locutor
        repeat(10) { mixer.mix(music, mic, out, 1152, caller = caller, callerFeed = feed) }
        assertEquals(1f, mixer.currentDuckGain)
        val musicOnly = ShortArray(1152 * 2)
        DuckingMixer().apply { musicVolume = 1f }.mix(music, null, musicOnly, 1152)
        assertTrue(out.indices.all { kotlin.math.abs(out[it] - musicOnly[it]) <= 1 }, "programa deveria ter só música")
        assertTrue(feed.indices.all { kotlin.math.abs(feed[it] - mic[it * 2]) <= 2 }, "retorno reservado deveria ser só o mic")
        // no ar: música abaixa
        mixer.callerOnAir = true
        repeat(40) { mixer.mix(music, mic, out, 1152, caller = caller, callerFeed = feed) }
        assertEquals(0.2f, mixer.currentDuckGain, 0.001f)
        assertTrue(mixer.callerPeak > 0.4f)
    }

    @Test
    fun softClipNeverExceedsOne() {
        for (x in listOf(-5f, -1.2f, -0.5f, 0f, 0.9f, 1f, 3f)) {
            val y = DuckingMixer.softClip(x)
            assertTrue(y in -1f..1f)
        }
        assertEquals(0.5f, DuckingMixer.softClip(0.5f))
    }

    @Test
    fun resamples48kTo44k() {
        val r = Resampler(48000, 1)
        var total = 0
        repeat(48) { total += r.process(ShortArray(1000) { 1000 }, 1000).size / 2 }
        // 48000 quadros de entrada => ~44100 de saída
        assertTrue(total in 44090..44110, "total=$total")
    }

    @Test
    fun ringBuffer() {
        val rb = PcmRingBuffer(10)
        rb.writeOverwrite(ShortArray(15) { it.toShort() }, 0, 15)
        val dst = ShortArray(10)
        assertEquals(10, rb.read(dst, 0, 10))
        assertEquals(5, dst[0].toInt())
    }

    @Test
    fun shoutcastHandshakeAndStream() {
        val server = ServerSocket(0)
        val received = ByteArrayOutputStream()
        var password = ""
        var headers = ""
        val gotData = CountDownLatch(1)
        thread {
            server.accept().use { s ->
                val inp = s.getInputStream()
                password = generateSequence { inp.read().takeIf { it != '\n'.code && it != -1 } }
                    .map { it.toChar() }.joinToString("").trim()
                s.getOutputStream().write("OK2\r\nicy-caps:11\r\n\r\n".toByteArray())
                val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) sb.append(inp.read().toChar())
                headers = sb.toString()
                val buf = ByteArray(4096)
                while (received.size() < 20_000) {
                    val n = inp.read(buf); if (n < 0) break
                    received.write(buf, 0, n)
                }
                gotData.countDown()
            }
        }
        val cfg = StreamConfig(ServerType.SHOUTCAST, "127.0.0.1", server.localPort, user = "dj1", password = "segredo")
        val states = mutableListOf<LiveState>()
        val b = Broadcaster(cfg, { states += it.state })
        b.start()
        val deadline = System.currentTimeMillis() + 10_000
        var k = 0
        while (gotData.count > 0 && System.currentTimeMillis() < deadline) {
            b.submit(sine(1152, start = k++ * 1152))
            Thread.sleep(5)
        }
        b.stop()
        assertTrue(gotData.await(1, TimeUnit.SECONDS))
        assertEquals("dj1:segredo", password)
        assertTrue(headers.contains("content-type:audio/mpeg"))
        assertTrue(LiveState.LIVE in states)
        assertTrue(countMp3Frames(received.toByteArray()) > 20)
    }

    @Test
    fun icecastHandshake() {
        val server = ServerSocket(0)
        var request = ""
        val done = CountDownLatch(1)
        thread {
            server.accept().use { s ->
                val inp = s.getInputStream()
                val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) sb.append(inp.read().toChar())
                request = sb.toString()
                s.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\n".toByteArray())
                inp.read(ByteArray(1000))
                done.countDown()
            }
        }
        val cfg = StreamConfig(ServerType.ICECAST, "127.0.0.1", server.localPort, mount = "live", password = "abc")
        val c = IcecastClient(cfg)
        c.connect()
        c.write(ByteArray(100) { 1 })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        c.close()
        assertTrue(request.startsWith("SOURCE /live HTTP/1.0"))
        assertTrue(request.contains("Authorization: Basic c291cmNlOmFiYw=="))
    }

    @Test
    fun icecastWrongPasswordIsAuthError() {
        val server = ServerSocket(0)
        thread {
            server.accept().use { s ->
                val inp = s.getInputStream()
                val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) sb.append(inp.read().toChar())
                s.getOutputStream().write("HTTP/1.0 401 Unauthorized\r\n\r\n".toByteArray())
            }
        }
        val cfg = StreamConfig(ServerType.ICECAST, "127.0.0.1", server.localPort, password = "x")
        var err: Exception? = null
        try { IcecastClient(cfg).connect() } catch (e: Exception) { err = e }
        assertTrue(err is AuthException)
    }

    @Test
    fun shoutcastFallsBackToPortPlusOne() {
        // procura uma porta livre p cuja p+1 também possa ser aberta
        var server: ServerSocket? = null
        var base = 0
        for (attempt in 0 until 50) {
            val probe = ServerSocket(0); base = probe.localPort - 1; probe.close()
            try { server = ServerSocket(base + 1); break } catch (_: Exception) {}
        }
        val srv = server!!
        thread {
            srv.accept().use { s ->
                val inp = s.getInputStream()
                while (inp.read() != '\n'.code) {}
                s.getOutputStream().write("OK2\r\n\r\n".toByteArray())
                Thread.sleep(300)
            }
        }
        val c = ShoutcastClient(StreamConfig(ServerType.SHOUTCAST, "127.0.0.1", base, password = "p"))
        c.connect()
        assertEquals(base + 1, c.connectedPort)
        c.close()
    }
}
