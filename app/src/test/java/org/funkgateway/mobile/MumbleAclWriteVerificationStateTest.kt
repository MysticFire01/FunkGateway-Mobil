package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleAclWriteVerificationStateTest {

    private fun context(
        generation: Long = 5L,
        channelId: Int = 9,
        receivedVersion: Long = 4L
    ) = MumbleAclWriteVerification.Context(
        connected = true,
        expectedGeneration = generation,
        currentGeneration = generation,
        expectedChannelId = channelId,
        receivedChannelId = channelId,
        versionBeforeWrite = 3L,
        receivedVersion = receivedVersion
    )

    @Test
    fun armedVerificationRejectsResponseUntilActivated() {
        val state = MumbleAclWriteVerificationState()
        val request = state.start(5L, 9, 3L)

        assertEquals(
            MumbleAclWriteVerificationState.Status.ARMED,
            state.snapshot().status
        )
        assertFalse(state.observe(request.verificationId, context(), true))
        assertFalse(state.timeout(request.verificationId))

        assertTrue(state.activate(request.verificationId))
        assertFalse(state.activate(request.verificationId))

        assertTrue(state.observe(request.verificationId, context(), true))
        assertEquals(
            MumbleAclWriteVerificationState.Status.CONTENT_MATCHED,
            state.snapshot().status
        )
    }

    @Test
    fun matchingResponseCompletesVerification() {
        val state = MumbleAclWriteVerificationState()
        val request = state.start(5L, 9, 3L)
        assertTrue(state.activate(request.verificationId))

        assertTrue(state.observe(request.verificationId, context(), true))
        assertEquals(
            MumbleAclWriteVerificationState.Status.CONTENT_MATCHED,
            state.snapshot().status
        )
        assertFalse(state.observe(request.verificationId, context(), true))
    }

    @Test
    fun mismatchingContentIsNotSuccess() {
        val state = MumbleAclWriteVerificationState()
        val request = state.start(5L, 9, 3L)
        assertTrue(state.activate(request.verificationId))

        assertTrue(state.observe(request.verificationId, context(), false))
        assertEquals(
            MumbleAclWriteVerificationState.Status.CONTENT_MISMATCH,
            state.snapshot().status
        )
    }

    @Test
    fun oldVersionAndWrongChannelAreRejected() {
        val state = MumbleAclWriteVerificationState()
        val request = state.start(5L, 9, 3L)
        assertTrue(state.activate(request.verificationId))

        assertFalse(
            state.observe(
                request.verificationId,
                context(receivedVersion = 3L),
                true
            )
        )
        assertFalse(
            state.observe(
                request.verificationId,
                context(channelId = 10),
                true
            )
        )
    }

    @Test
    fun timeoutPreventsLateSuccess() {
        val state = MumbleAclWriteVerificationState()
        val request = state.start(5L, 9, 3L)
        assertTrue(state.activate(request.verificationId))

        assertTrue(state.timeout(request.verificationId))
        assertFalse(state.observe(request.verificationId, context(), true))
        assertEquals(
            MumbleAclWriteVerificationState.Status.TIMED_OUT,
            state.snapshot().status
        )
    }

    @Test
    fun resetInvalidatesPendingVerification() {
        val state = MumbleAclWriteVerificationState()
        val request = state.start(5L, 9, 3L)
        assertTrue(state.activate(request.verificationId))

        state.reset()

        assertFalse(state.observe(request.verificationId, context(), true))
        assertEquals(
            MumbleAclWriteVerificationState.Status.IDLE,
            state.snapshot().status
        )
    }

    @Test
    fun newerVerificationRejectsOlderId() {
        val state = MumbleAclWriteVerificationState()
        val old = state.start(5L, 9, 3L)
        val current = state.start(5L, 9, 3L)
        assertTrue(state.activate(current.verificationId))

        assertFalse(state.observe(old.verificationId, context(), true))
        assertTrue(state.observe(current.verificationId, context(), true))
    }

    @org.junit.Test
    fun readbackFailurePreventsLaterConfirmation() {
        val state = MumbleAclWriteVerificationState()
        val started = state.start(
            generation = 5L,
            channelId = 9,
            versionBeforeWrite = 12L
        )

        org.junit.Assert.assertTrue(
            state.activate(started.verificationId)
        )
        org.junit.Assert.assertTrue(
            state.readbackFailed(started.verificationId)
        )
        org.junit.Assert.assertEquals(
            MumbleAclWriteVerificationState.Status.READBACK_FAILED,
            state.snapshot().status
        )

        val context = MumbleAclWriteVerification.Context(
            connected = true,
            expectedGeneration = 5L,
            currentGeneration = 5L,
            expectedChannelId = 9,
            receivedChannelId = 9,
            versionBeforeWrite = 12L,
            receivedVersion = 13L
        )

        org.junit.Assert.assertFalse(
            state.observe(
                started.verificationId,
                context,
                contentMatches = true
            )
        )
        org.junit.Assert.assertFalse(
            state.timeout(started.verificationId)
        )
        org.junit.Assert.assertEquals(
            MumbleAclWriteVerificationState.Status.READBACK_FAILED,
            state.snapshot().status
        )
    }

}
