package org.funkgateway.mobile.frn

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

data class FrnRoom(
    val manager: String,
    val server: String,
    val port: Int,
    val room: String,
    val clients: Int
)

data class FrnManager(
    val id: String,
    val name: String,
    val host: String,
    val charset: String
)

object FrnSystemManager {

    val managers = listOf(
        FrnManager(
            "de_orig", "DE Original",
            "sysman.freeradionetwork.eu", "windows-1252"
        ),
        FrnManager(
            "de_alt", "DE Alternativ",
            "sysman.freeradionetwork.de", "windows-1252"
        ),
        FrnManager(
            "ru", "Russland",
            "sysman.lpd-net.ru", "windows-1251"
        )
    )

    private fun readLine(reader: BufferedReader): String {
        val line = reader.readLine()
            ?: throw Exception("Verbindung unerwartet beendet")
        require(line.length <= 65536) {
            "FRN-Zeile überschreitet Schutzgrenze"
        }
        return line
    }

    private fun readNumber(
        reader: BufferedReader,
        max: Int
    ): Int {
        val number = readLine(reader).trim().toInt()
        require(number in 0..max) {
            "Ungültiger FRN-Zähler: $number"
        }
        return number
    }

    private fun parseServer(value: String): Pair<String, Int> {
        val match = Regex(
            """^\s*(.+?)\s*-\s*Port:\s*(\d{1,5})\s*$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(value)

        if (match == null) {
            return Pair(value.trim(), 10024)
        }

        val port = match.groupValues[2].toInt()
        require(port in 1..65535)

        return Pair(match.groupValues[1].trim(), port)
    }

    fun load(manager: FrnManager): List<FrnRoom> {
        val result = mutableListOf<FrnRoom>()

        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(manager.host, 10025),
                10000
            )
            socket.soTimeout = 12000

            val output = socket.getOutputStream()
            output.write("SM\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()

            val reader = BufferedReader(
                InputStreamReader(
                    socket.getInputStream(),
                    Charset.forName(manager.charset)
                )
            )

            val serverCount = readNumber(reader, 5000)

            repeat(serverCount) {
                val (server, port) =
                    parseServer(readLine(reader))

                val roomCount = readNumber(reader, 128)

                if (roomCount == 0) {
                    result.add(
                        FrnRoom(
                            manager.id, server, port, "", 0
                        )
                    )
                }

                repeat(roomCount) {
                    val room = readLine(reader)
                    val clients = readNumber(reader, 10000)

                    repeat(clients) {
                        readLine(reader)
                    }

                    result.add(
                        FrnRoom(
                            manager.id,
                            server,
                            port,
                            room,
                            clients
                        )
                    )
                }
            }
        }

        return result
    }
}
