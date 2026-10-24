package org.funkgateway.mobile.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.Closeable
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemeinsame PCM-Lautsprecherausgabe für FunkGateway Mobile.
 *
 * Aktuell: 8000 Hz, Mono, PCM 16 Bit für FRN.
 * Die Klasse kennt kein FRN-Protokoll und keinen Codec.
 */
class PcmAudioPlayer(
    private val sampleRate: Int = 8000
) : Closeable {

    private val running = AtomicBoolean(false)
    private val packets = LinkedBlockingDeque<ByteArray>(6)
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return

        worker = Thread({
            var track: AudioTrack? = null

            try {
                val channel = AudioFormat.CHANNEL_OUT_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT

                val minimum = AudioTrack.getMinBufferSize(
                    sampleRate, channel, encoding
                )

                check(minimum > 0) {
                    "Android unterstützt dieses PCM-Audioformat nicht."
                }

                val bufferSize = maxOf(minimum, sampleRate * 2 / 2)

                track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channel)
                        .build(),
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "AudioTrack konnte nicht initialisiert werden."
                }

                track.play()

                while (running.get()) {
                    val pcm = try {
                        packets.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        break
                    } ?: continue

                    var offset = 0

                    while (running.get() && offset < pcm.size) {
                        val count = track.write(
                            pcm,
                            offset,
                            pcm.size - offset,
                            AudioTrack.WRITE_BLOCKING
                        )

                        if (count <= 0) {
                            Log.w(
                                "FunkGateway",
                                "PCM-Ausgabe: AudioTrack.write=$count"
                            )
                            break
                        }

                        offset += count
                    }
                }

            } catch (e: Exception) {
                Log.e("FunkGateway", "PCM-Audioausgabe", e)
            } finally {
                packets.clear()
                try {
                    track?.pause()
                    track?.flush()
                    track?.release()
                } catch (e: Exception) {
                    Log.w("FunkGateway", "PCM-Freigabe", e)
                }
                running.set(false)
            }
        }, "FGW-PCM-Player")

        worker?.start()
    }

    fun enqueue(pcm: ByteArray) {
        if (!running.get() || pcm.isEmpty()) return

        val copy = pcm.copyOf()

        if (!packets.offerLast(copy)) {
            packets.pollFirst()
            packets.offerLast(copy)
        }
    }

    fun clear() {
        packets.clear()
    }

    @Synchronized
    override fun close() {
        running.set(false)
        packets.clear()
        worker?.interrupt()
        worker = null
    }
}
