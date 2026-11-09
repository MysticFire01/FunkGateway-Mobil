package org.funkgateway.mobile

/**
 * Sicherheitsprüfung für geplante Mumble-ACL-Änderungen.
 *
 * Diese Klasse sendet keine Nachrichten an den Server
 * und erteilt ausdrücklich keine Schreibfreigabe.
 */
object MumbleAclChangeSafety {

    data class Context(
        val connected: Boolean,
        val connectionGeneration: Long,
        val expectedGeneration: Long,
        val selectedChannelId: Int?,
        val requestedChannelId: Int,
        val serverSnapshot: MumbleAclManager.AclSnapshot?,
        val draft: MumbleAclDraft?
    )

    data class Result(
        val validForReview: Boolean,
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

        if (
            context.requestedChannelId < 0 ||
            context.selectedChannelId != context.requestedChannelId
        ) {
            reasons.add("Der ausgewählte Mumble-Kanal stimmt nicht überein.")
        }

        val draft = context.draft
        val snapshot = context.serverSnapshot

        if (draft == null || snapshot == null) {
            reasons.add("ACL-Entwurf oder Serverdaten fehlen.")
        } else {
            if (
                draft.channelId != context.requestedChannelId ||
                snapshot.channelId != context.requestedChannelId
            ) {
                reasons.add("ACL-Daten gehören zu einem anderen Kanal.")
            }

            if (!draft.hasChanges()) {
                reasons.add("Es liegen keine lokalen Änderungen vor.")
            }

            if (!MumbleAclPreparation.isServerStateUnchanged(
                    draft.original, snapshot
                )
            ) {
                reasons.add("Die Server-ACL weicht vom ursprünglichen Stand ab.")
            }

            reasons.addAll(MumbleAclPreparation.validateLocalGroups(draft))
            reasons.addAll(MumbleAclPreparation.validateLocalRules(draft))
        }

        return Result(
            validForReview = reasons.isEmpty(),
            reasons = reasons
        )
    }
}
