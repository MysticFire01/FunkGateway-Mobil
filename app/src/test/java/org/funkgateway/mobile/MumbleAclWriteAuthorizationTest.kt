package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleAclWriteAuthorizationTest {

    private val token = MumbleAclWriteGuard.Token(
        connectionGeneration = 5L,
        channelId = 9,
        aclReceiveVersion = 3L,
        draftFingerprint = "test-fingerprint"
    )

    private val context = MumbleAclWriteGuard.Context(
        connected = true,
        connectionGeneration = 5L,
        channelId = 9,
        aclReceiveVersion = 3L,
        draftFingerprint = "test-fingerprint"
    )

    @Test
    fun authorizationCanOnlyBeConsumedOnce() {
        val authorization = MumbleAclWriteAuthorization()
        authorization.authorize(token)

        assertTrue(authorization.consume(token, context))
        assertFalse(authorization.consume(token, context))
    }

    @Test
    fun wrongTokenConsumesPendingAuthorization() {
        val authorization = MumbleAclWriteAuthorization()
        authorization.authorize(token)

        val wrongToken = token.copy(channelId = 10)

        assertFalse(authorization.consume(wrongToken, context))
        assertFalse(authorization.consume(token, context))
    }

    @Test
    fun clearInvalidatesAuthorization() {
        val authorization = MumbleAclWriteAuthorization()
        authorization.authorize(token)
        authorization.clear()

        assertFalse(authorization.consume(token, context))
    }

    @Test
    fun changedConnectionConsumesAuthorization() {
        val authorization = MumbleAclWriteAuthorization()
        authorization.authorize(token)

        assertFalse(
            authorization.consume(
                token,
                context.copy(connectionGeneration = 6L)
            )
        )
        assertFalse(authorization.consume(token, context))
    }
}
