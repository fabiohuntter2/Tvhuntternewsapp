package com.tvhuntter.radiodj.audio

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.tvhuntter.radiodj.core.AudioFormat
import com.tvhuntter.radiodj.core.PcmRingBuffer
import com.tvhuntter.radiodj.core.Resampler
import java.nio.ByteOrder

/**
 * Decodifica uma música (MP3, AAC/M4A, OGG, FLAC, WAV...) com MediaCodec e entrega
 * PCM 44.1 kHz estéreo no [ring]. O mixer consome o ring no ritmo do tempo real.
 */
class MusicDeck(
    private val context: Context,
    private val ring: PcmRingBuffer,
    private val listener: Listener,
) {
    interface Listener {
        /** A música terminou de ser decodificada E tocada. */
        fun onTrackFinished(token: Int)
        fun onTrackError(token: Int, message: String)
        fun onDuration(token: Int, durationMs: Long)
    }

    @Volatile private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var seekRequestUs = -1L
    /** Posição (µs) do último PCM escrito no ring. */
    @Volatile private var decodedPositionUs = 0L
    private var token = 0

    /** Posição aproximada do que está sendo ouvido (desconta o que ainda está no buffer). */
    val positionMs: Long
        get() {
            val bufferedUs = ring.available().toLong() / AudioFormat.CHANNELS * 1_000_000L / AudioFormat.SAMPLE_RATE
            return ((decodedPositionUs - bufferedUs) / 1000).coerceAtLeast(0)
        }

    @Synchronized
    fun load(uri: Uri): Int {
        stopInternal()
        ring.clear()
        decodedPositionUs = 0
        seekRequestUs = -1
        stopFlag = false
        val myToken = ++token
        thread = Thread({ decodeLoop(uri, myToken) }, "music-decoder").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
        return myToken
    }

    fun seekTo(ms: Long) {
        seekRequestUs = ms * 1000
        thread?.interrupt() // acorda caso esteja bloqueado no ring cheio
    }

    @Synchronized
    fun stop() {
        stopInternal()
        ring.clear()
        decodedPositionUs = 0
    }

    private fun stopInternal() {
        val t = thread ?: return
        stopFlag = true
        t.interrupt()
        ring.clear()
        try { t.join(1500) } catch (_: InterruptedException) {}
        thread = null
    }

    private fun decodeLoop(uri: Uri, myToken: Int) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) {
                listener.onTrackError(myToken, "Arquivo sem áudio compatível")
                return
            }
            extractor.selectTrack(trackIndex)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                listener.onDuration(myToken, format.getLong(MediaFormat.KEY_DURATION) / 1000)
            }
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var resampler = Resampler(sampleRate, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var shorts = ShortArray(0)

            while (!outputDone && !stopFlag) {
                val seek = seekRequestUs
                if (seek >= 0) {
                    seekRequestUs = -1
                    Thread.interrupted()
                    extractor.seekTo(seek, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    c.flush()
                    ring.clear()
                    inputDone = false
                    resampler = Resampler(sampleRate, channels)
                    decodedPositionUs = seek
                }
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AndroidAudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(sampleRate, channels)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val ob = c.getOutputBuffer(outIdx)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val samples: Int
                            if (pcmFloat) {
                                val fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                samples = fb.remaining()
                                if (shorts.size < samples) shorts = ShortArray(samples)
                                for (k in 0 until samples) {
                                    shorts[k] = (fb.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                }
                            } else {
                                val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                                samples = sb.remaining()
                                if (shorts.size < samples) shorts = ShortArray(samples)
                                sb.get(shorts, 0, samples)
                            }
                            c.releaseOutputBuffer(outIdx, false)
                            val frames = samples / channels.coerceAtLeast(1)
                            val pcm = resampler.process(shorts, frames)
                            var written = false
                            while (!written) {
                                if (ring.writeBlocking(pcm, 0, pcm.size)) {
                                    written = true
                                } else {
                                    Thread.interrupted() // limpa a interrupção
                                    if (stopFlag || seekRequestUs >= 0) break
                                }
                            }
                            if (!written) {
                                // interrompido por seek ou stop
                                if (stopFlag) break
                                continue
                            }
                            decodedPositionUs = info.presentationTimeUs
                        } else {
                            c.releaseOutputBuffer(outIdx, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            if (outputDone && !stopFlag) {
                // espera o mixer terminar de tocar o que está no buffer
                while (!stopFlag && ring.available() > AudioFormat.BLOCK_FRAMES * AudioFormat.CHANNELS) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { if (stopFlag) break }
                }
                if (!stopFlag) listener.onTrackFinished(myToken)
            }
        } catch (e: Exception) {
            if (!stopFlag) {
                Log.e("MusicDeck", "Erro ao tocar $uri", e)
                listener.onTrackError(myToken, e.message ?: "Erro ao abrir a música")
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
