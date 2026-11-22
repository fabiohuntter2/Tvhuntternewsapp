package com.tvhuntter.radiodj.core

/**
 * Converte PCM de qualquer taxa/canais para 44.1 kHz estéreo usando interpolação
 * linear (contínua entre blocos).
 */
class Resampler(private val inRate: Int, private val inChannels: Int) {
    private val outRate = AudioFormat.SAMPLE_RATE
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0          // posição fracionária relativa ao quadro anterior guardado
    private var prevL = 0f
    private var prevR = 0f
    private var hasPrev = false

    val isPassthrough: Boolean get() = inRate == outRate && inChannels == 2

    /** Converte [frames] quadros de [input]; devolve PCM estéreo intercalado. */
    fun process(input: ShortArray, frames: Int): ShortArray {
        if (frames <= 0) return ShortArray(0)
        if (isPassthrough) return input.copyOf(frames * 2)

        val ch = inChannels
        fun l(f: Int): Float = input[f * ch].toFloat()
        fun r(f: Int): Float = if (ch >= 2) input[f * ch + 1].toFloat() else input[f * ch].toFloat()

        if (inRate == outRate) {
            val out = ShortArray(frames * 2)
            for (f in 0 until frames) {
                out[f * 2] = l(f).toInt().toShort()
                out[f * 2 + 1] = r(f).toInt().toShort()
            }
            return out
        }

        // Sequência virtual: índice -1 = quadro anterior guardado, 0..frames-1 = entrada.
        if (!hasPrev) {
            prevL = l(0); prevR = r(0); hasPrev = true
            pos = 1.0 // começa no primeiro quadro real
        }
        val maxOut = ((frames + 1) / step).toInt() + 4
        val out = ShortArray(maxOut * 2)
        var n = 0
        // pos é medido em quadros a partir do quadro anterior (índice -1)
        while (true) {
            val idx = pos.toInt()          // 0 => prev, k => input[k-1]
            if (idx >= frames) break       // precisa de input[idx] (próximo) que ainda não chegou
            val frac = (pos - idx).toFloat()
            val aL: Float; val aR: Float
            if (idx == 0) { aL = prevL; aR = prevR } else { aL = l(idx - 1); aR = r(idx - 1) }
            val bL = l(idx); val bR = r(idx)
            out[n * 2] = (aL + (bL - aL) * frac).toInt().toShort()
            out[n * 2 + 1] = (aR + (bR - aR) * frac).toInt().toShort()
            n++
            pos += step
        }
        prevL = l(frames - 1); prevR = r(frames - 1)
        pos -= frames
        return out.copyOf(n * 2)
    }
}
