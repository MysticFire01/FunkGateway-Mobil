package org.funkgateway.mobile.frn

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

object FrnRegistration {

    data class Request(
        val email: String,
        val username: String,
        val country: String,
        val city: String,
        val description: String
    )

    sealed class Result {
        object Accepted : Result()
        object AlreadyRegistered : Result()
        data class Error(val message: String) : Result()
    }

    private fun xml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun buildRequest(request: Request): String {
        val fields = listOf(
            "ON" to request.username,
            "EA" to request.email,
            "BC" to "PC Only",
            "DS" to request.description,
            "NN" to request.country,
            "CT" to request.city
        )

        return "IG:" + fields.joinToString("") { (tag, value) ->
            "<$tag>${xml(value)}</$tag>"
        } + "\r\n"
    }

    /**
     * Nur von einem Hintergrundthread aufrufen.
     * Noch nicht mit dem Registrierungsbutton verbunden.
     */
    fun register(
        manager: FrnManager,
        request: Request
    ): Result {
        if (manager.id != "de_orig" ||
            manager.host != "sysman.freeradionetwork.eu") {
            return Result.Error("Registrierung derzeit nur bei DE Original freigegeben.")
        }

        if (request.email.isBlank() || request.username.isBlank()) {
            return Result.Error("E-Mail und Benutzername fehlen.")
        }

        if (listOf(
                request.email,
                request.username,
                request.country,
                request.city,
                request.description
            ).any { '\r' in it || '\n' in it }) {
            return Result.Error("Zeilenumbrüche sind nicht erlaubt.")
        }

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(manager.host, 10025), 5000)
                socket.soTimeout = 8000

                val output = socket.getOutputStream()
                output.write(
                    buildRequest(request).toByteArray(
                        Charset.forName(manager.charset)
                    )
                )
                output.flush()

                val input = socket.getInputStream()
                val response = StringBuilder()

                while (response.length < 4096) {
                    val byte = input.read()
                    if (byte == -1 || byte == 10 || byte == 13) break
                    response.append(byte.toChar())
                }

                val answer = response.toString().trim()

                when (answer) {
                    "OK" -> Result.Accepted
                    "NU" -> Result.AlreadyRegistered
                    "" -> Result.Error("Keine Antwort vom SystemManager.")
                    else -> Result.Error(
                        "SystemManager hat die Registrierung nicht bestätigt."
                    )
                }
            }
        } catch (e: Exception) {
            Result.Error("Verbindung fehlgeschlagen: ${e.javaClass.simpleName}")
        }
    }
}
