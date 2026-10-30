package org.funkgateway.mobile.frn

import java.util.UUID

enum class FrnHostTransferType {
    OFFER, ACCEPT, DECLINE, COMMIT, ACK
}

data class FrnHostTransfer(
    val type: FrnHostTransferType,
    val transferId: String,
    val groupId: String,
    val version: Long,
    val successorId: String,
    val senderId: String,
    val successorGatewayId: String = ""
)

object FrnHostTransferParser {
    fun encode(
        type: FrnHostTransferType,
        transferId: String,
        groupId: String,
        version: Long,
        successorId: String,
        successorGatewayId: String = ""
    ): String {
        require(validUuid(transferId) != null)
        require(validUuid(groupId) != null)
        require(version > 0)
        require(successorId.isNotBlank() && successorId.length <= 128)
        val encodedSuccessor = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(successorId.toByteArray(Charsets.UTF_8))
        require(successorGatewayId.isEmpty() ||
            validUuid(successorGatewayId) != null)
        val result =
            "FGWH1|${type.name}|$transferId|$groupId|$version|$encodedSuccessor"
        val extended = if (successorGatewayId.isBlank()) result
            else "$result|$successorGatewayId"
        require(extended.length <= 500)
        return extended
    }

    fun parse(message: FrnChatMessage): FrnHostTransfer? {
        if (!message.privateMessage || message.senderId.isBlank()) return null
        val parts = message.message.split('|')
        if (parts.size !in 6..7 || parts[0] != "FGWH1") return null
        val type = try {
            FrnHostTransferType.valueOf(parts[1])
        } catch (_: IllegalArgumentException) {
            return null
        }
        val transferId = validUuid(parts[2]) ?: return null
        val groupId = validUuid(parts[3]) ?: return null
        val version = parts[4].toLongOrNull()
            ?.takeIf { it > 0 } ?: return null
        val successorId = try {
            String(
                java.util.Base64.getUrlDecoder().decode(parts[5]),
                Charsets.UTF_8
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (successorId.isBlank() || successorId.length > 128) return null
        val gatewayId = if (parts.size == 7)
            validUuid(parts[6]) ?: return null
        else ""
        return FrnHostTransfer(
            type, transferId, groupId, version,
            successorId, message.senderId, gatewayId
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

object FrnHostTransferAuthorization {
    fun isAuthorizedHostTransfer(
        transfer: FrnHostTransfer,
        group: FrnChatGroup
    ): Boolean {
        if (!group.active) return false
        if (transfer.groupId != group.id) return false
        if (transfer.version != group.hostVersion) return false
        if (transfer.transferId.isBlank()) return false
        if (transfer.successorId.isBlank()) return false

        val host = group.hostFrnId
        val successor = group.pendingSuccessorFrnId

        if (transfer.type == FrnHostTransferType.OFFER) {
            return host.isNotBlank() &&
                transfer.senderId == host &&
                transfer.successorId != host &&
                group.gatewayMemberIds.contains(transfer.successorId) &&
                group.participants.containsKey(transfer.successorId)
        }

        if (transfer.transferId != group.pendingTransferId) return false
        if (transfer.successorId != successor || successor.isBlank()) return false

        return when (transfer.type) {
            FrnHostTransferType.OFFER -> false
            FrnHostTransferType.ACCEPT ->
                group.hostTransferPhase == "OFFERED" &&
                transfer.senderId == successor &&
                transfer.successorGatewayId.isNotBlank()
            FrnHostTransferType.DECLINE ->
                group.hostTransferPhase == "OFFERED" &&
                transfer.senderId == successor
            FrnHostTransferType.COMMIT ->
                group.hostTransferPhase == "ACCEPTED" &&
                transfer.senderId == host &&
                transfer.successorGatewayId ==
                    group.pendingSuccessorGatewayId &&
                transfer.successorGatewayId.isNotBlank()
            FrnHostTransferType.ACK ->
                group.hostTransferPhase == "COMMITTING" &&
                group.hostTransferLocked &&
                transfer.senderId == successor &&
                transfer.successorGatewayId ==
                    group.pendingSuccessorGatewayId &&
                transfer.successorGatewayId.isNotBlank()
        }
    }
}
