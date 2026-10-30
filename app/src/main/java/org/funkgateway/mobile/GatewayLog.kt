package org.funkgateway.mobile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GatewayLog {
    private const val MAX_ENTRIES = 500

    data class Entry(
        val time: Long,
        val source: String,
        val level: String,
        val message: String
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(
        source: String,
        message: String,
        level: String = "INFO"
    ) {
        // Keine Zugangsdaten oder kompletten Login-Pakete protokollieren.
        val clean = message
            .replace(
                Regex("(?i)<PW>.*?</PW>"),
                "<PW>[geschützt]</PW>"
            )
            .replace(
                Regex("(?i)(password|passwort)=\\S+"),
                "$1=[geschützt]"
            )
        entries.addLast(Entry(
            System.currentTimeMillis(),
            source.uppercase(Locale.ROOT),
            level,
            clean.take(1000)
        ))
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun get(source: String? = null): List<Entry> =
        entries.filter {
            source == null || it.source.equals(source, true)
        }

    @Synchronized
    fun clear(source: String? = null) {
        if (source == null) {
            entries.clear()
        } else {
            entries.removeAll { it.source.equals(source, true) }
        }
    }

    fun format(entry: Entry): String {
        val time = SimpleDateFormat(
            "HH:mm:ss",
            Locale.GERMANY
        ).format(Date(entry.time))
        return "$time [${entry.level}] ${entry.message}"
    }
}
