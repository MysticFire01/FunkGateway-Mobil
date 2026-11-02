package org.funkgateway.mobile.frn

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class FrnGroupMemberSyncBufferTest {

    private lateinit var groupId: String

    @Before
    fun setUp() {
        FrnGroupMemberSyncBuffer.clear()
        groupId = UUID.randomUUID().toString()
    }

    @After
    fun tearDown() {
        FrnGroupMemberSyncBuffer.clear()
    }

    private fun member(
        index: Int,
        total: Int = 2,
        name: String = "Mitglied $index",
        version: Long = 2L
    ) = FrnGroupMemberSync(
        groupId = groupId,
        version = version,
        index = index,
        total = total,
        memberId = "mitglied-$index",
        memberName = name,
        senderId = "host-123"
    )

    @Test
    fun incompleteListIsNotApplied() {
        assertNull(FrnGroupMemberSyncBuffer.accept(member(1)))
    }

    @Test
    fun completeListIsAssembled() {
        assertNull(FrnGroupMemberSyncBuffer.accept(member(2)))

        val result = FrnGroupMemberSyncBuffer.accept(member(1))

        assertNotNull(result)
        assertEquals(groupId, result!!.groupId)
        assertEquals(2L, result.version)
        assertEquals(2, result.members.size)
        assertEquals(
            listOf("mitglied-1", "mitglied-2"),
            result.members.keys.toList()
        )
    }

    @Test
    fun conflictingPartIsRejected() {
        assertNull(FrnGroupMemberSyncBuffer.accept(member(1)))

        assertNull(
            FrnGroupMemberSyncBuffer.accept(
                member(1, name = "Anderer Name")
            )
        )

        // Der widersprüchliche Stapel wurde verworfen.
        assertNull(FrnGroupMemberSyncBuffer.accept(member(2)))
    }

    @Test
    fun clearRemovesIncompleteLists() {
        assertNull(FrnGroupMemberSyncBuffer.accept(member(1)))

        FrnGroupMemberSyncBuffer.clear()

        assertNull(FrnGroupMemberSyncBuffer.accept(member(2)))
    }
}
