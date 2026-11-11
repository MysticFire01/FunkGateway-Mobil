package org.funkgateway.mobile

/**
 * Einmalige lokale Bestätigung für einen konkreten ACL-Entwurf.
 *
 * Keine Serverberechtigung und keine Netzwerkkommunikation.
 * Eine Bestätigung wird bei jedem Einlöseversuch verbraucht.
 */
class MumbleAclWriteAuthorization {

    private var pendingToken: MumbleAclWriteGuard.Token? = null

    @Synchronized
    fun authorize(token: MumbleAclWriteGuard.Token) {
        pendingToken = token
    }

    @Synchronized
    fun consume(
        token: MumbleAclWriteGuard.Token,
        context: MumbleAclWriteGuard.Context
    ): Boolean {
        val pending = pendingToken
        pendingToken = null

        return pending != null &&
            pending == token &&
            MumbleAclWriteGuard.check(token, context)
    }

    @Synchronized
    fun clear() {
        pendingToken = null
    }
}
