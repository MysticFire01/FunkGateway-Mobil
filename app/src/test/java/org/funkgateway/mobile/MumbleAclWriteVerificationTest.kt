package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleAclWriteVerificationTest {

    private val valid = MumbleAclWriteVerification.Context(
        connected = true,
        expectedGeneration = 5L,
        currentGeneration = 5L,
        expectedChannelId = 9,
        receivedChannelId = 9,
        versionBeforeWrite = 3L,
        receivedVersion = 4L
    )

    @Test
    fun acceptsNewResponseForSameConnectionAndChannel() {
        assertTrue(
            MumbleAclWriteVerification.isCandidateResponse(valid)
        )
    }

    @Test
    fun rejectsDisconnectedConnection() {
        assertFalse(
            MumbleAclWriteVerification.isCandidateResponse(
                valid.copy(connected = false)
            )
        )
    }

    @Test
    fun rejectsChangedConnection() {
        assertFalse(
            MumbleAclWriteVerification.isCandidateResponse(
                valid.copy(currentGeneration = 6L)
            )
        )
    }

    @Test
    fun rejectsWrongChannel() {
        assertFalse(
            MumbleAclWriteVerification.isCandidateResponse(
                valid.copy(receivedChannelId = 10)
            )
        )
    }

    @Test
    fun rejectsOldOrSameVersion() {
        assertFalse(
            MumbleAclWriteVerification.isCandidateResponse(
                valid.copy(receivedVersion = 3L)
            )
        )
        assertFalse(
            MumbleAclWriteVerification.isCandidateResponse(
                valid.copy(receivedVersion = 2L)
            )
        )
    }

    @Test
    fun rejectsMissingInitialVersion() {
        assertFalse(
            MumbleAclWriteVerification.isCandidateResponse(
                valid.copy(versionBeforeWrite = 0L)
            )
        )
    }

    private fun group(
        member: Int = 12,
        inherited: Boolean = false
    ) = se.lublin.humla.protobuf.Mumble.ACL.ChanGroup
        .newBuilder()
        .setName("Funkfreunde")
        .setInherited(inherited)
        .addAdd(member)
        .build()

    private fun rule(
        allow: Int = 8,
        inherited: Boolean = false
    ) = se.lublin.humla.protobuf.Mumble.ACL.ChanACL
        .newBuilder()
        .setGroup("Funkfreunde")
        .setGrant(allow)
        .setInherited(inherited)
        .build()

    private fun snapshot(
        groups: List<se.lublin.humla.protobuf.Mumble.ACL.ChanGroup> =
            listOf(group()),
        rules: List<se.lublin.humla.protobuf.Mumble.ACL.ChanACL> =
            listOf(rule()),
        inheritance: Boolean = true
    ) = MumbleAclManager.AclSnapshot(
        channelId = 9,
        inheritAcls = inheritance,
        groups = groups,
        rules = rules
    )

    @Test
    fun acceptsMatchingAclContent() {
        val original = snapshot()
        val draft = MumbleAclDraft(original)

        assertTrue(
            MumbleAclWriteVerification.matchesExpectedContent(
                draft, original
            )
        )
    }

    @Test
    fun rejectsDifferentGroupMembers() {
        val draft = MumbleAclDraft(snapshot())

        assertFalse(
            MumbleAclWriteVerification.matchesExpectedContent(
                draft,
                snapshot(groups = listOf(group(member = 13)))
            )
        )
    }

    @Test
    fun rejectsDifferentPermissions() {
        val draft = MumbleAclDraft(snapshot())

        assertFalse(
            MumbleAclWriteVerification.matchesExpectedContent(
                draft,
                snapshot(rules = listOf(rule(allow = 4)))
            )
        )
    }

    @Test
    fun rejectsDifferentInheritance() {
        val draft = MumbleAclDraft(snapshot())

        assertFalse(
            MumbleAclWriteVerification.matchesExpectedContent(
                draft,
                snapshot(inheritance = false)
            )
        )
    }

    @Test
    fun ignoresInheritedEntriesInLocalComparison() {
        val draft = MumbleAclDraft(snapshot())

        val received = snapshot(
            groups = listOf(group(), group(99, inherited = true)),
            rules = listOf(rule(), rule(4, inherited = true))
        )

        assertTrue(
            MumbleAclWriteVerification.matchesExpectedContent(
                draft, received
            )
        )
    }

    @Test
    fun rejectsDifferentRuleOrder() {
        val original = snapshot(
            rules = listOf(rule(8), rule(4))
        )
        val draft = MumbleAclDraft(original)

        assertFalse(
            MumbleAclWriteVerification.matchesExpectedContent(
                draft,
                snapshot(rules = listOf(rule(4), rule(8)))
            )
        )
    }

}
