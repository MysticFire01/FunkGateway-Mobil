package org.funkgateway.mobile.audio

import java.io.Closeable

/**
 * GSM 06.10 WAV49 für FRN.
 *
 * 325 Byte GSM entsprechen 1600 PCM-Samples:
 * 8000 Hz, Mono, 16 Bit, 200 ms.
 *
 * Ein Codec-Objekt darf nicht gleichzeitig von mehreren
 * Threads verwendet werden.
 */
class GsmWav49Codec : Closeable {

    companion object {
        const val PACKET_BYTES = 325
        const val PCM_BYTES = 3200
        const val SAMPLE_RATE = 8000

        init {
            System.loadLibrary("fgw_audio")
        }
    }

    private var handle: Long = nativeCreate()

    init {
        check(handle != 0L) {
            "GSM-WAV49-Codec konnte nicht initialisiert werden."
        }
    }

    @Synchronized
    fun decode(packet: ByteArray): ByteArray {
        check(handle != 0L) { "Codec wurde geschlossen." }
        require(packet.size == PACKET_BYTES) {
            "FRN-Audiopaket muss 325 Byte enthalten."
        }

        return nativeDecode(handle, packet)
            ?: error("GSM-WAV49-Decodierung fehlgeschlagen.")
    }

    @Synchronized
    fun encode(pcm: ByteArray): ByteArray {
        check(handle != 0L) { "Codec wurde geschlossen." }
        require(pcm.size == PCM_BYTES) {
            "PCM-Block muss 3200 Byte enthalten."
        }

        return nativeEncode(handle, pcm)
            ?: error("GSM-WAV49-Codierung fehlgeschlagen.")
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeDecode(
        handle: Long,
        packet: ByteArray
    ): ByteArray?

    private external fun nativeEncode(
        handle: Long,
        pcm: ByteArray
    ): ByteArray?
}
