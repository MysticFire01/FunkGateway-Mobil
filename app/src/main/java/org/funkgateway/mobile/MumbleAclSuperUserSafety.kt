package org.funkgateway.mobile

/**
 * Prüft die Identität des Mumble-SuperUsers.
 *
 * Keine Netzwerkkommunikation und keine eigenständige
 * ACL-Schreibfreigabe.
 */
object MumbleAclSuperUserSafety {

    data class Context(
        val connected: Boolean,
        val connectionGeneration: Long,
        val expectedGeneration: Long,
        val ownSessionId: Int,
        val reportedSessionId: Int,
        val reportedUserId: Int
    )

    fun check(context: Context): Boolean =
        context.connected &&
        context.connectionGeneration >= 0L &&
        context.connectionGeneration == context.expectedGeneration &&
        context.ownSessionId >= 0 &&
        context.reportedSessionId == context.ownSessionId &&
        context.reportedUserId == 0
}
