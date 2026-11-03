package org.funkgateway.mobile.audio

import java.io.Closeable

/**
 * GSM-WAV49-Encoder für die direkte VoIP-Verlinkung.
 * Verwendet kein Mikrofon.
 *
 * Nur von einem Audio-Worker gleichzeitig aufrufen.
 */
class FrnBridgeEncoder : Closeable {

    private val codec = GsmWav49Codec()
    private val samples = ShortArray(1600)
    private var filled = 0
    private var closed = false

    @Synchronized
    fun encode(input: ShortArray): List<ByteArray> {
        if (closed) return emptyList()

        val packets = ArrayList<ByteArray>()
        var offset = 0

        while (offset < input.size) {
            val count = minOf(
                samples.size - filled,
                input.size - offset
            )

            input.copyInto(
                samples,
                destinationOffset = filled,
                startIndex = offset,
                endIndex = offset + count
            )

            filled += count
            offset += count

            if (filled == samples.size) {
                val pcm = ByteArray(GsmWav49Codec.PCM_BYTES)

                for (i in samples.indices) {
                    val value = samples[i].toInt()
                    pcm[i * 2] = value.toByte()
                    pcm[i * 2 + 1] = (value shr 8).toByte()
                }

                packets.add(codec.encode(pcm))
                filled = 0
            }
        }

        return packets
    }

    @Synchronized
    fun reset() {
        filled = 0
        samples.fill(0)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        reset()
        codec.close()
    }
}
