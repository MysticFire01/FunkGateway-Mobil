package org.funkgateway.mobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class FrnAudioSender(
    private val context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit
) : Closeable {
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (!started.compareAndSet(false, true)) return

        check(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            "Mikrofonberechtigung fehlt."
        }

        running.set(true)

        worker = Thread({
            var input: AudioRecord? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    GsmWav49Codec.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                check(minBuffer > 0) {
                    "Mikrofonformat wird nicht unterstützt."
                }

                input = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    GsmWav49Codec.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, GsmWav49Codec.PCM_BYTES * 2)
                )

                check(input.state == AudioRecord.STATE_INITIALIZED) {
                    "Mikrofon konnte nicht initialisiert werden."
                }

                recorder = input

                GsmWav49Codec().use { codec ->
                    // PTT könnte während der Mikrofonvorbereitung
                    // bereits wieder losgelassen worden sein.
                    if (!running.get()) return@use

                    input.startRecording()

                    // Auch einen Abbruch während startRecording()
                    // sofort berücksichtigen.
                    if (!running.get()) return@use

                    val pcm = ByteArray(GsmWav49Codec.PCM_BYTES)

                    while (running.get()) {
                        var filled = 0

                        while (running.get() && filled < pcm.size) {
                            val count = input.read(
                                pcm,
                                filled,
                                pcm.size - filled,
                                AudioRecord.READ_BLOCKING
                            )

                            check(count > 0) {
                                "Fehler bei der Mikrofonaufnahme: $count"
                            }

                            filled += count
                        }

                        if (!running.get()) break

                        val packet = codec.encode(pcm)

                        if (running.get()) {
                            onPacket(packet)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    try {
                        onError(e)
                    } catch (_: Exception) {
                        // Fehler im Rückruf dürfen das Aufräumen nicht verhindern.
                    }
                }
            } catch (e: LinkageError) {
                if (running.get()) {
                    try {
                        onError(e)
                    } catch (_: Exception) {
                        // Fehler im Rückruf dürfen das Aufräumen nicht verhindern.
                    }
                }
            } finally {
                running.set(false)
                try {
                    input?.stop()
                } catch (_: Exception) {
                }
                input?.release()
                recorder = null
                worker = null
            }
        }, "FRN-Audio-Sender")

        worker?.start()
    }

    @Synchronized
    override fun close() {
        running.set(false)
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        worker?.interrupt()
    }
}
