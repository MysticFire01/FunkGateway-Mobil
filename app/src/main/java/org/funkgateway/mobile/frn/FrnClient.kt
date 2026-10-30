package org.funkgateway.mobile.frn

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

object FrnClient {

    private fun xml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    fun requestDynamicPassword(
        manager: FrnManager,
        email: String,
        mainPassword: String
    ): String {
        require(email.isNotBlank() && mainPassword.isNotBlank()) {
            "E-Mail und FRN-Passwort fehlen."
        }
        require(listOf(email, mainPassword).none {
            '\r' in it || '\n' in it
        }) {
            "Ungültige Zeichen in den Zugangsdaten."
        }

        val charset = Charset.forName(manager.charset)

        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(manager.host, 10025),
                8000
            )
            socket.soTimeout = 8000

            val request =
                "DP:<EA>${xml(email)}</EA>" +
                "<PW>${xml(mainPassword)}</PW>\r\n"

            socket.getOutputStream().apply {
                write(request.toByteArray(charset))
                flush()
            }

            val response = StringBuilder()
            val input = socket.getInputStream()

            while (response.length < 65536) {
                val byte = input.read()
                if (byte == -1 || byte == 10 || byte == 13) break
                response.append(byte.toChar())
            }

            val password = response.toString().trim()

            if (password.isEmpty() || password == "-") {
                error("SystemManager lieferte kein dynamisches Passwort.")
            }

            return password
        }
    }
}

data class FrnLoginConfig(
    val room: FrnRoom,
    val email: String,
    val password: String,
    val callsign: String,
    val description: String = "FunkGateway Mobile",
    val country: String = "Germany",
    val city: String = "",
    val vx: String = "20261016",
    val clientType: String = "2",
    val bandChannel: String = "PC Only"
)

class FrnConnection private constructor(
    private val socket: Socket,
    private val input: java.io.InputStream,
    private val output: java.io.OutputStream,
    private val pending: java.util.ArrayDeque<Int>
) : java.io.Closeable {

    @Synchronized
    fun send(command: String) {
        check(!socket.isClosed) { "FRN-Verbindung ist geschlossen." }
        require('\r' !in command && '\n' !in command)
        output.write((command + "\r\n").toByteArray(
            Charset.forName("windows-1252")
        ))
        output.flush()
    }

    @Synchronized
    fun sendAudioPacket(packet: ByteArray) {
        check(!socket.isClosed) {
            "FRN-Verbindung ist geschlossen."
        }
        require(packet.size == 325) {
            "FRN-Sendepaket muss genau 325 Byte enthalten."
        }
        output.write("TX1\r\n".toByteArray(Charsets.US_ASCII))
        output.write(packet)
        output.flush()
    }

    fun receive(): ByteArray {
        if (pending.isNotEmpty()) {
            return ByteArray(pending.size) {
                pending.removeFirst().toByte()
            }
        }
        val bytes = ByteArray(65536)
        return try {
            val count = input.read(bytes)
            if (count < 0) {
                throw java.io.EOFException("FRN-Server hat die Verbindung beendet.")
            }
            bytes.copyOf(count)
        } catch (e: java.net.SocketTimeoutException) {
            ByteArray(0)
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        private fun escape(value: String): String =
            value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")

        private fun line(
            input: java.io.InputStream,
            pending: java.util.ArrayDeque<Int>
        ): String {
            val bytes = java.io.ByteArrayOutputStream()

            while (bytes.size() < 65536) {
                val b = if (pending.isEmpty()) input.read() else pending.removeFirst()

                if (b == -1) throw java.io.EOFException("FRN-Server hat getrennt.")

                if (b == 10 || b == 13) {
                    if (bytes.size() == 0) continue
                    if (b == 13) {
                        val next = if (pending.isEmpty()) input.read()
                                   else pending.removeFirst()
                        if (next != 10 && next != -1) {
                            pending.addFirst(next)
                        }
                    }
                    return bytes.toString("windows-1252")
                }

                bytes.write(b)
            }

            error("FRN-Antwort überschreitet Schutzgrenze.")
        }

        fun connect(config: FrnLoginConfig): FrnConnection {
            require(config.room.room.isNotBlank()) { "Bitte FRN-Raum auswählen." }
            require(config.callsign.isNotBlank()) { "Rufzeichen oder Name fehlt." }
            require(config.room.port in 1..65535) { "Ungültiger Serverport." }

            val manager = FrnSystemManager.managers.firstOrNull {
                it.id == config.room.manager
            } ?: error("Unbekannter FRN-SystemManager.")

            val dynamicPassword = FrnClient.requestDynamicPassword(
                manager, config.email, config.password
            )

            val socket = Socket()

            try {
                socket.connect(
                    InetSocketAddress(config.room.server, config.room.port),
                    10000
                )
                socket.soTimeout = 12000

                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val pending = java.util.ArrayDeque<Int>()

                val city = config.city.trim().ifEmpty { "- -" }

                val login = "CT:" +
                    "<VX>${escape(config.vx)}</VX>" +
                    "<EA>${escape(config.email)}</EA>" +
                    "<PW>${escape(dynamicPassword)}</PW>" +
                    "<ON>${escape(config.callsign)}</ON>" +
                    "<CL>${escape(config.clientType)}</CL>" +
                    "<BC>${escape(config.bandChannel)}</BC>" +
                    "<DS>${escape(config.description)}</DS>" +
                    "<NN>${escape(config.country)}</NN>" +
                    "<CT>${escape(city)}</CT>" +
                    "<NT>${escape(config.room.room)}</NT>"

                output.write((login + "\r\n").toByteArray(
                    Charset.forName("windows-1252")
                ))
                output.flush()

                val first = line(input, pending)
                    .trimStart { it.code < 32 }
                    .trim()

                if (!(first == "0" || first == "20261016" || first.length == 7)) {
                    error("Unerwartete FRN-Protokollantwort.")
                }

                val second = line(input, pending)

                when {
                    "<AL>WRONG</AL>" in second ->
                        error("FRN-Anmeldung abgelehnt: Zugangsdaten falsch.")
                    "<AL>BLOCK</AL>" in second ->
                        error("FRN-Server blockiert diesen Client.")
                    "<AL>OK</AL>" !in second ->
                        error("FRN-Server hat die Anmeldung nicht bestätigt.")
                }

                val connection = FrnConnection(socket, input, output, pending)
                connection.send("RX0")
                connection.send("ST:0")
                socket.soTimeout = 500
                return connection

            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
    }
}
