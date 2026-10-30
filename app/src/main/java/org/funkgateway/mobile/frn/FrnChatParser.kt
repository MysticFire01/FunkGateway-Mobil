package org.funkgateway.mobile.frn

import android.text.Html
import java.util.UUID

data class FrnChatMessage(
    val sender: String,
    val senderId: String,
    val message: String,
    val privateMessage: Boolean
)

object FrnChatParser {
    private val knownNames = mutableMapOf<String, String>()
    private var directoryContext: android.content.Context? = null

    fun initialize(context: android.content.Context) {
        synchronized(knownNames) {
            if (directoryContext != null) return
            val application = context.applicationContext
            knownNames.putAll(FrnParticipantDirectory.load(application))
            directoryContext = application
        }
    }

    private fun decode(value: String): String =
        Html.fromHtml(
            value, Html.FROM_HTML_MODE_LEGACY
        ).toString().trim()

    private fun field(raw: String, tag: String): String =
        Regex(
            "<$tag>(.*?)</$tag>",
            RegexOption.DOT_MATCHES_ALL
        ).find(raw)?.groupValues?.get(1).orEmpty()

    fun parse(
        entries: List<String>,
        clients: List<String>
    ): List<FrnChatMessage> {
        synchronized(knownNames) {
            var changed = false
            clients.forEach { raw ->
                val id = try {
                    UUID.fromString(field(raw, "ID").trim())
                        .toString().lowercase()
                } catch (_: IllegalArgumentException) {
                    null
                }
                val name = decode(field(raw, "ON"))
                if (id != null && name.isNotBlank() &&
                    knownNames[id] != name
                ) {
                    knownNames[id] = name
                    changed = true
                }
            }
            if (changed) {
                directoryContext?.let { context ->
                    FrnParticipantDirectory.save(context, knownNames)
                }
            }
        }

        if (entries.size == 3 && entries[2].trim() == "P") {
            val senderId = try {
                UUID.fromString(entries[0].trim()).toString()
            } catch (_: IllegalArgumentException) {
                null
            }
            val message = decode(entries[1])
            if (senderId != null && message.isNotBlank() &&
                '<' !in message
            ) {
                val sender = synchronized(knownNames) {
                    knownNames[senderId.lowercase()]
                } ?: "FRN"

                return listOf(
                    FrnChatMessage(sender, senderId, message, true)
                )
            }
        }

        return entries.take(100).mapNotNull { raw ->
            if (raw.length > 4096) return@mapNotNull null

            val sender = decode(field(raw, "ON"))
                .ifBlank { "FRN" }
            val text = field(raw, "MS").let {
                if (it.isNotEmpty()) decode(it)
                else if ('<' !in raw) decode(raw)
                else ""
            }
            if (text.isBlank()) null
            else FrnChatMessage(
                sender = sender,
                senderId = field(raw, "ID"),
                message = text.take(2000),
                privateMessage = false
            )
        }
    }
}
