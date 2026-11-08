package org.funkgateway.mobile

import com.google.protobuf.ByteString
import org.junit.Assert.*
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

class MumbleBanPreparationTest {

    private fun ban(number: Int): Mumble.BanList.BanEntry {
        val address = ByteArray(16)
        address[10] = 0xff.toByte()
        address[11] = 0xff.toByte()
        address[12] = 192.toByte()
        address[13] = 0
        address[14] = 2
        address[15] = number.toByte()

        return Mumble.BanList.BanEntry.newBuilder()
            .setName("Test-$number")
            .setReason("Testgrund-$number")
            .setAddress(ByteString.copyFrom(address))
            .setMask(128)
            .setHash("Testhash-$number")
            .setDuration(3600)
            .build()
    }

    @Test
    fun additionPreservesExistingBans() {
        val existing = (1..5).map(::ban)
        val result = MumbleBanList.prepareBanAddition(existing, ban(6))

        assertEquals(6, result.size)
        assertEquals(existing, result.take(5))
        assertEquals(ban(6), result.last())
        assertEquals(5, existing.size)
    }

    @Test
    fun removalPreservesOtherBans() {
        val existing = (1..5).map(::ban)
        val result = MumbleBanList.prepareBanRemoval(existing, existing[2])

        assertEquals(4, result.size)
        assertEquals(listOf(existing[0], existing[1], existing[3], existing[4]), result)
        assertEquals(5, existing.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownBanCannotBeRemoved() {
        MumbleBanList.prepareBanRemoval((1..5).map(::ban), ban(9))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateBanCannotBeRemovedAmbiguously() {
        val existing = listOf(ban(1), ban(2), ban(2), ban(3), ban(4))
        MumbleBanList.prepareBanRemoval(existing, ban(2))
    }
}
