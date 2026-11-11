package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test
import se.lublin.humla.net.Permissions

class MumbleAclWritePermissionSafetyTest {

    private fun context(
        connected: Boolean = true,
        generation: Long = 5L,
        expected: Long = 5L,
        channel: Int = 9,
        responseChannel: Int? = 9,
        hasMask: Boolean = true,
        mask: Int = Permissions.Write,
        flush: Boolean = false
    ) = MumbleAclWritePermissionSafety.Context(
        connected = connected,
        connectionGeneration = generation,
        expectedGeneration = expected,
        requestedChannelId = channel,
        responseChannelId = responseChannel,
        hasPermissionMask = hasMask,
        permissionMask = mask,
        flush = flush
    )

    @Test
    fun validWritePermissionIsReported() {
        assertTrue(
            MumbleAclWritePermissionSafety.check(context()).permissionReported
        )
    }

    @Test
    fun missingWritePermissionIsRejected() {
        assertFalse(
            MumbleAclWritePermissionSafety.check(
                context(mask = Permissions.Speak)
            ).permissionReported
        )
    }

    @Test
    fun wrongChannelIsRejected() {
        assertFalse(
            MumbleAclWritePermissionSafety.check(
                context(responseChannel = 107)
            ).permissionReported
        )
    }

    @Test
    fun oldConnectionGenerationIsRejected() {
        assertFalse(
            MumbleAclWritePermissionSafety.check(
                context(generation = 6L)
            ).permissionReported
        )
    }

    @Test
    fun disconnectedSessionIsRejected() {
        assertFalse(
            MumbleAclWritePermissionSafety.check(
                context(connected = false)
            ).permissionReported
        )
    }

    @Test
    fun flushIsRejected() {
        assertFalse(
            MumbleAclWritePermissionSafety.check(
                context(flush = true)
            ).permissionReported
        )
    }

    @Test
    fun missingPermissionMaskIsRejected() {
        assertFalse(
            MumbleAclWritePermissionSafety.check(
                context(hasMask = false)
            ).permissionReported
        )
    }
}
