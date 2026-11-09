package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

class MumbleAclRuleEditorTest {

    private fun rule() = Mumble.ACL.ChanACL.newBuilder()
        .setInherited(false)
        .setGrant(8)
        .setDeny(16)
        .setApplyHere(true)
        .setApplySubs(false)
        .build()

    @Test
    fun generalRuleIsRecognized() {
        assertEquals(
            MumbleAclRuleEditor.TargetType.GENERAL,
            MumbleAclRuleEditor.targetType(rule())
        )
    }

    @Test
    fun userTargetIsAssigned() {
        val updated = MumbleAclRuleEditor.updateTarget(
            rule(), MumbleAclRuleEditor.TargetType.USER, userId = 42
        )
        assertEquals(42, updated.userId)
        assertFalse(updated.hasGroup())
    }

    @Test
    fun groupTargetReplacesUserTarget() {
        val user = MumbleAclRuleEditor.updateTarget(
            rule(), MumbleAclRuleEditor.TargetType.USER, userId = 42
        )
        val group = MumbleAclRuleEditor.updateTarget(
            user, MumbleAclRuleEditor.TargetType.GROUP,
            groupName = "admins"
        )
        assertFalse(group.hasUserId())
        assertEquals("admins", group.group)
    }

    @Test
    fun generalTargetClearsPreviousTarget() {
        val group = MumbleAclRuleEditor.updateTarget(
            rule(), MumbleAclRuleEditor.TargetType.GROUP,
            groupName = "admins"
        )
        val general = MumbleAclRuleEditor.updateTarget(
            group, MumbleAclRuleEditor.TargetType.GENERAL
        )
        assertFalse(general.hasUserId())
        assertFalse(general.hasGroup())
    }

    @Test
    fun otherPropertiesArePreserved() {
        val updated = MumbleAclRuleEditor.updateTarget(
            rule(), MumbleAclRuleEditor.TargetType.USER, userId = 7
        )
        assertEquals(8, updated.grant)
        assertEquals(16, updated.deny)
        assertTrue(updated.applyHere)
        assertFalse(updated.applySubs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidUserIdIsRejected() {
        MumbleAclRuleEditor.updateTarget(
            rule(), MumbleAclRuleEditor.TargetType.USER, userId = -1
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankGroupIsRejected() {
        MumbleAclRuleEditor.updateTarget(
            rule(), MumbleAclRuleEditor.TargetType.GROUP,
            groupName = " "
        )
    }

    @Test
    fun permissionChangesPreserveUnknownBits() {
        val unknownBit = 1 shl 29
        val original = rule().toBuilder()
            .setGrant(unknownBit or 8)
            .setDeny(16)
            .build()

        val updated = MumbleAclRuleEditor.updatePermissions(
            original,
            mapOf(8 to MumbleAclPermissionEditor.State.DENY)
        )

        assertTrue(updated.grant and unknownBit != 0)
        assertEquals(0, updated.grant and 8)
        assertTrue(updated.deny and 8 != 0)
        assertEquals(16, updated.deny and 16)
    }

    @Test
    fun permissionCanBeAllowed() {
        val updated = MumbleAclRuleEditor.updatePermissions(
            rule(),
            mapOf(16 to MumbleAclPermissionEditor.State.ALLOW)
        )
        assertTrue(updated.grant and 16 != 0)
        assertEquals(0, updated.deny and 16)
    }

    @Test
    fun permissionCanBeDenied() {
        val updated = MumbleAclRuleEditor.updatePermissions(
            rule(),
            mapOf(8 to MumbleAclPermissionEditor.State.DENY)
        )
        assertEquals(0, updated.grant and 8)
        assertTrue(updated.deny and 8 != 0)
    }

    @Test
    fun permissionCanBeUnset() {
        val updated = MumbleAclRuleEditor.updatePermissions(
            rule(),
            mapOf(8 to MumbleAclPermissionEditor.State.UNSET)
        )
        assertEquals(0, updated.grant and 8)
        assertEquals(0, updated.deny and 8)
        assertEquals(16, updated.deny and 16)
    }

}
