package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleBanAddressTest {

    @Test
    fun validIpv4Addresses() {
        val cases = mapOf(
            "192.168.1.25" to 128,
            "192.168.1.0/24" to 120,
            "10.0.0.1/8" to 104,
            "0.0.0.0/0" to 96,
            "255.255.255.255/32" to 128
        )

        for ((input, expectedMask) in cases) {
            val result = MumbleBanList.parseIpv4BanAddress(input)
            assertNotNull(input, result)
            assertEquals(input, expectedMask, result!!.mask)
            assertEquals(input, 16, result.address.size)
            assertEquals(input, 0xff.toByte(), result.address[10])
            assertEquals(input, 0xff.toByte(), result.address[11])
        }
    }

    @Test
    fun invalidIpv4Addresses() {
        val cases = listOf(
            "",
            "192.168.1",
            "256.168.1.25",
            "192.168.1.25/33",
            "192.168.1.25/-1",
            "192.168.1.25/abc",
            "192.168.001.25",
            "beispiel.de",
            "192.168.1.25/24/12"
        )

        for (input in cases) {
            assertNull(input, MumbleBanList.parseIpv4BanAddress(input))
        }
    }

    @Test
    fun ipv4MappedBytesAreCorrect() {
        val result = MumbleBanList.parseIpv4BanAddress("192.168.1.25")
        assertNotNull(result)

        val bytes = result!!.address
        assertTrue(bytes.take(10).all { it == 0.toByte() })

        assertEquals(192, bytes[12].toInt() and 0xff)
        assertEquals(168, bytes[13].toInt() and 0xff)
        assertEquals(1, bytes[14].toInt() and 0xff)
        assertEquals(25, bytes[15].toInt() and 0xff)
    }
}
