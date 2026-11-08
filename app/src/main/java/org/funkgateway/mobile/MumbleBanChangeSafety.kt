package org.funkgateway.mobile

/**
 * Reine Sicherheitsprüfung für vorbereitete Mumble-Bannlistenänderungen.
 *
 * Diese Prüfung führt keine Netzwerkoperationen aus.
 * Sie garantiert keine atomare Änderung auf dem Mumble-Server.
 */
internal object MumbleBanChangeSafety {

    fun check(
        connected: Boolean,
        expectedGeneration: Long,
        currentGeneration: Long,
        expectedServer: Pair<String, Int>,
        currentServer: Pair<String, Int>?,
        hasBanPermission: Boolean,
        hasValidBanList: Boolean,
        expectedListVersion: Long? = null,
        currentListVersion: Long? = null
    ): String? {
        if (!connected) {
            return "Keine aktive Mumble-Verbindung"
        }

        if (currentGeneration != expectedGeneration) {
            return "Mumble-Verbindung hat sich geändert"
        }

        if (currentServer != expectedServer) {
            return "Mumble-Server hat sich geändert"
        }

        if (!hasBanPermission) {
            return "Keine bestätigte Bann-Berechtigung"
        }

        if (!hasValidBanList) {
            return "Keine vollständig empfangene, gültige Bannliste"
        }

        if (
            expectedListVersion == null ||
            currentListVersion == null ||
            expectedListVersion != currentListVersion
        ) {
            return "Bannlistenstand fehlt oder hat sich geändert"
        }

        return null
    }
}
