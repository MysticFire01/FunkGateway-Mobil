package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleBanIpv6Test {

    @Test
    fun validIpv6Addresses() {
        val cases = mapOf(
            "2001:db8::1" to 128,
            "2001:db8::/64" to 64,
            "::1" to 128,
            "::" to 128,
            "fe80::1234/10" to 10,
            "2001:db8:0:0:0:0:0:1" to 128,
            "::/0" to 0
        )

        for ((input, expectedMask) in cases) {
            val result = MumbleBanList.parseIpv6BanAddress(input)
            assertNotNull(input, result)
            assertEquals(input, expectedMask, result!!.mask)
            assertEquals(input, 16, result.address.size)
        }
    }

    @Test
    fun invalidIpv6Addresses() {
        val cases = listOf(
            "",
            "beispiel.de",
            "2001:db8::xyz",
            "2001:db8::1/129",
            "2001:db8::1/-1",
            "2001:::1",
            "2001::db8::1",
            "2001:db8:0:0:0:0:0",
            "2001:db8::1/abc",
            "2001:db8::1/64/32",
            "fe80::1%eth0",
            "::ffff:192.168.1.1"
        )

        for (input in cases) {
            assertNull(input, MumbleBanList.parseIpv6BanAddress(input))
        }
    }

    @Test
    fun ipv6BytesAreCorrect() {
        val result = MumbleBanList.parseIpv6BanAddress("2001:db8::1")
        assertNotNull(result)

        val bytes = result!!.address
        assertEquals(0x20, bytes[0].toInt() and 0xff)
        assertEquals(0x01, bytes[1].toInt() and 0xff)
        assertEquals(0x0d, bytes[2].toInt() and 0xff)
        assertEquals(0xb8, bytes[3].toInt() and 0xff)
        assertTrue(bytes.slice(4..14).all { it == 0.toByte() })
        assertEquals(1, bytes[15].toInt() and 0xff)
    }

    @Test
    fun compressedZeroAddressIsCorrect() {
        val result = MumbleBanList.parseIpv6BanAddress("::/0")
        assertNotNull(result)
        assertEquals(0, result!!.mask)
        assertTrue(result.address.all { it == 0.toByte() })
    }
}
