package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleAclWriteGuardTest {

    private val token = MumbleAclWriteGuard.Token(
        connectionGeneration = 12L,
        channelId = 107,
        aclReceiveVersion = 5L,
        draftFingerprint = "draft-A"
    )

    private val valid = MumbleAclWriteGuard.Context(
        connected = true,
        connectionGeneration = 12L,
        channelId = 107,
        aclReceiveVersion = 5L,
        draftFingerprint = "draft-A"
    )

    @Test
    fun matchingStatePasses() {
        assertTrue(MumbleAclWriteGuard.check(token, valid))
    }

    @Test
    fun disconnectedFails() {
        assertFalse(MumbleAclWriteGuard.check(token, valid.copy(connected = false)))
    }

    @Test
    fun changedConnectionFails() {
        assertFalse(MumbleAclWriteGuard.check(token, valid.copy(connectionGeneration = 13L)))
    }

    @Test
    fun changedChannelFails() {
        assertFalse(MumbleAclWriteGuard.check(token, valid.copy(channelId = 9)))
    }

    @Test
    fun changedAclVersionFails() {
        assertFalse(MumbleAclWriteGuard.check(token, valid.copy(aclReceiveVersion = 6L)))
    }

    @Test
    fun changedDraftFails() {
        assertFalse(MumbleAclWriteGuard.check(token, valid.copy(draftFingerprint = "draft-B")))
    }

    @Test
    fun emptyFingerprintFails() {
        assertFalse(MumbleAclWriteGuard.check(token.copy(draftFingerprint = ""), valid.copy(draftFingerprint = "")))
    }

    @Test
    fun invalidAclVersionFails() {
        assertFalse(MumbleAclWriteGuard.check(token.copy(aclReceiveVersion = 0L), valid.copy(aclReceiveVersion = 0L)))
    }
}
