package org.funkgateway.mobile.audio

import android.util.Log
import java.io.Closeable
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FRN-GSM-Empfang.
 *
 * Netzwerkthread -> begrenzte GSM-Warteschlange ->
 * Decoder-Thread -> gemeinsamer PCM-Player.
 */
class FrnAudioReceiver : Closeable {

    private val running = AtomicBoolean(true)
    private val deafened = AtomicBoolean(false)

    fun setDeafened(value: Boolean) {
        deafened.set(value)
        player.setDeafened(value)
        if (value) {
            packets.clear()
            player.clear()
        }
    }
    private val packets = LinkedBlockingDeque<ByteArray>(4)
    private val player = PcmAudioPlayer(GsmWav49Codec.SAMPLE_RATE)

    fun setVolume(value: Float) {
        player.setVolume(value)
    }

    private val worker = Thread({
        var codec: GsmWav49Codec? = null

        try {
            codec = GsmWav49Codec()
            player.start()

            while (running.get()) {
                val packet = try {
                    packets.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue

                if (!running.get()) break
                if (deafened.get()) continue

                try {
                    val pcm = codec.decode(packet)
                    if (running.get() && !deafened.get()) {
                        player.enqueue(pcm)
                    }
                } catch (e: Exception) {
                    Log.w(
                        "FunkGateway",
                        "FRN-GSM-Paket konnte nicht decodiert werden",
                        e
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("FunkGateway", "FRN-Audioinitialisierung", e)
        } catch (e: LinkageError) {
            Log.e("FunkGateway", "Native FRN-Audiobibliothek nicht verfügbar", e)
        } finally {
            packets.clear()
            player.close()
            codec?.close()
        }
    }, "FGW-FRN-Decoder")

    init {
        worker.start()
    }

    /**
     * Nicht blockierende Übergabe vom FRN-Netzwerkthread.
     */
    fun receive(packet: ByteArray) {
        if (!running.get() || deafened.get()) return
        if (packet.size != GsmWav49Codec.PACKET_BYTES) return

        val copy = packet.copyOf()

        if (!running.get()) return

        if (!packets.offerLast(copy)) {
            packets.pollFirst()
            packets.offerLast(copy)
        }
    }

    /**
     * Beendet die Verbindung zum Audiopfad.
     * Der Decoder-Thread räumt seine Ressourcen selbst auf.
     */
    override fun close() {
        if (running.compareAndSet(true, false)) {
            packets.clear()
            worker.interrupt()
        }
    }
}
