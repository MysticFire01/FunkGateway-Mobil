package org.funkgateway.mobile.audio

import org.funkgateway.mobile.MumbleConnectionManager
import android.os.SystemClock

class MumbleParrotController(
    private val manager: MumbleConnectionManager
) {
    @Volatile private var generation = 0L
    @Volatile private var cancellationGeneration = 0L
    @Volatile private var closed = false
    private val playbackLock = java.util.concurrent.locks.ReentrantLock()

    fun requestCancel() {
        synchronized(this) { generation++; cancellationGeneration++ }
    }

    fun requestClose() {
        synchronized(this) {
            closed = true
            generation++
            cancellationGeneration++
        }
    }

    fun isPlaybackRunning(): Boolean = playbackLock.isLocked

    fun waitForPlaybackIdle(): Boolean {
        if (closed) return false
        playbackLock.lock()
        return try {
            !closed
        } finally {
            playbackLock.unlock()
        }
    }

    fun cancel() {
        closed = true
        requestCancel()
        playbackLock.lock()
        try { /* Warten, bis die alte Wiedergabe beendet ist. */ }
        finally { playbackLock.unlock() }
    }

    @Synchronized
    private fun isCurrent(token: Long): Boolean = token == generation

    fun playRecording(pcm: ShortArray): Boolean {
        if (pcm.isEmpty() || closed) return false

        val cancellationToken = synchronized(this) {
            if (closed) return false
            cancellationGeneration
        }
        playbackLock.lock()
        try {
            val token = synchronized(this) {
                if (closed || cancellationToken != cancellationGeneration) return false
                ++generation
            }
            return playLocked(pcm, token)
        } finally {
            playbackLock.unlock()
        }
    }

    fun playTimeoutTone(): Boolean {
        if (closed) return false

        playbackLock.lock()
        try {
            if (closed) return false

            val sampleRate = 48000
            val frequency = 750.0
            val durationSamples = sampleRate / 4
            val pcm = ShortArray(durationSamples) { index ->
                val phase = 2.0 * Math.PI * frequency * index / sampleRate
                (kotlin.math.sin(phase) * 9000.0).toInt().toShort()
            }

            return playRecording(pcm)
        } finally {
            playbackLock.unlock()
        }
    }

    /**
     * Fertige TTS-Ansage über den digitalen Mumble-Audioweg senden.
     * Das Smartphone-Mikrofon bleibt ausgeschaltet.
     */
    fun playSpeechAnnouncement(pcm: ShortArray): Boolean {
        if (pcm.isEmpty() || closed) return false
        return playRecording(pcm)
    }

    private fun playLocked(pcm: ShortArray, token: Long): Boolean {
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
                // Aussendung beenden, digitalen Eingang aber aktiv lassen.
                // Sonst würde Humla das Smartphone-Mikrofon neu starten.
                manager.stopBridgeAudio()
            }
        }
    }
}
