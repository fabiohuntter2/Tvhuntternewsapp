package com.tvhuntter.radiodj.core

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib

/**
 * Encoder MP3 CBR em Java puro (LAME/jump3r), sem dependência de NDK.
 * Entrada: PCM 16 bits intercalado (L,R,L,R...).
 */
class Mp3Encoder(
    val sampleRate: Int = AudioFormat.SAMPLE_RATE,
    val channels: Int = AudioFormat.CHANNELS,
    val bitrateKbps: Int = 128,
    quality: Int = 7,
) {
    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var out = ByteArray(0)

    init {
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        val gaud = GetAudio()

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, p)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gfp = lame.lame_init()
        gfp.num_channels = channels
        gfp.in_samplerate = sampleRate
        gfp.out_samplerate = sampleRate
        gfp.mode = if (channels == 2) MPEGMode.JOINT_STEREO else MPEGMode.MONO
        gfp.VBR = VbrMode.vbr_off
        gfp.brate = bitrateKbps
        gfp.quality = quality
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        gfp.bWriteVbrTag = false
        val rc = lame.lame_init_params(gfp)
        require(rc >= 0) { "Parâmetros MP3 não suportados (código $rc)" }
    }

    /**
     * Codifica [frames] quadros de [pcm] (intercalado) e devolve os bytes MP3 gerados
     * (pode ser vazio enquanto o encoder acumula amostras).
     */
    fun encode(pcm: ShortArray, frames: Int): ByteArray {
        if (frames <= 0) return EMPTY
        ensureCapacity(frames)
        if (channels == 2) {
            var j = 0
            for (i in 0 until frames) {
                left[i] = pcm[j].toInt() shl 16
                right[i] = pcm[j + 1].toInt() shl 16
                j += 2
            }
        } else {
            for (i in 0 until frames) {
                val s = pcm[i].toInt() shl 16
                left[i] = s
                right[i] = s
            }
        }
        val n = lame.lame_encode_buffer_int(gfp, left, right, frames, out, 0, out.size)
        check(n >= 0) { "Erro no encoder MP3 ($n)" }
        return if (n == 0) EMPTY else out.copyOf(n)
    }

    fun flush(): ByteArray {
        val buf = ByteArray(7200)
        val n = lame.lame_encode_flush(gfp, buf, 0, buf.size)
        return if (n <= 0) EMPTY else buf.copyOf(n)
    }

    private fun ensureCapacity(frames: Int) {
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
        }
        val need = (1.25 * frames + 7200).toInt()
        if (out.size < need) out = ByteArray(need)
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}
