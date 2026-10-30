package org.funkgateway.mobile.frn

import java.util.UUID

data class FrnGroupDissolution(
    val groupId: String,
    val version: Long,
    val senderId: String
)

object FrnGroupDissolutionParser {

    fun encode(groupId: String, version: Long): String {
        require(validUuid(groupId) != null)
        require(version > 0)
        return "FGWD1|DISSOLVE|$groupId|$version"
    }

    fun parse(message: FrnChatMessage): FrnGroupDissolution? {
        if (!message.privateMessage || message.senderId.isBlank()) {
            return null
        }

        val parts = message.message.split('|')
        if (parts.size != 4 ||
            parts[0] != "FGWD1" ||
            parts[1] != "DISSOLVE"
        ) return null

        val groupId = validUuid(parts[2]) ?: return null
        val version = parts[3].toLongOrNull()
            ?.takeIf { it > 0 } ?: return null

        return FrnGroupDissolution(
            groupId = groupId,
            version = version,
            senderId = message.senderId
        )
    }

    private fun validUuid(value: String): String? =
        try {
            UUID.fromString(value).toString()
                .takeIf { it.equals(value, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
}
