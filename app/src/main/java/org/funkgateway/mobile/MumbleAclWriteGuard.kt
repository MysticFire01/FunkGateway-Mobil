package org.funkgateway.mobile

/**
 * Bindet eine ausdrückliche ACL-Speicherbestätigung an
 * Verbindung, Kanal, Empfangsversion und Entwurfsstand.
 *
 * Diese Prüfung allein berechtigt nicht zum Senden.
 */
object MumbleAclWriteGuard {

    /**
     * Fingerprint der tatsächlich vorbereiteten ACL-Schreibnachricht.
     * Gibt null zurück, wenn der Entwurf nicht sicher vorbereitet werden kann.
     */
    fun fingerprint(
        draft: MumbleAclDraft,
        snapshot: MumbleAclManager.AclSnapshot
    ): String? {
        val message = MumbleAclPreparation.prepareWriteMessage(
            draft, snapshot
        ) ?: return null

        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(message.toByteArray())

        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    data class Token(
        val connectionGeneration: Long,
        val channelId: Int,
        val aclReceiveVersion: Long,
        val draftFingerprint: String
    )

    data class Context(
        val connected: Boolean,
        val connectionGeneration: Long,
        val channelId: Int,
        val aclReceiveVersion: Long,
        val draftFingerprint: String
    )

    fun check(token: Token, context: Context): Boolean {
        return context.connected &&
            token.connectionGeneration >= 0L &&
            token.connectionGeneration == context.connectionGeneration &&
            token.channelId >= 0 &&
            token.channelId == context.channelId &&
            token.aclReceiveVersion > 0L &&
            token.aclReceiveVersion == context.aclReceiveVersion &&
            token.draftFingerprint.isNotEmpty() &&
            token.draftFingerprint == context.draftFingerprint
    }
}
