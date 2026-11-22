package com.tvhuntter.radiodj.core

/** Formato interno de mixagem: 44.1 kHz, estéreo, 16 bits. */
object AudioFormat {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    /** Tamanho do bloco de mixagem (1 quadro MP3 = 1152 amostras). */
    const val BLOCK_FRAMES = 1152
}
