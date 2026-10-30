package org.funkgateway.mobile.frn

import android.text.Html

data class FrnChatContact(
    val id: String,
    val name: String
)

object FrnChatContacts {
    private fun field(raw: String, tag: String): String =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1).orEmpty()

    fun fromParticipants(
        entries: List<String>,
        ownName: String
    ): List<FrnChatContact> {
        fun decode(value: String): String =
            Html.fromHtml(
                value, Html.FROM_HTML_MODE_LEGACY
            ).toString().trim()

        fun normalize(value: String): String =
            value.replace(Regex("[\\s\\u00A0]+"), " ").trim()

        val own = normalize(ownName)
        val ownCallsign = own.substringBefore(",").trim()

        fun isOwnParticipant(name: String): Boolean {
            if (own.isBlank()) return false
            val normalized = normalize(name)
            val participantCallsign = normalized.substringBefore(",").trim()
            return normalized.equals(own, ignoreCase = true) ||
                (
                    ownCallsign.isNotBlank() &&
                    participantCallsign.equals(ownCallsign, ignoreCase = true)
                )
        }

        return entries.mapNotNull { raw ->
            val id = decode(field(raw, "ID"))
            val name = decode(field(raw, "ON"))
            if (id.isBlank() || id.length > 128 ||
                id.any { it == '<' || it == '>' || it == '\r' || it == '\n' } ||
                name.isBlank() || isOwnParticipant(name)
            ) {
                null
            } else {
                FrnChatContact(id, name)
            }
        }.distinctBy { it.id.lowercase() }
            .sortedBy { it.name.lowercase() }
    }
}
