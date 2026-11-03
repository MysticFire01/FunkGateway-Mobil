package org.funkgateway.mobile.audio

/**
 * Speichert PCM-Audio einer einzelnen Papagei-Aufnahme.
 * Standard: maximal 30 Sekunden bei 48 kHz, Mono, 16 Bit.
 */
class ParrotRecordingBuffer(
    private val sampleRate: Int = 48000,
    private val maxSeconds: Int = 30
) {
    private val maxSamples = sampleRate * maxSeconds
    private val chunks = ArrayList<ShortArray>()
    private var sampleCount = 0
    private var generation = 0L
    private var recording = false

    @Synchronized
    fun start(): Long {
        generation++
        chunks.clear()
        sampleCount = 0
        recording = true
        return generation
    }

    @Synchronized
    fun append(session: Long, pcm: ShortArray): Boolean {
        if (!recording || session != generation || pcm.isEmpty()) return false

        val remaining = maxSamples - sampleCount
        if (remaining <= 0) return false

        val count = minOf(remaining, pcm.size)
        chunks.add(pcm.copyOf(count))
        sampleCount += count
        return count == pcm.size
    }

    @Synchronized
    fun finish(session: Long): ShortArray? {
        if (!recording || session != generation) return null
        recording = false

        val result = ShortArray(sampleCount)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }

        chunks.clear()
        sampleCount = 0
        return result
    }

    @Synchronized
    fun cancel() {
        generation++
        recording = false
        chunks.clear()
        sampleCount = 0
    }

    @Synchronized
    fun isRecording(session: Long): Boolean =
        recording && session == generation
}
