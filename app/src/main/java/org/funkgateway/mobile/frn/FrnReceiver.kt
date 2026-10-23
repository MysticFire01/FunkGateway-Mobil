package org.funkgateway.mobile.frn

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class FrnReceiver(
    private val connection: FrnConnection,
    private val onEvent: (FrnEvent) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onStopped: () -> Unit = {}
) : Closeable {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return

        thread = Thread({
            val parser = FrnParser()
            var nextPing = System.nanoTime() + 2_000_000_000L

            try {
                while (running.get()) {
                    val data = connection.receive()

                    if (data.isNotEmpty()) {
                        val events = parser.feed(data)

                        for (event in events) {
                            if (!running.get()) break
                            onEvent(event)

                            if (event is FrnEvent.Audio) {
                                connection.send("P")
                                nextPing = System.nanoTime() + 2_000_000_000L
                            }
                        }
                    }

                    if (running.get() && System.nanoTime() >= nextPing) {
                        connection.send("P")
                        nextPing = System.nanoTime() + 2_000_000_000L
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    try {
                        onError(e)
                    } catch (_: Exception) {
                        // Fehler im Rückruf dürfen die Aufräumarbeiten nicht verhindern.
                    }
                }
            } finally {
                running.set(false)
                try {
                    connection.close()
                } finally {
                    onStopped()
                }
            }
        }, "FRN-Receiver")

        thread?.start()
    }

    override fun close() {
        running.set(false)
        connection.close()
    }
}
