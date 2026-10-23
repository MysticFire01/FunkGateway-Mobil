package org.funkgateway.mobile.frn

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

sealed class FrnEvent {
    data object Idle : FrnEvent()
    data class TxGranted(val index: Int) : FrnEvent()
    data class ClientList(val activeIndex: Int, val entries: List<String>) : FrnEvent()
    data class ServerList(val type: Int, val entries: List<String>) : FrnEvent()
    data class Audio(val index: Int, val packet: ByteArray) : FrnEvent()
    data class Unknown(val type: Int) : FrnEvent()
}

class FrnParser {
    private val buffer = ArrayList<Byte>()
    private val charset = Charset.forName("windows-1252")

    private var state = 0
    private var skipNextLf = false
    private var listType = 0
    private var activeIndex = 0
    private var remaining = -1
    private val entries = ArrayList<String>()

    fun feed(data: ByteArray): List<FrnEvent> {
        require(buffer.size.toLong() + data.size <= 1_048_576L) {
            "FRN-Empfangspuffer überschreitet Schutzgrenze."
        }
        var start = 0
        if (skipNextLf && data.isNotEmpty()) {
            if (data[0] == 10.toByte()) start = 1
            skipNextLf = false
        }
        for (i in start until data.size) buffer.add(data[i])
        val events = ArrayList<FrnEvent>()

        while (buffer.isNotEmpty()) {
            when (state) {
                1, 2 -> {
                    if (buffer.size < 2) break
                    val index = ((buffer[0].toInt() and 255) shl 8) or
                        (buffer[1].toInt() and 255)
                    repeat(2) { buffer.removeAt(0) }

                    if (state == 1) {
                        events.add(FrnEvent.TxGranted(index))
                        state = 0
                    } else {
                        activeIndex = index
                        listType = 3
                        remaining = -1
                        entries.clear()
                        state = 3
                    }
                }

                3 -> {
                    val end = buffer.indexOfFirst {
                        it == 10.toByte() || it == 13.toByte()
                    }
                    if (end < 0) {
                        require(buffer.size <= 65536) {
                            "FRN-Textzeile überschreitet Schutzgrenze."
                        }
                        break
                    }

                    val line = ByteArray(end) { buffer[it] }
                        .toString(charset)

                    val consume = if (
                        buffer[end] == 13.toByte() &&
                        end + 1 < buffer.size &&
                        buffer[end + 1] == 10.toByte()
                    ) end + 2 else end + 1

                    skipNextLf = buffer[end] == 13.toByte() &&
                        end + 1 == buffer.size
                    repeat(consume) { buffer.removeAt(0) }

                    if (remaining == -1) {
                        remaining = line.toIntOrNull()
                            ?: error(
                                "Ungültige FRN-Listenlänge (Typ $listType, " +
                                    "Bytes: " +
                                    line.toByteArray(charset).take(12)
                                        .joinToString(" ") {
                                            "%02X".format(it.toInt() and 255)
                                        } + ")"
                            )
                        require(remaining in 0..10000) {
                            "FRN-Listenlänge außerhalb der Schutzgrenze."
                        }
                    } else {
                        entries.add(line)
                        remaining--
                    }

                    if (remaining == 0) {
                        if (listType == 3) {
                            events.add(
                                FrnEvent.ClientList(activeIndex, entries.toList())
                            )
                        } else {
                            events.add(
                                FrnEvent.ServerList(listType, entries.toList())
                            )
                        }
                        entries.clear()
                        remaining = -1
                        state = 0
                    }
                }

                4 -> {
                    if (buffer.size < 327) break
                    val index = ((buffer[0].toInt() and 255) shl 8) or
                        (buffer[1].toInt() and 255)
                    val packet = ByteArray(325) { buffer[it + 2] }
                    repeat(327) { buffer.removeAt(0) }
                    events.add(FrnEvent.Audio(index, packet))
                    state = 0
                }

                else -> {
                    val command = buffer.removeAt(0).toInt() and 255
                    when (command) {
                        0 -> events.add(FrnEvent.Idle)
                        1 -> state = 1
                        2 -> state = 4
                        3 -> state = 2
                        in 4..10 -> {
                            listType = command
                            remaining = -1
                            entries.clear()
                            state = 3
                        }
                        else -> events.add(FrnEvent.Unknown(command))
                    }
                }
            }
        }

        return events
    }
}
