package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

class MumbleAclChangeSummaryTest {

    private fun rule(name: String) =
        Mumble.ACL.ChanACL.newBuilder()
            .setGroup(name)
            .setInherited(false)
            .setGrant(1)
            .build()

    private fun summary(
        original: List<Mumble.ACL.ChanACL>,
        current: List<Mumble.ACL.ChanACL>
    ): MumbleAclChangeSummary.Summary {
        val snapshot = MumbleAclManager.AclSnapshot(
            channelId = 1,
            inheritAcls = true,
            groups = emptyList(),
            rules = original
        )
        val draft = MumbleAclDraft(snapshot)
        original.indices.reversed().forEach { draft.removeRule(it) }
        current.forEach { draft.addRule(it) }
        return MumbleAclChangeSummary.summarize(draft)
    }

    @Test
    fun unchangedRules() {
        val a = rule("a")
        val result = summary(listOf(a), listOf(a))
        assertFalse(result.hasChanges())
    }

    @Test
    fun pureReordering() {
        val a = rule("a")
        val b = rule("b")
        val result = summary(listOf(a, b), listOf(b, a))
        assertTrue(result.rulesReordered)
        assertEquals(0, result.addedRules)
        assertEquals(0, result.removedRules)
    }

    @Test
    fun addedRule() {
        val a = rule("a")
        val b = rule("b")
        val result = summary(listOf(a), listOf(a, b))
        assertEquals(1, result.addedRules)
        assertEquals(0, result.removedRules)
        assertFalse(result.rulesReordered)
    }

    @Test
    fun reorderingWithAddition() {
        val a = rule("a")
        val b = rule("b")
        val c = rule("c")
        val result = summary(listOf(a, b), listOf(b, c, a))
        assertEquals(1, result.addedRules)
        assertEquals(0, result.removedRules)
        assertTrue(result.rulesReordered)
    }

    @Test
    fun duplicateRules() {
        val a = rule("a")
        val b = rule("b")
        val result = summary(listOf(a, a, b), listOf(a, b, a))
        assertEquals(0, result.addedRules)
        assertEquals(0, result.removedRules)
        assertTrue(result.rulesReordered)
    }

    @Test
    fun removedRule() {
        val a = rule("a")
        val b = rule("b")
        val result = summary(listOf(a, b), listOf(b))
        assertEquals(0, result.addedRules)
        assertEquals(1, result.removedRules)
        assertFalse(result.rulesReordered)
    }

    @Test
    fun editedRuleIsReportedAsUnmatchedOccurrences() {
        val original = rule("a")
        val edited = original.toBuilder().setGrant(2).build()
        val result = summary(listOf(original), listOf(edited))
        assertEquals(1, result.addedRules)
        assertEquals(1, result.removedRules)
        assertEquals(0, result.modifiedRules)
        assertFalse(result.rulesReordered)
    }

    @Test
    fun removalWithReordering() {
        val a = rule("a")
        val b = rule("b")
        val c = rule("c")
        val result = summary(listOf(a, b, c), listOf(c, a))
        assertEquals(0, result.addedRules)
        assertEquals(1, result.removedRules)
        assertTrue(result.rulesReordered)
    }
}
