package org.funkgateway.mobile.frn

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

object FrnGroupInvitationParser {
    private const val PREFIX = "FGW1|INVITE|"

    fun encode(invitation: FrnGroupInvitation): String {
        require(validUuid(invitation.invitationId) != null)
        require(validUuid(invitation.groupId) != null)
        require(validUuid(invitation.hostId) != null)
        require(Regex("G(?:10|[1-9])").matches(invitation.shortCode))
        require(invitation.groupName.isNotBlank())
        require(invitation.groupName.length <= 100)
        require(invitation.server.isNotBlank() && invitation.server.length <= 255)
        require(invitation.room.isNotBlank() && invitation.room.length <= 255)
        require('|' !in invitation.server && '|' !in invitation.room)
        require('\n' !in invitation.server && '\n' !in invitation.room)

        val encodedName = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                invitation.groupName.toByteArray(StandardCharsets.UTF_8)
            )

        return listOf(
            "FGW1",
            "INVITE",
            invitation.invitationId,
            invitation.groupId,
            invitation.shortCode,
            encodedName,
            invitation.server,
            invitation.room,
            invitation.hostId
        ).joinToString("|")
    }

    fun parse(message: FrnChatMessage): FrnGroupInvitation? {
        if (!message.privateMessage) return null
        if (!message.message.startsWith(PREFIX)) return null

        val parts = message.message.split('|')
        if (parts.size != 9) return null
        if (parts[0] != "FGW1" || parts[1] != "INVITE") return null

        val invitationId = validUuid(parts[2]) ?: return null
        val groupId = validUuid(parts[3]) ?: return null
        val shortCode = parts[4]
        if (!Regex("G(?:10|[1-9])").matches(shortCode)) return null

        val groupName = try {
            val bytes = Base64.getUrlDecoder().decode(parts[5])
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (groupName.isBlank() || groupName.length > 100) return null

        val server = parts[6]
        val room = parts[7]
        val hostId = validUuid(parts[8]) ?: return null

        if (server.isBlank() || server.length > 255 ||
            room.isBlank() || room.length > 255
        ) return null

        return FrnGroupInvitation(
            invitationId = invitationId,
            groupId = groupId,
            groupName = groupName,
            shortCode = shortCode,
            server = server,
            room = room,
            senderId = message.senderId,
            senderName = message.sender,
            hostId = hostId
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
