package org.funkgateway.mobile.frn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FrnGroupPersistenceTest {

    @Test
    fun groupSurvivesJsonRoundTrip() {
        val original = FrnChatGroup(
            id = UUID.randomUUID().toString(),
            name = "Testgruppe",
            participants = linkedMapOf(
                "host-123" to "Kapitän",
                "member-456" to "Funker"
            ),
            shortCode = "G3",
            server = "test.example.org",
            room = "TestRaum",
            hostId = "host-123",
            hostCallsign = "TEST123",
            hostFrnId = "123456",
            gatewayMemberIds = linkedSetOf("member-456"),
            hostToken = "test-token-123",
            hostVersion = 7L,
            active = true
        )

        val encoded = FrnGroupPersistence.encodeGroups(listOf(original))
        val restored = FrnGroupPersistence.decodeGroups(
            JSONObject(encoded.toString())
        )

        assertEquals(1, restored.size)

        val result = restored.single()

        assertEquals(original.id, result.id)
        assertEquals(original.name, result.name)
        assertEquals(original.shortCode, result.shortCode)
        assertEquals(original.server, result.server)
        assertEquals(original.room, result.room)
        assertEquals(original.hostId, result.hostId)
        assertEquals(original.hostCallsign, result.hostCallsign)
        assertEquals(original.hostFrnId, result.hostFrnId)
        assertEquals(original.hostToken, result.hostToken)
        assertEquals(original.hostVersion, result.hostVersion)
        assertEquals(original.active, result.active)
        assertEquals(original.participants, result.participants)
        assertEquals(original.gatewayMemberIds, result.gatewayMemberIds)
    }

    @Test
    fun invalidFormatVersionIsRejected() {
        val data = JSONObject().apply {
            put("formatVersion", 999)
        }

        assertTrue(FrnGroupPersistence.decodeGroups(data).isEmpty())
    }

    @Test
    fun emptyGroupListSurvivesRoundTrip() {
        val encoded = FrnGroupPersistence.encodeGroups(emptyList())
        val restored = FrnGroupPersistence.decodeGroups(encoded)

        assertTrue(restored.isEmpty())
    }
}
