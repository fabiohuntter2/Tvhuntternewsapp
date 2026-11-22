package com.tvhuntter.radiodj.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.tvhuntter.radiodj.core.AudioFormat
import com.tvhuntter.radiodj.core.PcmRingBuffer
import android.media.AudioFormat as AF

/** Captura o microfone (interno, fone com fio ou USB) em 44.1 kHz e entrega estéreo no ring. */
class MicInput(private val ring: PcmRingBuffer) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val rate = AudioFormat.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AF.CHANNEL_IN_MONO, AF.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AF.CHANNEL_IN_MONO, AF.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, AudioFormat.BLOCK_FRAMES * 4),
            )
        } catch (e: Exception) {
            Log.e("MicInput", "AudioRecord falhou", e); return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); return false
        }
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { setEnabled(true) } }.getOrNull() else null
        ring.clear()
        running = true
        thread = Thread({
            val mono = ShortArray(AudioFormat.BLOCK_FRAMES / 2)
            val stereo = ShortArray(mono.size * 2)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(mono, 0, mono.size)
                    if (n <= 0) continue
                    for (i in 0 until n) {
                        stereo[i * 2] = mono[i]; stereo[i * 2 + 1] = mono[i]
                    }
                    ring.writeOverwrite(stereo, 0, n * 2)
                }
            } catch (e: Exception) {
                Log.e("MicInput", "Erro no microfone", e)
            } finally {
                runCatching { record.stop() }
                runCatching { ns?.release() }
                record.release()
                running = false
            }
        }, "mic-input").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
        ring.clear()
    }
}
