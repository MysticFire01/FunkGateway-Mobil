package org.funkgateway.mobile.frn

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class FrnGroupInvitationParserTest {
    private val invitationId = UUID.randomUUID().toString()
    private val groupId = UUID.randomUUID().toString()
    private val hostId = UUID.randomUUID().toString()

    private fun invitation(
        privateMessage: Boolean = true,
        code: String = "G1"
    ): FrnChatMessage {
        val name = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("Sandgaleone".toByteArray(Charsets.UTF_8))

        return FrnChatMessage(
            sender = "DD1JP",
            senderId = UUID.randomUUID().toString(),
            message = "FGW1|INVITE|$invitationId|$groupId|$code|$name|server.example|Raum1|$hostId",
            privateMessage = privateMessage
        )
    }

    @Test
    fun encodedInvitationCanBeDecoded() {
        val original = FrnGroupInvitation(
            invitationId = invitationId,
            groupId = groupId,
            groupName = "Sandgaleone – Grüße aus Würzburg",
            shortCode = "G2",
            server = "server.example",
            room = "Raum1",
            senderId = UUID.randomUUID().toString(),
            senderName = "DD1JP",
            hostId = hostId
        )

        val encoded = FrnGroupInvitationParser.encode(original)
        val received = FrnChatMessage(
            sender = original.senderName,
            senderId = original.senderId,
            message = encoded,
            privateMessage = true
        )

        val decoded = FrnGroupInvitationParser.parse(received)

        assertNotNull(decoded)
        assertEquals(original.invitationId, decoded?.invitationId)
        assertEquals(original.groupId, decoded?.groupId)
        assertEquals(original.groupName, decoded?.groupName)
        assertEquals(original.shortCode, decoded?.shortCode)
        assertEquals(original.server, decoded?.server)
        assertEquals(original.room, decoded?.room)
        assertEquals(original.hostId, decoded?.hostId)
    }

    @Test
    fun validPrivateInvitationIsRecognized() {
        val result = FrnGroupInvitationParser.parse(invitation())
        assertNotNull(result)
        assertEquals("Sandgaleone", result?.groupName)
        assertEquals("G1", result?.shortCode)
        assertEquals(groupId, result?.groupId)
    }

    @Test
    fun publicMessageIsRejected() {
        assertNull(
            FrnGroupInvitationParser.parse(
                invitation(privateMessage = false)
            )
        )
    }

    @Test
    fun invalidGroupCodeIsRejected() {
        assertNull(
            FrnGroupInvitationParser.parse(
                invitation(code = "G99")
            )
        )
    }
}
