package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleAclChangeSafetyTest {

    private fun snapshot(channelId: Int = 7) =
        MumbleAclManager.AclSnapshot(
            channelId = channelId,
            inheritAcls = true,
            groups = emptyList(),
            rules = emptyList()
        )

    private fun context(
        connected: Boolean = true,
        generation: Long = 3,
        expectedGeneration: Long = 3,
        selectedChannelId: Int? = 7,
        requestedChannelId: Int = 7,
        serverSnapshot: MumbleAclManager.AclSnapshot? = snapshot(),
        draft: MumbleAclDraft? = MumbleAclDraft(snapshot()).apply {
            setInheritance(false)
        }
    ) = MumbleAclChangeSafety.Context(
        connected = connected,
        connectionGeneration = generation,
        expectedGeneration = expectedGeneration,
        selectedChannelId = selectedChannelId,
        requestedChannelId = requestedChannelId,
        serverSnapshot = serverSnapshot,
        draft = draft
    )

    @Test
    fun validContextAllowsReview() {
        assertTrue(MumbleAclChangeSafety.check(context()).validForReview)
    }

    @Test
    fun disconnectedContextBlocksReview() {
        val result = MumbleAclChangeSafety.check(
            context(connected = false)
        )
        assertFalse(result.validForReview)
        assertTrue(result.reasons.any { it.contains("Verbindung") })
    }

    @Test
    fun changedConnectionGenerationBlocksReview() {
        val result = MumbleAclChangeSafety.check(
            context(generation = 4)
        )
        assertFalse(result.validForReview)
    }

    @Test
    fun changedChannelBlocksReview() {
        val result = MumbleAclChangeSafety.check(
            context(selectedChannelId = 8)
        )
        assertFalse(result.validForReview)
    }

    @Test
    fun changedServerAclBlocksReview() {
        val changed = snapshot().copy(inheritAcls = false)
        val result = MumbleAclChangeSafety.check(
            context(serverSnapshot = changed)
        )
        assertFalse(result.validForReview)
    }

    @Test
    fun missingSnapshotBlocksReview() {
        assertFalse(
            MumbleAclChangeSafety.check(
                context(serverSnapshot = null)
            ).validForReview
        )
    }

    @Test
    fun missingDraftBlocksReview() {
        assertFalse(
            MumbleAclChangeSafety.check(
                context(draft = null)
            ).validForReview
        )
    }

    @Test
    fun unchangedDraftBlocksReview() {
        assertFalse(
            MumbleAclChangeSafety.check(
                context(draft = MumbleAclDraft(snapshot()))
            ).validForReview
        )
    }
}
