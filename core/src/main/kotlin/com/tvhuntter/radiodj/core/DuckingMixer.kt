package com.tvhuntter.radiodj.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mixer de música + microfone com "ducking" automático (talkover), no estilo dos
 * softwares de automação de rádio: ao abrir o microfone a música abaixa suavemente
 * até [duckLevel]; ao fechar, ela volta ao volume normal.
 *
 * Todos os buffers são PCM 16 bits estéreo intercalado.
 */
class DuckingMixer(private val sampleRate: Int = AudioFormat.SAMPLE_RATE) {

    /** Volume da música (0..1). */
    @Volatile var musicVolume: Float = 0.8f
    /** Volume do microfone (0..2, permite ganho extra). */
    @Volatile var micVolume: Float = 1.0f
    /** Nível da música enquanto o microfone está aberto (0..1). */
    @Volatile var duckLevel: Float = 0.2f
    /** Tempo da descida da música ao abrir o microfone (ms). */
    @Volatile var duckAttackMs: Int = 400
    /** Tempo da volta da música ao fechar o microfone (ms). */
    @Volatile var duckReleaseMs: Int = 900
    /** Ducking automático ligado. */
    @Volatile var autoDuck: Boolean = true
    /** Microfone no ar. */
    @Volatile var micOn: Boolean = false

    /** Ganho atual de ducking aplicado na música (1 = normal). */
    var currentDuckGain: Float = 1f
        private set
    private var currentMicGain = 0f

    /** Picos (0..1) do último bloco, para os VUs. */
    @Volatile var musicPeak = 0f; private set
    @Volatile var micPeak = 0f; private set
    @Volatile var outPeakL = 0f; private set
    @Volatile var outPeakR = 0f; private set

    /**
     * Mistura [frames] quadros. [music] e [mic] podem ser nulos (silêncio).
     * O resultado vai para [out]. Se [musicOnlyOut] for informado, recebe só a música
     * (com o ducking aplicado), para o retorno no fone sem microfonia.
     */
    fun mix(music: ShortArray?, mic: ShortArray?, out: ShortArray, frames: Int, musicOnlyOut: ShortArray? = null) {
        val mv = musicVolume.coerceIn(0f, 1f)
        val micV = micVolume.coerceIn(0f, 2f)
        val micTarget = if (micOn) 1f else 0f
        val duckTarget = if (micOn && autoDuck) duckLevel.coerceIn(0f, 1f) else 1f

        val attackStep = 1f / max(1f, duckAttackMs * sampleRate / 1000f)
        val releaseStep = 1f / max(1f, duckReleaseMs * sampleRate / 1000f)
        val micStep = 1f / (0.06f * sampleRate) // 60 ms para abrir/fechar o mic sem estalo

        var duck = currentDuckGain
        var micG = currentMicGain
        var mPeak = 0f
        var micP = 0f
        var pL = 0f
        var pR = 0f

        var i = 0
        for (f in 0 until frames) {
            // rampas suaves
            if (duck > duckTarget) duck = max(duckTarget, duck - attackStep)
            else if (duck < duckTarget) duck = min(duckTarget, duck + releaseStep)
            if (micG < micTarget) micG = min(micTarget, micG + micStep)
            else if (micG > micTarget) micG = max(micTarget, micG - micStep)

            val gMusic = mv * duck
            val ml = if (music != null) music[i] / 32768f * gMusic else 0f
            val mr = if (music != null) music[i + 1] / 32768f * gMusic else 0f
            val vl = if (mic != null && micG > 0f) mic[i] / 32768f * micV * micG else 0f
            val vr = if (mic != null && micG > 0f) mic[i + 1] / 32768f * micV * micG else 0f

            mPeak = max(mPeak, max(abs(ml), abs(mr)))
            micP = max(micP, max(abs(vl), abs(vr)))

            val l = softClip(ml + vl)
            val r = softClip(mr + vr)
            pL = max(pL, abs(l))
            pR = max(pR, abs(r))
            if (musicOnlyOut != null) {
                musicOnlyOut[i] = (softClip(ml) * 32767f).toInt().toShort()
                musicOnlyOut[i + 1] = (softClip(mr) * 32767f).toInt().toShort()
            }
            out[i] = (l * 32767f).toInt().toShort()
            out[i + 1] = (r * 32767f).toInt().toShort()
            i += 2
        }
        currentDuckGain = duck
        currentMicGain = micG
        musicPeak = mPeak
        micPeak = micP
        outPeakL = pL
        outPeakR = pR
    }

    companion object {
        /** Limitador suave: linear até 0.85, comprime acima e nunca passa de 1. */
        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= 0.85f) return x
            val over = a - 0.85f
            val y = 0.85f + 0.15f * (over / (over + 0.15f))
            return if (x < 0) -y else y
        }
    }
}
