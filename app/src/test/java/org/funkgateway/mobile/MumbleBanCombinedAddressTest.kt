package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleBanCombinedAddressTest {

    @Test
    fun detectsIpv4AndIpv6() {
        val cases = mapOf(
            "192.168.1.25" to 128,
            "10.0.0.0/8" to 104,
            "2001:db8::1" to 128,
            "2001:db8::/64" to 64,
            "::1" to 128
        )

        for ((input, expectedMask) in cases) {
            val result = MumbleBanList.parseBanAddress(input)
            assertNotNull(input, result)
            assertEquals(input, expectedMask, result!!.mask)
            assertEquals(input, 16, result.address.size)
        }
    }

    @Test
    fun rejectsInvalidAddresses() {
        val cases = listOf(
            "",
            "beispiel.de",
            "192.168.1",
            "256.168.1.25",
            "192.168.1.25/33",
            "2001:db8::xyz",
            "2001:db8::1/129",
            "192.168.1.25:1234"
        )

        for (input in cases) {
            assertNull(input, MumbleBanList.parseBanAddress(input))
        }
    }

    @Test
    fun trimsWhitespace() {
        val result = MumbleBanList.parseBanAddress(
            "  192.168.1.25/32  "
        )

        assertNotNull(result)
        assertEquals(128, result!!.mask)
    }
}
