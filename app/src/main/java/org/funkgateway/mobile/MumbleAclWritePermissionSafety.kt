package org.funkgateway.mobile

/**
 * Lokale Sicherheitsprüfung für Mumble-ACL-Schreibrechte.
 *
 * Keine Netzwerkkommunikation und keine Schreibfreigabe.
 * Eine PermissionQuery besitzt keine eindeutige Anfragekennung.
 */
object MumbleAclWritePermissionSafety {

    data class Context(
        val connected: Boolean,
        val connectionGeneration: Long,
        val expectedGeneration: Long,
        val requestedChannelId: Int,
        val responseChannelId: Int?,
        val hasPermissionMask: Boolean,
        val permissionMask: Int,
        val flush: Boolean
    )

    data class Result(
        val permissionReported: Boolean,
        val reasons: List<String>
    )

    fun check(context: Context): Result {
        val reasons = mutableListOf<String>()

        if (!context.connected) {
            reasons.add("Keine aktive Mumble-Verbindung.")
        }

        if (context.connectionGeneration != context.expectedGeneration) {
            reasons.add("Die Mumble-Verbindung hat sich geändert.")
        }

        if (context.requestedChannelId < 0 ||
            context.responseChannelId != context.requestedChannelId) {
            reasons.add("Berechtigungsantwort gehört zu einem anderen Kanal.")
        }

        if (context.flush) {
            reasons.add("Der Server hat die Berechtigungen zurückgesetzt.")
        }

        if (!context.hasPermissionMask) {
            reasons.add("Die Berechtigungsantwort enthält keine Rechte.")
        } else if (
            context.permissionMask and se.lublin.humla.net.Permissions.Write == 0
        ) {
            reasons.add("Der Server meldet kein ACL-Schreibrecht.")
        }

        return Result(
            permissionReported = reasons.isEmpty(),
            reasons = reasons
        )
    }
}
