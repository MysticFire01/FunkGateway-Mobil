package org.funkgateway.mobile.frn

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FrnHostTransferParserTest {
    private val transferId = UUID.randomUUID().toString()
    private val groupId = UUID.randomUUID().toString()

    @Test
    fun allMessageTypesEncodeCorrectly() {
        FrnHostTransferType.values().forEach { type ->
            val encoded = FrnHostTransferParser.encode(
                type, transferId, groupId, 2L, "member-123"
            )
            assertTrue(encoded.startsWith("FGWH1|${type.name}|"))
            assertTrue(encoded.length <= 500)
        }
    }

    @Test
    fun invalidUuidIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FrnHostTransferParser.encode(
                FrnHostTransferType.OFFER,
                "invalid", groupId, 2L, "member-123"
            )
        }
    }

    @Test
    fun invalidVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FrnHostTransferParser.encode(
                FrnHostTransferType.COMMIT,
                transferId, groupId, 0L, "member-123"
            )
        }
    }

    @Test
    fun emptySuccessorIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FrnHostTransferParser.encode(
                FrnHostTransferType.OFFER,
                transferId, groupId, 2L, ""
            )
        }
    }

    @Test
    fun allMessageTypesDecodeCorrectly() {
        FrnHostTransferType.values().forEach { type ->
            val encoded = FrnHostTransferParser.encode(
                type, transferId, groupId, 2L, "member-123"
            )
            val received = FrnChatMessage(
                "Alter Host", "host-frn-123", encoded, true
            )
            val decoded = FrnHostTransferParser.parse(received)
            assertNotNull(decoded)
            assertEquals(type, decoded!!.type)
            assertEquals(transferId, decoded.transferId)
            assertEquals(groupId, decoded.groupId)
            assertEquals(2L, decoded.version)
            assertEquals("member-123", decoded.successorId)
            assertEquals("host-frn-123", decoded.senderId)
        }
    }

    @Test
    fun publicMessageIsRejected() {
        val encoded = FrnHostTransferParser.encode(
            FrnHostTransferType.OFFER,
            transferId, groupId, 2L, "member-123"
        )
        assertNull(
            FrnHostTransferParser.parse(
                FrnChatMessage("Host", "host-1", encoded, false)
            )
        )
    }

    @Test
    fun emptySenderIsRejected() {
        val encoded = FrnHostTransferParser.encode(
            FrnHostTransferType.ACCEPT,
            transferId, groupId, 2L, "member-123"
        )
        assertNull(
            FrnHostTransferParser.parse(
                FrnChatMessage("Host", "", encoded, true)
            )
        )
    }

    @Test
    fun invalidReceivedVersionIsRejected() {
        val encoded = FrnHostTransferParser.encode(
            FrnHostTransferType.COMMIT,
            transferId, groupId, 2L, "member-123"
        )
        val invalid = encoded.replace("|2|", "|0|")
        assertNull(
            FrnHostTransferParser.parse(
                FrnChatMessage("Host", "host-1", invalid, true)
            )
        )
    }


    @org.junit.Test
    fun successorGatewayIdRoundTrip() {
        val transferId = java.util.UUID.randomUUID().toString()
        val groupId = java.util.UUID.randomUUID().toString()
        val gatewayId = java.util.UUID.randomUUID().toString()

        val encoded = FrnHostTransferParser.encode(
            FrnHostTransferType.ACCEPT,
            transferId,
            groupId,
            2L,
            "successor-frn",
            gatewayId
        )

        val parsed = FrnHostTransferParser.parse(
            FrnChatMessage(
                sender = "Nachfolger",
                senderId = "successor-frn",
                message = encoded,
                privateMessage = true
            )
        )

        org.junit.Assert.assertNotNull(parsed)
        org.junit.Assert.assertEquals(gatewayId, parsed!!.successorGatewayId)
        org.junit.Assert.assertEquals(
            FrnHostTransferType.ACCEPT,
            parsed.type
        )

        org.junit.Assert.assertNull(
            FrnHostTransferParser.parse(
                FrnChatMessage(
                    sender = "Nachfolger",
                    senderId = "successor-frn",
                    message = encoded.substringBeforeLast('|') + "|invalid",
                    privateMessage = true
                )
            )
        )
    }

}
