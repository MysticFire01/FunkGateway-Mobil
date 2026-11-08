package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test
import se.lublin.humla.protobuf.Mumble
import com.google.protobuf.ByteString

class MumblePreparedBanChangeTest {

    private val context = MumbleBanChangeContext(
        connectionGeneration = 12L,
        server = Pair("mumble.example.org", 64738),
        banListVersion = 7L
    )

    private fun ban(number: Int): Mumble.BanList.BanEntry =
        Mumble.BanList.BanEntry.newBuilder()
            .setAddress(
                ByteString.copyFrom(
                    ByteArray(16).also { it[15] = number.toByte() }
                )
            )
            .setMask(128)
            .setName("Test-Sperre $number")
            .setReason("Automatischer Test")
            .build()

    @Test
    fun additionPreservesAllFiveExistingBans() {
        val original = (1..5).map(::ban)
        val additional = ban(6)

        val change = MumblePreparedBanChange(
            context = context,
            operation = MumblePreparedBanChange.Operation.ADD,
            originalBans = original,
            preparedBans = MumbleBanList.prepareBanAddition(
                original,
                additional
            )
        )

        assertEquals(5, change.originalBans.size)
        assertEquals(6, change.preparedBans.size)
        assertEquals(original, change.preparedBans.take(5))
        assertEquals(additional, change.preparedBans.last())
        assertEquals(context, change.context)
    }

    @Test
    fun removalPreservesAllOtherBans() {
        val original = (1..5).map(::ban)
        val selected = original[2]

        val change = MumblePreparedBanChange(
            context = context,
            operation = MumblePreparedBanChange.Operation.REMOVE,
            originalBans = original,
            preparedBans = MumbleBanList.prepareBanRemoval(
                original,
                selected
            )
        )

        assertEquals(5, change.originalBans.size)
        assertEquals(4, change.preparedBans.size)
        assertEquals(
            original.filterIndexed { index, _ -> index != 2 },
            change.preparedBans
        )
        assertEquals(context, change.context)
    }

    @Test
    fun removalOfUnknownBanIsRejected() {
        val original = (1..5).map(::ban)

        try {
            MumbleBanList.prepareBanRemoval(original, ban(9))
            fail("Unbekannte Sperre darf nicht entfernt werden")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}
