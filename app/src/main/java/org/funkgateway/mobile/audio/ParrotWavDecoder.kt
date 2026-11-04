package org.funkgateway.mobile.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV-PCM für die digitale Mumble-Ausgabe dekodieren.
 * Ausgabe: Mono, 48 kHz, 16 Bit.
 */
object ParrotWavDecoder {
    fun decode(file: File): ShortArray? {
        val bytes = file.readBytes()
        if (bytes.size < 44 ||
            String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null

        val buffer = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var format = 0
        var dataOffset = -1
        var dataSize = 0
        var offset = 12

        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            if (size < 0 || size > bytes.size - offset - 8) return null

            when (String(bytes, offset, 4, Charsets.US_ASCII)) {
                "fmt " -> {
                    if (size < 16) return null
                    format = buffer.getShort(offset + 8).toInt() and 0xffff
                    channels = buffer.getShort(offset + 10).toInt() and 0xffff
                    sampleRate = buffer.getInt(offset + 12)
                    bits = buffer.getShort(offset + 22).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = offset + 8
                    dataSize = size
                }
            }
            offset += 8 + size + (size and 1)
        }

        if (format != 1 || channels !in 1..2 ||
            sampleRate !in 8000..192000 ||
            bits != 16 || dataOffset < 0
        ) return null

        val frames = dataSize / (channels * 2)
        if (frames <= 0 || frames > sampleRate * 60) return null

        val mono = ShortArray(frames) { i ->
            val position = dataOffset + i * channels * 2
            val first = buffer.getShort(position).toInt()
            if (channels == 1) first.toShort()
            else {
                val second = buffer.getShort(position + 2).toInt()
                ((first + second) / 2).toShort()
            }
        }

        if (sampleRate == 48000) return mono

        val outputSize = (frames.toLong() * 48000L / sampleRate)
            .toInt()
        return ShortArray(outputSize) { i ->
            val source = i.toDouble() * sampleRate / 48000.0
            val left = source.toInt().coerceIn(0, frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = source - left
            (mono[left] * (1.0 - fraction) +
                mono[right] * fraction).toInt().toShort()
        }
    }
}
