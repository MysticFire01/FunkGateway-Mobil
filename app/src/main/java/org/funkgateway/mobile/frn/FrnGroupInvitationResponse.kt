package org.funkgateway.mobile.frn

import java.util.UUID

enum class FrnGroupResponseType {
    ACCEPT,
    DECLINE,
    CONFIRM,
    ACK
}

data class FrnGroupInvitationResponse(
    val type: FrnGroupResponseType,
    val invitationId: String,
    val groupId: String,
    val senderId: String
)

object FrnGroupInvitationResponseParser {
    fun encode(
        type: FrnGroupResponseType,
        invitationId: String,
        groupId: String
    ): String {
        require(validUuid(invitationId) != null)
        require(validUuid(groupId) != null)
        return "FGW1|${type.name}|$invitationId|$groupId"
    }

    fun parse(message: FrnChatMessage): FrnGroupInvitationResponse? {
        if (!message.privateMessage) return null

        val parts = message.message.split('|')
        if (parts.size != 4 || parts[0] != "FGW1") return null

        val type = try {
            FrnGroupResponseType.valueOf(parts[1])
        } catch (_: IllegalArgumentException) {
            return null
        }

        val invitationId = validUuid(parts[2]) ?: return null
        val groupId = validUuid(parts[3]) ?: return null

        if (message.senderId.isBlank()) return null

        return FrnGroupInvitationResponse(
            type = type,
            invitationId = invitationId,
            groupId = groupId,
            senderId = message.senderId
        )
    }

    private fun validUuid(value: String): String? =
        try {
            UUID.fromString(value)
                .toString()
                .takeIf { it.equals(value, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
}
