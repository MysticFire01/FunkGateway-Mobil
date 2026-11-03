package org.funkgateway.mobile.audio

/**
 * PCM-Abtastratenumwandlung für FRN (8 kHz) -> Mumble (48 kHz).
 * Mono, vorzeichenbehaftete 16-Bit-Samples.
 */
object PcmResampler {

    fun frnToMumble(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)

        val factor = 6
        val output = ShortArray(input.size * factor)

        for (i in output.indices) {
            val source = i / factor
            val fraction = (i % factor) / factor.toFloat()

            val a = input[source].toFloat()
            val b = input[minOf(source + 1, input.lastIndex)].toFloat()

            output[i] = (a + (b - a) * fraction)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        return output
    }

    /**
     * Mumble (48 kHz) -> FRN (8 kHz).
     * FIR-Tiefpass vor der Reduzierung um Faktor 6.
     *
     * Eingabe sollte ein Vielfaches von 6 Samples enthalten.
     * Für kontinuierliches Audio später mit zustandsbehaftetem
     * Filter und Übernahme der Blockgrenzen verwenden.
     */
    fun mumbleToFrn(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)

        require(input.size % 6 == 0) {
            "48-kHz-PCM muss durch 6 teilbar sein."
        }

        val taps = 49
        val cutoff = 0.075
        val coefficients = DoubleArray(taps)
        val center = (taps - 1) / 2

        for (i in 0 until taps) {
            val x = i - center
            val sinc = if (x == 0) {
                2.0 * cutoff
            } else {
                kotlin.math.sin(2.0 * Math.PI * cutoff * x) /
                    (Math.PI * x)
            }

            val window = 0.54 -
                0.46 * kotlin.math.cos(
                    2.0 * Math.PI * i / (taps - 1)
                )

            coefficients[i] = sinc * window
        }

        val sum = coefficients.sum()
        for (i in coefficients.indices) {
            coefficients[i] /= sum
        }

        val output = ShortArray(input.size / 6)

        for (i in output.indices) {
            val position = i * 6
            var value = 0.0

            for (j in coefficients.indices) {
                val source = (position + j - center)
                    .coerceIn(0, input.lastIndex)

                value += input[source].toDouble() * coefficients[j]
            }

            output[i] = value.toInt()
                .coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                )
                .toShort()
        }

        return output
    }
}
