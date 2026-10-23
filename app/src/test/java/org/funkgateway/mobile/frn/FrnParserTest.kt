package org.funkgateway.mobile.frn

import org.junit.Assert.*
import org.junit.Test

class FrnParserTest {

    @Test
    fun clientListSplitAcrossPackets() {
        val parser = FrnParser()

        assertTrue(parser.feed(byteArrayOf(3, 0)).isEmpty())
        assertTrue(parser.feed(byteArrayOf(1, '1'.code.toByte(), 13)).isEmpty())

        val result = parser.feed(
            byteArrayOf(10) +
                "<ON>Teststation</ON>\r\n".toByteArray(Charsets.US_ASCII)
        )

        assertEquals(1, result.size)
        val list = result.single() as FrnEvent.ClientList
        assertEquals(1, list.activeIndex)
        assertEquals(listOf("<ON>Teststation</ON>"), list.entries)
    }

    @Test
    fun audioSplitAcrossPackets() {
        val parser = FrnParser()
        val packet = byteArrayOf(2, 0, 7) + ByteArray(325) { 42 }

        assertTrue(parser.feed(packet.copyOfRange(0, 100)).isEmpty())

        val result = parser.feed(packet.copyOfRange(100, packet.size))
        val audio = result.single() as FrnEvent.Audio

        assertEquals(7, audio.index)
        assertEquals(325, audio.packet.size)
        assertTrue(audio.packet.all { it == 42.toByte() })
    }

    @Test
    fun crlfSplitBeforeNextCommand() {
        val parser = FrnParser()

        val first = byteArrayOf(4) +
            "1\r".toByteArray(Charsets.US_ASCII)

        assertTrue(parser.feed(first).isEmpty())

        val second = byteArrayOf(10) +
            "Nachricht\r\n".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0)

        val events = parser.feed(second)

        assertEquals(2, events.size)

        val list = events[0] as FrnEvent.ServerList
        assertEquals(4, list.type)
        assertEquals(listOf("Nachricht"), list.entries)

        assertTrue(events[1] is FrnEvent.Idle)
    }

}
