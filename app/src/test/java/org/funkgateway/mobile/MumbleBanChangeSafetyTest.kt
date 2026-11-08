package org.funkgateway.mobile

import org.junit.Assert.*
import org.junit.Test

class MumbleBanChangeSafetyTest {

    private val server = Pair("mumble.example.org", 64738)

    private fun check(
        connected: Boolean = true,
        expectedGeneration: Long = 12L,
        currentGeneration: Long = 12L,
        expectedServer: Pair<String, Int> = server,
        currentServer: Pair<String, Int>? = server,
        hasBanPermission: Boolean = true,
        hasValidBanList: Boolean = true,
        expectedListVersion: Long? = 5L,
        currentListVersion: Long? = 5L
    ): String? = MumbleBanChangeSafety.check(
        connected = connected,
        expectedGeneration = expectedGeneration,
        currentGeneration = currentGeneration,
        expectedServer = expectedServer,
        currentServer = currentServer,
        hasBanPermission = hasBanPermission,
        hasValidBanList = hasValidBanList,
        expectedListVersion = expectedListVersion,
        currentListVersion = currentListVersion
    )

    @Test
    fun validConnectionIsAccepted() {
        assertNull(check())
    }

    @Test
    fun disconnectedConnectionIsRejected() {
        assertEquals(
            "Keine aktive Mumble-Verbindung",
            check(connected = false)
        )
    }

    @Test
    fun oldConnectionGenerationIsRejected() {
        assertEquals(
            "Mumble-Verbindung hat sich geändert",
            check(currentGeneration = 13L)
        )
    }

    @Test
    fun differentServerIsRejected() {
        assertEquals(
            "Mumble-Server hat sich geändert",
            check(currentServer = Pair("other.example.org", 64738))
        )
    }

    @Test
    fun missingBanPermissionIsRejected() {
        assertEquals(
            "Keine bestätigte Bann-Berechtigung",
            check(hasBanPermission = false)
        )
    }

    @Test
    fun missingValidBanListIsRejected() {
        assertEquals(
            "Keine vollständig empfangene, gültige Bannliste",
            check(hasValidBanList = false)
        )
    }

    @Test
    fun matchingBanListVersionIsAccepted() {
        assertNull(check(
            expectedListVersion = 7L,
            currentListVersion = 7L
        ))
    }

    @Test
    fun outdatedBanListVersionIsRejected() {
        assertEquals(
            "Bannlistenstand fehlt oder hat sich geändert",
            check(
                expectedListVersion = 7L,
                currentListVersion = 8L
            )
        )
    }

    @Test
    fun missingBanListVersionIsRejected() {
        assertEquals(
            "Bannlistenstand fehlt oder hat sich geändert",
            check(
                expectedListVersion = 7L,
                currentListVersion = null
            )
        )
    }
}
