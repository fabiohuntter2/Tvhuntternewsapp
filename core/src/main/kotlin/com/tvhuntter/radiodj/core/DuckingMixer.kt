package com.tvhuntter.radiodj.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mixer de música + microfone + ouvinte (ligação) com "ducking" automático (talkover),
 * no estilo dos softwares de automação de rádio: ao abrir o microfone ou colocar o
 * ouvinte no ar, a música abaixa suavemente até [duckLevel]; ao fechar, ela volta.
 *
 * Buffers de música/mic/ouvinte/saída: PCM 16 bits estéreo intercalado.
 */
class DuckingMixer(private val sampleRate: Int = AudioFormat.SAMPLE_RATE) {

    /** Volume da música (0..1). */
    @Volatile var musicVolume: Float = 0.8f
    /** Volume do microfone (0..2, permite ganho extra). */
    @Volatile var micVolume: Float = 1.0f
    /** Volume do ouvinte (0..2). */
    @Volatile var callerVolume: Float = 1.0f
    /** Nível da música enquanto alguém fala (0..1). */
    @Volatile var duckLevel: Float = 0.2f
    /** Tempo da descida da música (ms). */
    @Volatile var duckAttackMs: Int = 400
    /** Tempo da volta da música (ms). */
    @Volatile var duckReleaseMs: Int = 900
    /** Ducking automático ligado. */
    @Volatile var autoDuck: Boolean = true
    /** Microfone no ar. */
    @Volatile var micOn: Boolean = false
    /** Ouvinte no ar. */
    @Volatile var callerOnAir: Boolean = false

    /** Ganho atual de ducking aplicado na música (1 = normal). */
    var currentDuckGain: Float = 1f
        private set
    private var currentMicGain = 0f
    private var currentCallerGain = 0f

    /** Picos (0..1) do último bloco, para os VUs. */
    @Volatile var musicPeak = 0f; private set
    @Volatile var micPeak = 0f; private set
    @Volatile var callerPeak = 0f; private set
    @Volatile var outPeakL = 0f; private set
    @Volatile var outPeakR = 0f; private set

    /**
     * Mistura [frames] quadros. Entradas nulas = silêncio.
     *
     * - [out]: o que vai para o ar.
     * - [monitorOut] (estéreo): retorno no fone do locutor = música + ouvinte (sem a
     *   voz do locutor, para não dar microfonia).
     * - [callerFeed] (mono): o que o ouvinte escuta. Com ele no ar: programa sem a voz
     *   dele (mix-minus). Fora do ar: só a voz do locutor (conversa reservada).
     */
    fun mix(
        music: ShortArray?,
        mic: ShortArray?,
        out: ShortArray,
        frames: Int,
        monitorOut: ShortArray? = null,
        caller: ShortArray? = null,
        callerFeed: ShortArray? = null,
    ) {
        val mv = musicVolume.coerceIn(0f, 1f)
        val micV = micVolume.coerceIn(0f, 2f)
        val callV = callerVolume.coerceIn(0f, 2f)
        val micTarget = if (micOn) 1f else 0f
        val callerTarget = if (callerOnAir) 1f else 0f
        val talking = micOn || callerOnAir
        val duckTarget = if (talking && autoDuck) duckLevel.coerceIn(0f, 1f) else 1f

        val attackStep = 1f / max(1f, duckAttackMs * sampleRate / 1000f)
        val releaseStep = 1f / max(1f, duckReleaseMs * sampleRate / 1000f)
        val gateStep = 1f / (0.06f * sampleRate) // 60 ms para abrir/fechar sem estalo

        var duck = currentDuckGain
        var micG = currentMicGain
        var callG = currentCallerGain
        var mPeak = 0f
        var micP = 0f
        var cPeak = 0f
        var pL = 0f
        var pR = 0f

        var i = 0
        for (f in 0 until frames) {
            if (duck > duckTarget) duck = max(duckTarget, duck - attackStep)
            else if (duck < duckTarget) duck = min(duckTarget, duck + releaseStep)
            if (micG < micTarget) micG = min(micTarget, micG + gateStep)
            else if (micG > micTarget) micG = max(micTarget, micG - gateStep)
            if (callG < callerTarget) callG = min(callerTarget, callG + gateStep)
            else if (callG > callerTarget) callG = max(callerTarget, callG - gateStep)

            val gMusic = mv * duck
            val ml = if (music != null) music[i] / 32768f * gMusic else 0f
            val mr = if (music != null) music[i + 1] / 32768f * gMusic else 0f
            // voz do locutor crua (com volume) e a parte que vai ao ar
            val rawL = if (mic != null) mic[i] / 32768f * micV else 0f
            val rawR = if (mic != null) mic[i + 1] / 32768f * micV else 0f
            val vl = rawL * micG
            val vr = rawR * micG
            // ouvinte cru (com volume) e a parte que vai ao ar
            val cRawL = if (caller != null) caller[i] / 32768f * callV else 0f
            val cRawR = if (caller != null) caller[i + 1] / 32768f * callV else 0f
            val cl = cRawL * callG
            val cr = cRawR * callG

            mPeak = max(mPeak, max(abs(ml), abs(mr)))
            micP = max(micP, max(abs(rawL), abs(rawR)))
            cPeak = max(cPeak, max(abs(cRawL), abs(cRawR)))

            val l = softClip(ml + vl + cl)
            val r = softClip(mr + vr + cr)
            pL = max(pL, abs(l))
            pR = max(pR, abs(r))
            out[i] = (l * 32767f).toInt().toShort()
            out[i + 1] = (r * 32767f).toInt().toShort()

            if (monitorOut != null) {
                monitorOut[i] = (softClip(ml + cRawL) * 32767f).toInt().toShort()
                monitorOut[i + 1] = (softClip(mr + cRawR) * 32767f).toInt().toShort()
            }
            if (callerFeed != null) {
                val feed = if (callerOnAir) {
                    (ml + mr) * 0.5f + (vl + vr) * 0.5f
                } else {
                    (rawL + rawR) * 0.5f // conversa reservada: só a voz do locutor
                }
                callerFeed[f] = (softClip(feed) * 32767f).toInt().toShort()
            }
            i += 2
        }
        currentDuckGain = duck
        currentMicGain = micG
        currentCallerGain = callG
        musicPeak = mPeak
        micPeak = micP
        callerPeak = cPeak
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
