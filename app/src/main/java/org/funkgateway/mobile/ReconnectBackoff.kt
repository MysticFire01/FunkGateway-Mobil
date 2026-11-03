package org.funkgateway.mobile

/**
 * Berechnet Wartezeiten für automatische Wiederverbindungen.
 * Jeder Dienst (FRN/Mumble) verwendet eine eigene Instanz.
 */
class ReconnectBackoff {

    private var attempts = 0

    @Synchronized
    fun nextDelayMillis(settings: ReconnectSettings): Long {
        val initial = settings.initialDelaySeconds.coerceIn(3, 10).toLong()
        val maximum = settings.maxDelaySeconds.coerceIn(30, 120).toLong()

        var delay = initial

        repeat(attempts.coerceAtMost(16)) {
            delay = (delay * 2).coerceAtMost(maximum)
        }

        if (attempts < 16) attempts++

        return delay.coerceAtMost(maximum) * 1000L
    }

    @Synchronized
    fun reset() {
        attempts = 0
    }
}
