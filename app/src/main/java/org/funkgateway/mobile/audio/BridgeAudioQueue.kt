package org.funkgateway.mobile.audio

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Begrenzter PCM-Puffer für die VoIP-Audio-Bridge.
 * Eine neue Sitzung macht alle bisherigen Pakete ungültig.
 */
class BridgeAudioQueue {

    data class Frame(
        val session: Long,
        val pcm: ShortArray
    )

    private val queue = LinkedBlockingDeque<Frame>(4)
    private var generation = 0L
    private var active = false

    @Synchronized
    fun start(): Long {
        generation++
        queue.clear()
        active = true
        return generation
    }

    @Synchronized
    fun stop() {
        active = false
        generation++
        queue.clear()
    }

    @Synchronized
    fun offer(session: Long, pcm: ShortArray): Boolean {
        if (!active || session != generation || pcm.isEmpty()) return false

        val frame = Frame(session, pcm.copyOf())

        if (!queue.offerLast(frame)) {
            queue.pollFirst()
            return queue.offerLast(frame)
        }
        return true
    }

    fun poll(timeoutMillis: Long = 50): Frame? {
        val frame = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            ?: return null

        return synchronized(this) {
            if (active && frame.session == generation) frame else null
        }
    }

    @Synchronized
    fun isActive(session: Long): Boolean =
        active && session == generation
}
