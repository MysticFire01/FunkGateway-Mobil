package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleAclSuperUserSafetyTest {

    private fun context(
        connected: Boolean = true,
        generation: Long = 5L,
        expected: Long = 5L,
        ownSession: Int = 21,
        reportedSession: Int = 21,
        userId: Int = 0
    ) = MumbleAclSuperUserSafety.Context(
        connected = connected,
        connectionGeneration = generation,
        expectedGeneration = expected,
        ownSessionId = ownSession,
        reportedSessionId = reportedSession,
        reportedUserId = userId
    )

    @Test
    fun validSuperUserAccepted() {
        assertTrue(MumbleAclSuperUserSafety.check(context()))
    }

    @Test
    fun normalAdministratorRejected() {
        assertFalse(MumbleAclSuperUserSafety.check(context(userId = 1)))
    }

    @Test
    fun unregisteredUserRejected() {
        assertFalse(MumbleAclSuperUserSafety.check(context(userId = -1)))
    }

    @Test
    fun disconnectedSessionRejected() {
        assertFalse(MumbleAclSuperUserSafety.check(context(connected = false)))
    }

    @Test
    fun changedConnectionRejected() {
        assertFalse(MumbleAclSuperUserSafety.check(context(generation = 6L)))
    }

    @Test
    fun foreignSessionRejected() {
        assertFalse(MumbleAclSuperUserSafety.check(context(reportedSession = 22)))
    }

    @Test
    fun invalidSessionRejected() {
        assertFalse(MumbleAclSuperUserSafety.check(context(ownSession = -1)))
    }
}
