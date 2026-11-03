package org.funkgateway.mobile.audio

import org.funkgateway.mobile.MumbleConnectionManager
import android.os.SystemClock

class MumbleParrotController(
    private val manager: MumbleConnectionManager
) {
    @Volatile private var generation = 0L

    @Synchronized
    fun cancel() {
        generation++
    }

    @Synchronized
    private fun isCurrent(token: Long): Boolean = token == generation

    fun playRecording(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return false

        val token = synchronized(this) { ++generation }
        val frameSize = 480
        var enabled = false

        try {
            if (!isCurrent(token)) return false
            if (!manager.setBridgeAudioEnabled(true)) return false
            enabled = true

            val started = SystemClock.elapsedRealtimeNanos()
            var offset = 0
            var frameNumber = 0L

            while (offset < pcm.size) {
                if (!isCurrent(token)) return false

                val frame = ShortArray(frameSize)
                val count = minOf(frameSize, pcm.size - offset)
                System.arraycopy(pcm, offset, frame, 0, count)

                if (!manager.submitBridgeAudio(frame, frameSize)) return false

                offset += count
                frameNumber++

                val deadline = started + frameNumber * 10_000_000L
                while (isCurrent(token)) {
                    val remaining = deadline - SystemClock.elapsedRealtimeNanos()
                    if (remaining <= 0L) break
                    SystemClock.sleep(maxOf(1L, remaining / 1_000_000L))
                }
            }

            return isCurrent(token)
        } finally {
            if (enabled) {
                manager.stopBridgeAudio()
                manager.setBridgeAudioEnabled(false)
            }
        }
    }
}
