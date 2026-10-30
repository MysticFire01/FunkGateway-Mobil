package org.funkgateway.mobile.frn

import java.util.UUID

data class FrnGroupMemberSync(
    val groupId: String,
    val version: Long,
    val index: Int,
    val total: Int,
    val memberId: String,
    val memberName: String,
    val senderId: String
)

object FrnGroupMemberSyncParser {
    fun encode(
        groupId: String,
        version: Long,
        index: Int,
        total: Int,
        memberId: String,
        memberName: String
    ): String {
        require(validUuid(groupId) != null)
        require(version > 0)
        require(total in 1..100)
        require(index in 1..total)
        require(memberId.isNotBlank())
        require(memberName.isNotBlank())

        val id = encodeField(memberId)
        val name = encodeField(memberName)

        val result = "FGW1|MEMBER|$groupId|$version|$index|$total|$id|$name"
        require(result.length <= 500)
        return result
    }

    fun parse(message: FrnChatMessage): FrnGroupMemberSync? {
        if (!message.privateMessage || message.senderId.isBlank()) return null

        val parts = message.message.split('|')
        if (parts.size != 8 || parts[0] != "FGW1" ||
            parts[1] != "MEMBER") return null

        val groupId = validUuid(parts[2]) ?: return null
        val version = parts[3].toLongOrNull()?.takeIf { it > 0 }
            ?: return null
        val index = parts[4].toIntOrNull() ?: return null
        val total = parts[5].toIntOrNull() ?: return null

        if (total !in 1..100 || index !in 1..total) return null

        val memberId = decodeField(parts[6]) ?: return null
        val memberName = decodeField(parts[7]) ?: return null

        if (memberId.isBlank() || memberName.isBlank()) return null

        return FrnGroupMemberSync(
            groupId, version, index, total,
            memberId, memberName, message.senderId
        )
    }

    private fun encodeField(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String? =
        try {
            String(
                java.util.Base64.getUrlDecoder().decode(value),
                Charsets.UTF_8
            ).takeIf { it.length <= 128 }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun validUuid(value: String): String? =
        try {
            UUID.fromString(value).toString()
                .takeIf { it.equals(value, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
}
