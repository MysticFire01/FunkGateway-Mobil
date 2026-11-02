package org.funkgateway.mobile.frn

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FrnHostTransferAuthorizationTest {
    private val groupId = UUID.randomUUID().toString()
    private val transferId = UUID.randomUUID().toString()

    private fun group() = FrnChatGroup(
        id = groupId,
        name = "Testgruppe",
        participants = linkedMapOf(
            "host-frn" to "Host",
            "successor-frn" to "Nachfolger"
        ),
        hostFrnId = "host-frn",
        gatewayMemberIds = linkedSetOf("successor-frn"),
        hostVersion = 2L
    )

    private fun offer(sender: String, successor: String = "successor-frn") =
        FrnHostTransfer(
            type = FrnHostTransferType.OFFER,
            transferId = transferId,
            groupId = groupId,
            version = 2L,
            successorId = successor,
            senderId = sender
        )

    @Test
    fun legitimateHostCanOfferTransfer() {
        assertTrue(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                offer("host-frn"), group()
            )
        )
    }

    @Test
    fun anotherMemberCannotOfferTransfer() {
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                offer("successor-frn"), group()
            )
        )
    }

    @Test
    fun unknownSuccessorIsRejected() {
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                offer("host-frn", "unknown-frn"), group()
            )
        )
    }

    @Test
    fun wrongVersionIsRejected() {
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                offer("host-frn").copy(version = 99L), group()
            )
        )
    }

    @Test
    fun wrongGroupIsRejected() {
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                offer("host-frn").copy(
                    groupId = UUID.randomUUID().toString()
                ), group()
            )
        )
    }

    @Test
    fun onlySelectedSuccessorCanAccept() {
        val gatewayId = UUID.randomUUID().toString()
        val pendingGroup = group().copy(
            pendingTransferId = transferId,
            pendingSuccessorFrnId = "successor-frn",
            hostTransferPhase = "OFFERED"
        )
        val acceptance = FrnHostTransfer(
            type = FrnHostTransferType.ACCEPT,
            transferId = transferId,
            groupId = groupId,
            version = 2L,
            successorId = "successor-frn",
            senderId = "successor-frn",
            successorGatewayId = gatewayId
        )

        assertTrue(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                acceptance, pendingGroup
            )
        )
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                acceptance.copy(senderId = "unknown-frn"), pendingGroup
            )
        )
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                acceptance.copy(transferId = UUID.randomUUID().toString()),
                pendingGroup
            )
        )
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                acceptance.copy(successorGatewayId = ""), pendingGroup
            )
        )
    }

    @Test
    fun onlyCurrentHostCanCommitAcceptedTransfer() {
        val gatewayId = UUID.randomUUID().toString()
        val group = group().copy(
            pendingTransferId = transferId,
            pendingSuccessorFrnId = "successor-frn",
            pendingSuccessorGatewayId = gatewayId,
            hostTransferPhase = "ACCEPTED"
        )
        val commit = FrnHostTransfer(
            FrnHostTransferType.COMMIT,
            transferId, groupId, 2L,
            "successor-frn", "host-frn", gatewayId
        )

        assertTrue(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(commit, group)
        )
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                commit.copy(senderId = "successor-frn"), group
            )
        )
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                commit.copy(successorGatewayId = UUID.randomUUID().toString()),
                group
            )
        )
    }

    @Test
    fun onlySuccessorCanAcknowledgeLockedTransfer() {
        val gatewayId = UUID.randomUUID().toString()
        val group = group().copy(
            pendingTransferId = transferId,
            pendingSuccessorFrnId = "successor-frn",
            pendingSuccessorGatewayId = gatewayId,
            hostTransferPhase = "COMMITTING",
            hostTransferLocked = true
        )
        val ack = FrnHostTransfer(
            FrnHostTransferType.ACK,
            transferId, groupId, 2L,
            "successor-frn", "successor-frn", gatewayId
        )

        assertTrue(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(ack, group)
        )
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                ack.copy(senderId = "unknown-frn"), group
            )
        )
        assertFalse(
            FrnHostTransferAuthorization.isAuthorizedHostTransfer(
                ack, group.copy(hostTransferLocked = false)
            )
        )
    }
}
