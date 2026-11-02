package org.funkgateway.mobile.frn

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FrnChatGroupsTest {

    private val createdGroups = mutableListOf<String>()

    private fun createGroup(): FrnChatGroup {
        val contacts = listOf(
            FrnChatContact("host-1", "Host"),
            FrnChatContact("member-1", "Mitglied 1")
        )

        return FrnChatGroups.create(
            contacts = contacts,
            server = "test.example.org",
            room = "Test",
            creatorId = "host-1",
            initialMembers = listOf(contacts.first())
        ).also {
            createdGroups.add(it.id)
        }
    }

    @After
    fun tearDown() {
        createdGroups.forEach { FrnChatGroups.remove(it) }
    }

    @Test
    fun confirmedMemberIsAddedOnlyOnce() {
        val group = createGroup()

        assertTrue(
            FrnChatGroups.addConfirmedMember(
                group.id, "host-1", "member-1", "Mitglied 1"
            )
        )

        val revision = FrnChatGroups.currentRevision()
        val version = group.hostVersion

        assertFalse(
            FrnChatGroups.addConfirmedMember(
                group.id, "host-1", "member-1", "Mitglied 1"
            )
        )

        assertTrue(
            FrnChatGroups.hasConfirmedMember(
                group.id, "host-1", "member-1"
            )
        )

        assertEquals(2, group.participants.size)
        assertEquals(version, group.hostVersion)
        assertEquals(revision, FrnChatGroups.currentRevision())
    }

    @Test
    fun foreignHostCannotConfirmMembers() {
        val group = createGroup()

        assertFalse(
            FrnChatGroups.addConfirmedMember(
                group.id, "foreign-host", "member-1", "Mitglied 1"
            )
        )

        assertFalse(
            FrnChatGroups.hasConfirmedMember(
                group.id, "foreign-host", "member-1"
            )
        )

        assertFalse(group.participants.containsKey("member-1"))
    }

    @Test
    fun unknownMemberIsNotAlreadyConfirmed() {
        val group = createGroup()

        assertFalse(
            FrnChatGroups.hasConfirmedMember(
                group.id, "host-1", "unknown-member"
            )
        )
    }

    @Test
    fun confirmedMemberCanBeSelectedAsSuccessor() {
        val group = createGroup()
        assertTrue(FrnChatGroups.addConfirmedMember(
            group.id, "host-1", "member-1", "Mitglied 1"
        ))

        val transferId = FrnChatGroups.beginHostTransfer(
            group.id, "host-1", "member-1"
        )

        assertNotNull(transferId)
        assertEquals(transferId, group.pendingTransferId)
        assertEquals("member-1", group.pendingSuccessorFrnId)
        assertEquals("host-1", group.hostId)
        assertFalse(group.hostTransferLocked)
    }

    @Test
    fun unknownMemberCannotBecomeSuccessor() {
        val group = createGroup()

        assertNull(FrnChatGroups.beginHostTransfer(
            group.id, "host-1", "unknown-member"
        ))
        assertNull(FrnChatGroups.beginHostTransfer(
            group.id, "foreign-host", "member-1"
        ))
        assertTrue(group.pendingTransferId.isEmpty())
    }

    @Test
    fun secondTransferCannotReplaceFirst() {
        val group = createGroup()
        assertTrue(FrnChatGroups.addConfirmedMember(
            group.id, "host-1", "member-1", "Mitglied 1"
        ))

        val firstId = FrnChatGroups.beginHostTransfer(
            group.id, "host-1", "member-1"
        )
        assertNotNull(firstId)

        val revision = FrnChatGroups.currentRevision()

        assertNull(FrnChatGroups.beginHostTransfer(
            group.id, "host-1", "member-1"
        ))
        assertEquals(firstId, group.pendingTransferId)
        assertEquals(revision, FrnChatGroups.currentRevision())
    }



    @Test
    fun restoredIncompleteAcceptedTransferLocksHost() {
        val original = createGroup()
        val saved = original.copy(
            participants = LinkedHashMap(original.participants),
            gatewayMemberIds = original.gatewayMemberIds.toMutableSet(),
            pendingTransferId = java.util.UUID.randomUUID().toString(),
            pendingSuccessorFrnId = "member-1",
            pendingSuccessorGatewayId = "",
            hostTransferPhase = "ACCEPTED",
            hostTransferLocked = false
        )

        FrnChatGroups.remove(original.id)
        assertTrue(FrnChatGroups.restore(listOf(saved)))

        val restored = FrnChatGroups.get(original.id)
        assertNotNull(restored)
        assertEquals("ACCEPTED", restored!!.hostTransferPhase)
        assertTrue(restored.hostTransferLocked)
        assertFalse(FrnChatGroups.isHost(original.id, "host-1"))
    }

    @Test
    fun restoredCommittingTransferKeepsHostLocked() {
        val original = createGroup()
        val saved = original.copy(
            participants = LinkedHashMap(original.participants),
            gatewayMemberIds = original.gatewayMemberIds.toMutableSet(),
            hostTransferPhase = "COMMITTING",
            hostTransferLocked = false
        )

        FrnChatGroups.remove(original.id)
        assertTrue(FrnChatGroups.restore(listOf(saved)))

        val restored = FrnChatGroups.get(original.id)
        assertNotNull(restored)
        assertEquals("COMMITTING", restored!!.hostTransferPhase)
        assertTrue(restored.hostTransferLocked)
        assertFalse(FrnChatGroups.isHost(original.id, "host-1"))
    }

    @Test
    fun committingHostTransferLocksOldHost() {
        val group = createGroup()

        assertTrue(FrnChatGroups.addConfirmedMember(
            group.id, "host-1", "member-1", "Mitglied 1"
        ))

        val transferId = FrnChatGroups.beginHostTransfer(
            group.id, "host-1", "member-1"
        )
        assertNotNull(transferId)

        assertTrue(FrnChatGroups.acceptHostTransfer(
            group.id, "host-1", transferId!!,
            "member-1", java.util.UUID.randomUUID().toString()
        ))

        assertTrue(FrnChatGroups.isHost(group.id, "host-1"))

        assertTrue(FrnChatGroups.commitHostTransfer(
            group.id, "host-1", transferId
        ))

        assertEquals("COMMITTING", group.hostTransferPhase)
        assertTrue(group.hostTransferLocked)
        assertFalse(FrnChatGroups.isHost(group.id, "host-1"))

        assertFalse(FrnChatGroups.commitHostTransfer(
            group.id, "host-1", transferId
        ))
    }

    @Test
    fun hostTransferAcceptanceIsValidated() {
        val group = createGroup()
        assertTrue(FrnChatGroups.addConfirmedMember(
            group.id, "host-1", "member-1", "Mitglied 1"
        ))

        val transferId = FrnChatGroups.beginHostTransfer(
            group.id, "host-1", "member-1"
        )
        assertNotNull(transferId)
        assertEquals("OFFERED", group.hostTransferPhase)

        val gatewayId = java.util.UUID.randomUUID().toString()

        assertTrue(FrnChatGroups.acceptHostTransfer(
            group.id, "host-1", transferId!!,
            "member-1", gatewayId
        ))

        assertFalse(FrnChatGroups.acceptHostTransfer(
            group.id, "host-1", "falsche-id",
            "member-1", gatewayId
        ))

        assertFalse(FrnChatGroups.acceptHostTransfer(
            group.id, "host-1", transferId,
            "member-1", "ungueltige-uuid"
        ))

        assertFalse(FrnChatGroups.acceptHostTransfer(
            group.id, "host-1", transferId,
            "fremdes-mitglied", gatewayId
        ))

        assertEquals(gatewayId, group.pendingSuccessorGatewayId)
        assertEquals("ACCEPTED", group.hostTransferPhase)

        assertTrue(FrnChatGroups.acceptHostTransfer(
            group.id, "host-1", transferId,
            "member-1", gatewayId
        ))

        assertFalse(FrnChatGroups.acceptHostTransfer(
            group.id, "host-1", transferId,
            "member-1", java.util.UUID.randomUUID().toString()
        ))

        assertEquals(gatewayId, group.pendingSuccessorGatewayId)
        assertEquals("host-1", group.hostId)
        assertFalse(group.hostTransferLocked)
    }

}
