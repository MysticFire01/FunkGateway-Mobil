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

    @Volatile
    private var pcmListener: ((ShortArray) -> Unit)? = null

    fun setPcmListener(listener: ((ShortArray) -> Unit)?) {
        pcmListener = listener
    }

    private val packetGeneration =
        java.util.concurrent.atomic.AtomicLong(0)

    private val callbackLock = Any()

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
    private data class QueuedPacket(
        val data: ByteArray,
        val generation: Long
    )

    private val packets = LinkedBlockingDeque<QueuedPacket>(4)
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

                val generation = packet.generation
                if (generation != packetGeneration.get()) continue
                if (!running.get()) break
                if (deafened.get()) continue

                try {
                    val pcm = codec.decode(packet.data)
                    if (running.get() && !deafened.get() && generation == packetGeneration.get()) {
                        try {
                            val listener = pcmListener
                            if (listener != null && pcm.size == GsmWav49Codec.PCM_BYTES) {
                                val samples = ShortArray(pcm.size / 2) { i ->
                                    val lo = pcm[i * 2].toInt() and 0xff
                                    val hi = pcm[i * 2 + 1].toInt() and 0xff
                                    ((hi shl 8) or lo).toShort()
                                }
                                synchronized(callbackLock) {
                                    if (running.get() &&
                                        !deafened.get() &&
                                        generation == packetGeneration.get() &&
                                        pcmListener === listener
                                    ) {
                                        listener(samples)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("FunkGateway", "FRN-PCM-Callback fehlgeschlagen", e)
                        }
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

        val copy = QueuedPacket(
            packet.copyOf(),
            packetGeneration.get()
        )

        if (!running.get()) return

        if (!packets.offerLast(copy)) {
            packets.pollFirst()
            packets.offerLast(copy)
        }
    }

    /**
     * Verwirft noch nicht dekodierte FRN-Audiopakete.
     * Der Decoder und die FRN-Verbindung laufen weiter.
     *
     * Ein bereits vom Decoder entnommenes Paket wird dadurch
     * nicht nachträglich gestoppt.
     */
    fun clearPendingPackets() {
        synchronized(callbackLock) {
            packetGeneration.incrementAndGet()
            packets.clear()
        }
    }

    /**
     * Beendet die Verbindung zum Audiopfad.
     * Der Decoder-Thread räumt seine Ressourcen selbst auf.
     */
    override fun close() {
        if (running.compareAndSet(true, false)) {
            pcmListener = null
            packets.clear()
            worker.interrupt()
        }
    }
}
