package com.tvhuntter.radiodj.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/** Buffer circular de amostras PCM 16 bits, seguro entre threads. */
class PcmRingBuffer(capacitySamples: Int) {
    private val buf = ShortArray(capacitySamples)
    private var readPos = 0
    private var writePos = 0
    private var count = 0
    private val lock = ReentrantLock()
    private val notFull = lock.newCondition()

    val capacity: Int get() = buf.size
    fun available(): Int = lock.withLock { count }

    /** Escreve tudo, bloqueando enquanto estiver cheio. Retorna false se interrompido. */
    fun writeBlocking(src: ShortArray, offset: Int, length: Int): Boolean {
        var off = offset
        var remaining = length
        lock.withLock {
            while (remaining > 0) {
                while (count == buf.size) {
                    try {
                        notFull.await()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                val n = min(remaining, buf.size - count)
                for (k in 0 until n) {
                    buf[writePos] = src[off + k]
                    writePos++
                    if (writePos == buf.size) writePos = 0
                }
                count += n
                off += n
                remaining -= n
            }
        }
        return true
    }

    /** Escreve sem bloquear; se faltar espaço descarta as amostras mais antigas. */
    fun writeOverwrite(src: ShortArray, offset: Int, length: Int) {
        lock.withLock {
            for (k in 0 until length) {
                if (count == buf.size) {
                    readPos++
                    if (readPos == buf.size) readPos = 0
                    count--
                }
                buf[writePos] = src[offset + k]
                writePos++
                if (writePos == buf.size) writePos = 0
                count++
            }
        }
    }

    /** Lê até [length] amostras; retorna quantas foram lidas. */
    fun read(dst: ShortArray, offset: Int, length: Int): Int {
        lock.withLock {
            val n = min(length, count)
            for (k in 0 until n) {
                dst[offset + k] = buf[readPos]
                readPos++
                if (readPos == buf.size) readPos = 0
            }
            count -= n
            if (n > 0) notFull.signalAll()
            return n
        }
    }

    /** Descarta amostras antigas mantendo no máximo [maxSamples]. */
    fun trimTo(maxSamples: Int) {
        lock.withLock {
            while (count > maxSamples) {
                readPos++
                if (readPos == buf.size) readPos = 0
                count--
            }
            notFull.signalAll()
        }
    }

    fun clear() {
        lock.withLock {
            readPos = 0; writePos = 0; count = 0
            notFull.signalAll()
        }
    }
}
