package org.funkgateway.mobile.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kontinuierlicher PCM-Resampler von 48 kHz auf 8 kHz.
 * Mono, 16 Bit, mit FIR-Tiefpass und Filterhistorie.
 *
 * Eine Instanz pro Audiostrom verwenden.
 */
class StreamingPcmResampler {

    private val factor = 6
    private val taps = 49
    private val history = ShortArray(taps - 1)
    private val coefficients = DoubleArray(taps)
    private var phase = 0

    init {
        val cutoff = 0.075
        val center = (taps - 1) / 2

        for (i in coefficients.indices) {
            val x = i - center

            val sinc = if (x == 0) {
                2.0 * cutoff
            } else {
                sin(2.0 * PI * cutoff * x) / (PI * x)
            }

            val window = 0.54 -
                0.46 * cos(2.0 * PI * i / (taps - 1))

            coefficients[i] = sinc * window
        }

        val sum = coefficients.sum()
        for (i in coefficients.indices) {
            coefficients[i] /= sum
        }
    }

    @Synchronized
    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)

        val combined = ShortArray(history.size + input.size)
        history.copyInto(combined)
        input.copyInto(combined, history.size)

        val output = ArrayList<Short>()

        for (i in input.indices) {
            if (phase == 0) {
                val end = history.size + i
                var value = 0.0

                for (j in coefficients.indices) {
                    value += combined[end - j].toDouble() *
                        coefficients[j]
                }

                output.add(
                    value.toInt()
                        .coerceIn(
                            Short.MIN_VALUE.toInt(),
                            Short.MAX_VALUE.toInt()
                        )
                        .toShort()
                )
            }

            phase = (phase + 1) % factor
        }

        combined.copyInto(
            history,
            startIndex = combined.size - history.size
        )

        return output.toShortArray()
    }

    @Synchronized
    fun reset() {
        history.fill(0)
        phase = 0
    }
}
