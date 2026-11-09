package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

class MumbleAclPreparationTest {

    private fun snapshot(
        channelId: Int = 1,
        inheritAcls: Boolean = true,
        rules: List<Mumble.ACL.ChanACL> = emptyList()
    ) = MumbleAclManager.AclSnapshot(
        channelId = channelId,
        inheritAcls = inheritAcls,
        groups = emptyList(),
        rules = rules
    )

    @Test
    fun identicalServerStateIsAccepted() {
        val original = snapshot()
        val current = snapshot()

        assertTrue(
            MumbleAclPreparation.isServerStateUnchanged(
                original, current
            )
        )
    }

    @Test
    fun differentChannelIsRejected() {
        assertFalse(
            MumbleAclPreparation.isServerStateUnchanged(
                snapshot(channelId = 1),
                snapshot(channelId = 2)
            )
        )
    }

    @Test
    fun changedAclRulesAreRejected() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setGroup("all")
            .setGrant(1)
            .build()

        assertFalse(
            MumbleAclPreparation.isServerStateUnchanged(
                snapshot(),
                snapshot(rules = listOf(rule))
            )
        )
    }

    @Test
    fun draftRequiresChangesAndUnchangedServer() {
        val original = snapshot()
        val draft = MumbleAclDraft(original)

        assertFalse(
            MumbleAclPreparation.canPrepareChanges(draft, original)
        )

        draft.setInheritance(false)

        assertTrue(
            MumbleAclPreparation.canPrepareChanges(draft, original)
        )

        assertFalse(
            MumbleAclPreparation.canPrepareChanges(
                draft,
                snapshot(inheritAcls = false)
            )
        )
    }

    @Test
    fun inheritedGroupsAreExcluded() {
        val inherited = Mumble.ACL.ChanGroup.newBuilder()
            .setName("admin")
            .setInherited(true)
            .build()
        val local = Mumble.ACL.ChanGroup.newBuilder()
            .setName("users")
            .setInherited(false)
            .addAdd(42)
            .build()

        val original = snapshot().copy(
            groups = listOf(inherited, local)
        )
        val result = MumbleAclPreparation.localGroups(
            MumbleAclDraft(original)
        )

        assertEquals(1, result.size)
        assertEquals("users", result.single().name)
        assertEquals(listOf(42), result.single().addList)
    }

    @Test
    fun inheritedMembersAreClearedButExclusionsRemain() {
        val group = Mumble.ACL.ChanGroup.newBuilder()
            .setName("admin")
            .setInherited(false)
            .addInheritedMembers(101)
            .addRemove(101)
            .build()

        val draft = MumbleAclDraft(
            snapshot().copy(groups = listOf(group))
        )
        val result = MumbleAclPreparation.localGroups(draft)

        assertTrue(result.single().inheritedMembersList.isEmpty())
        assertEquals(listOf(101), result.single().removeList)
    }

    @Test
    fun localRulesPreserveOrderAndPermissionBits() {
        val inherited = Mumble.ACL.ChanACL.newBuilder()
            .setGroup("all")
            .setInherited(true)
            .setGrant(1)
            .build()
        val first = Mumble.ACL.ChanACL.newBuilder()
            .setGroup("admin")
            .setInherited(false)
            .setGrant(0x40000000)
            .build()
        val second = Mumble.ACL.ChanACL.newBuilder()
            .setUserId(42)
            .setInherited(false)
            .setDeny(8)
            .build()

        val draft = MumbleAclDraft(
            snapshot(rules = listOf(inherited, first, second))
        )
        val result = MumbleAclPreparation.localRules(draft)

        assertEquals(listOf(first, second), result)
        assertEquals(0x40000000, result[0].grant)
        assertEquals(8, result[1].deny)
    }

    @Test
    fun unsetInheritedFieldDefaultsToTrue() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setGroup("users")
            .setGrant(1)
            .build()

        assertFalse(rule.hasInherited())
        assertTrue(rule.inherited)

        val draft = MumbleAclDraft(
            snapshot(rules = listOf(rule))
        )

        assertTrue(
            MumbleAclPreparation.localRules(draft).isEmpty()
        )
    }

    @Test
    fun inheritedGroupWithLocalOverrideIsValid() {
        val inherited = Mumble.ACL.ChanGroup.newBuilder()
            .setName("admin").setInherited(true).build()
        val local = Mumble.ACL.ChanGroup.newBuilder()
            .setName("admin").setInherited(false)
            .setInherit(true).addRemove(101).build()

        val draft = MumbleAclDraft(
            snapshot().copy(groups = listOf(inherited, local))
        )
        assertTrue(
            MumbleAclPreparation.validateLocalGroups(draft).isEmpty()
        )
    }

    @Test
    fun duplicateLocalGroupNamesAreRejected() {
        val group = Mumble.ACL.ChanGroup.newBuilder()
            .setName("admin").setInherited(false).build()
        val draft = MumbleAclDraft(
            snapshot().copy(groups = listOf(group, group))
        )
        assertTrue(
            MumbleAclPreparation.validateLocalGroups(draft)
                .any { it.contains("Doppelte") }
        )
    }

    @Test
    fun negativeMemberIdsAreRejected() {
        val group = Mumble.ACL.ChanGroup.newBuilder()
            .setName("users").setInherited(false)
            .addAdd(-1).build()
        val draft = MumbleAclDraft(
            snapshot().copy(groups = listOf(group))
        )
        assertTrue(
            MumbleAclPreparation.validateLocalGroups(draft)
                .any { it.contains("Benutzer-ID") }
        )
    }

    @Test
    fun conflictingMembershipIsRejected() {
        val group = Mumble.ACL.ChanGroup.newBuilder()
            .setName("users").setInherited(false)
            .addAdd(42).addRemove(42).build()
        val draft = MumbleAclDraft(
            snapshot().copy(groups = listOf(group))
        )
        assertTrue(
            MumbleAclPreparation.validateLocalGroups(draft)
                .any { it.contains("gleichzeitig") }
        )
    }

    @Test
    fun unchangedDraftProducesNoWriteMessage() {
        val original = snapshot()
        val draft = MumbleAclDraft(original)
        assertTrue(
            MumbleAclPreparation.prepareWriteMessage(draft, original) == null
        )
    }

    @Test
    fun changedServerBlocksWriteMessage() {
        val original = snapshot()
        val draft = MumbleAclDraft(original)
        draft.setInheritance(false)
        assertTrue(
            MumbleAclPreparation.prepareWriteMessage(
                draft, original.copy(channelId = 2)
            ) == null
        )
    }

    @Test
    fun invalidGroupBlocksWriteMessage() {
        val group = Mumble.ACL.ChanGroup.newBuilder()
            .setName("users").setInherited(false)
            .addAdd(42).addRemove(42).build()
        val original = snapshot().copy(groups = listOf(group))
        val draft = MumbleAclDraft(original)
        draft.setInheritance(false)
        assertTrue(
            MumbleAclPreparation.prepareWriteMessage(draft, original) == null
        )
    }

    @Test
    fun writeMessagePreservesLocalData() {
        val inherited = Mumble.ACL.ChanGroup.newBuilder()
            .setName("admin").setInherited(true).build()
        val local = Mumble.ACL.ChanGroup.newBuilder()
            .setName("admin").setInherited(false)
            .addRemove(101).addInheritedMembers(101).build()
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("admin")
            .setGrant(0x40000000).setDeny(0x20000000)
            .build()
        val original = snapshot(
            channelId = 7, rules = listOf(rule)
        ).copy(groups = listOf(inherited, local))
        val draft = MumbleAclDraft(original)
        draft.setInheritance(false)

        val message = MumbleAclPreparation.prepareWriteMessage(
            draft, original
        )
        assertTrue(message != null)
        val result = message!!
        assertTrue(result.channelId == 7)
        assertTrue(!result.query)
        assertTrue(!result.inheritAcls)
        assertTrue(result.groupsCount == 1)
        assertTrue(result.getGroups(0).name == "admin")
        assertTrue(result.getGroups(0).removeList == listOf(101))
        assertTrue(result.getGroups(0).inheritedMembersCount == 0)
        assertTrue(result.aclsCount == 1)
        assertTrue(result.getAcls(0).grant == 0x40000000)
        assertTrue(result.getAcls(0).deny == 0x20000000)
    }

    @Test
    fun aclRuleWithTwoTargetsIsRejected() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setUserId(42)
            .setGroup("admin").build()
        val draft = MumbleAclDraft(snapshot(rules = listOf(rule)))
        assertTrue(MumbleAclPreparation.validateLocalRules(draft)
            .any { it.contains("gleichzeitig") })
    }

    @Test
    fun aclRuleWithNegativeUserIdIsRejected() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setUserId(-1).build()
        val draft = MumbleAclDraft(snapshot(rules = listOf(rule)))
        assertTrue(MumbleAclPreparation.validateLocalRules(draft)
            .any { it.contains("Benutzer-ID") })
    }

    @Test
    fun aclRuleWithEmptyGroupIsRejected() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("   ").build()
        val draft = MumbleAclDraft(snapshot(rules = listOf(rule)))
        assertTrue(MumbleAclPreparation.validateLocalRules(draft)
            .any { it.contains("Gruppenname") })
    }

    @Test
    fun validAclRuleIsAccepted() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("admin")
            .setApplyHere(true).setApplySubs(false)
            .setGrant(0x40000000).setDeny(0x20000000)
            .build()
        val draft = MumbleAclDraft(snapshot(rules = listOf(rule)))
        assertTrue(MumbleAclPreparation.validateLocalRules(draft).isEmpty())
        assertTrue(MumbleAclPreparation.localRules(draft) == listOf(rule))
    }

    @Test
    fun invalidAclRuleBlocksWritePreparation() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setUserId(-1).build()
        val original = snapshot(rules = listOf(rule))
        val draft = MumbleAclDraft(original)
        draft.setInheritance(false)
        assertTrue(
            MumbleAclPreparation.prepareWriteMessage(draft, original) == null
        )
    }

    @Test
    fun localRuleCanBeAdded() {
        val draft = MumbleAclDraft(snapshot())
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("users")
            .setGrant(0x40000000).build()

        assertTrue(draft.addRule(rule))
        assertTrue(draft.getRules() == listOf(rule))
        assertTrue(draft.hasChanges())
    }

    @Test
    fun localRuleCanBeUpdated() {
        val originalRule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("users").build()
        val draft = MumbleAclDraft(
            snapshot(rules = listOf(originalRule))
        )
        val updated = originalRule.toBuilder()
            .setGrant(0x40000000).build()

        assertTrue(draft.updateRule(0, updated))
        assertTrue(draft.getRules() == listOf(updated))
    }

    @Test
    fun localRuleCanBeRemoved() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("users").build()
        val draft = MumbleAclDraft(
            snapshot(rules = listOf(rule))
        )

        assertTrue(draft.removeRule(0))
        assertTrue(draft.getRules().isEmpty())
    }

    @Test
    fun inheritedRuleCannotBeModifiedOrRemoved() {
        val inherited = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(true).setGroup("admin").build()
        val draft = MumbleAclDraft(
            snapshot(rules = listOf(inherited))
        )
        val replacement = inherited.toBuilder()
            .setInherited(false).build()

        assertTrue(!draft.updateRule(0, replacement))
        assertTrue(!draft.removeRule(0))
        assertTrue(draft.getRules() == listOf(inherited))
        assertTrue(!draft.hasChanges())
    }

    @Test
    fun invalidRuleOperationsAreRejected() {
        val draft = MumbleAclDraft(snapshot())
        val inherited = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(true).build()
        val local = inherited.toBuilder()
            .setInherited(false).build()

        assertTrue(!draft.addRule(inherited))
        assertTrue(!draft.updateRule(-1, local))
        assertTrue(!draft.updateRule(0, local))
        assertTrue(!draft.removeRule(-1))
        assertTrue(!draft.removeRule(0))
        assertTrue(draft.getRules().isEmpty())
    }

    @Test
    fun localRulesCanMoveDownAndUp() {
        val a = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("A").build()
        val b = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("B").build()
        val c = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("C").build()

        val draft = MumbleAclDraft(
            snapshot(rules = listOf(a, b, c))
        )

        assertTrue(draft.moveRule(0, 1))
        assertTrue(draft.getRules() == listOf(b, a, c))
        assertTrue(draft.moveRule(1, -1))
        assertTrue(draft.getRules() == listOf(a, b, c))
    }

    @Test
    fun inheritedRuleCannotMove() {
        val inherited = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(true).setGroup("admin").build()
        val local = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("users").build()

        val draft = MumbleAclDraft(
            snapshot(rules = listOf(inherited, local))
        )

        assertTrue(!draft.moveRule(0, 1))
        assertTrue(!draft.moveRule(1, -1))
        assertTrue(draft.getRules() == listOf(inherited, local))
        assertTrue(!draft.hasChanges())
    }

    @Test
    fun invalidMovePositionsAreRejected() {
        val rule = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("users").build()
        val draft = MumbleAclDraft(
            snapshot(rules = listOf(rule))
        )

        assertTrue(!draft.moveRule(-1, 1))
        assertTrue(!draft.moveRule(0, -1))
        assertTrue(!draft.moveRule(0, 1))
        assertTrue(!draft.moveRule(1, -1))
        assertTrue(draft.getRules() == listOf(rule))
    }

    @Test
    fun invalidMoveDirectionsAreRejected() {
        val a = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("A").build()
        val b = Mumble.ACL.ChanACL.newBuilder()
            .setInherited(false).setGroup("B").build()
        val draft = MumbleAclDraft(
            snapshot(rules = listOf(a, b))
        )

        assertTrue(!draft.moveRule(0, 0))
        assertTrue(!draft.moveRule(0, 2))
        assertTrue(!draft.moveRule(1, -2))
        assertTrue(draft.getRules() == listOf(a, b))
    }
}
