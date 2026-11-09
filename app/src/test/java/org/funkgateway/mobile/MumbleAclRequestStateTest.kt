package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleAclRequestStateTest {

    @Test
    fun startsIdle() {
        val state = MumbleAclRequestState()
        assertEquals(
            MumbleAclRequestState.Status.IDLE,
            state.snapshot().status
        )
    }

    @Test
    fun matchingMessageIsObserved() {
        val state = MumbleAclRequestState()
        state.start(7)
        assertTrue(state.observe(7))
        assertEquals(
            MumbleAclRequestState.Status.MESSAGE_OBSERVED,
            state.snapshot().status
        )
    }

    @Test
    fun otherChannelDoesNotCompleteRequest() {
        val state = MumbleAclRequestState()
        state.start(7)
        assertFalse(state.observe(8))
        assertEquals(
            MumbleAclRequestState.Status.PENDING,
            state.snapshot().status
        )
    }

    @Test
    fun timeoutMarksPendingRequest() {
        val state = MumbleAclRequestState()
        val request = state.start(7)
        assertTrue(state.timeout(request.requestId))
        assertEquals(
            MumbleAclRequestState.Status.TIMED_OUT,
            state.snapshot().status
        )
    }

    @Test
    fun oldTimeoutCannotCancelNewRequest() {
        val state = MumbleAclRequestState()
        val old = state.start(7)
        val newer = state.start(7)
        assertFalse(state.timeout(old.requestId))
        assertEquals(newer, state.snapshot())
    }

    @Test
    fun resetInvalidatesPendingRequest() {
        val state = MumbleAclRequestState()
        val request = state.start(7)
        state.reset()
        assertFalse(state.timeout(request.requestId))
        assertFalse(state.observe(7))
    }

    @Test
    fun retryAfterTimeoutIsPossible() {
        val state = MumbleAclRequestState()
        val first = state.start(7)
        assertTrue(state.timeout(first.requestId))
        val retry = state.start(7)
        assertTrue(retry.requestId > first.requestId)
        assertEquals(
            MumbleAclRequestState.Status.PENDING,
            state.snapshot().status
        )
    }

    @Test
    fun observedMessageCannotLaterTimeOut() {
        val state = MumbleAclRequestState()
        val request = state.start(7)
        assertTrue(state.observe(7))
        assertFalse(state.timeout(request.requestId))
    }
}
