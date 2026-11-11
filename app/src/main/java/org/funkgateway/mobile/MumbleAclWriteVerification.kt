package org.funkgateway.mobile

/**
 * Grundprüfung einer ACL-Antwort nach einem Schreibversuch.
 *
 * Eine passende Antwort ist noch kein Beweis, dass der Server
 * die gewünschten Änderungen vollständig übernommen hat.
 */
object MumbleAclWriteVerification {

    // Fester Sollzustand für eine einzelne ACL-Nachkontrolle.
    // Die Protobuf-Nachrichten sind unveränderlich.
    data class ExpectedContent(
        val channelId: Int,
        val inheritAcls: Boolean,
        val groups: List<se.lublin.humla.protobuf.Mumble.ACL.ChanGroup>,
        val rules: List<se.lublin.humla.protobuf.Mumble.ACL.ChanACL>
    )

    fun captureExpectedContent(draft: MumbleAclDraft): ExpectedContent =
        ExpectedContent(
            channelId = draft.channelId,
            inheritAcls = draft.inheritAcls,
            groups = MumbleAclPreparation.localGroups(draft).toList(),
            rules = MumbleAclPreparation.localRules(draft).toList()
        )

    data class Context(
        val connected: Boolean,
        val expectedGeneration: Long,
        val currentGeneration: Long,
        val expectedChannelId: Int,
        val receivedChannelId: Int,
        val versionBeforeWrite: Long,
        val receivedVersion: Long
    )


    /**
     * Vergleicht den gewünschten lokalen ACL-Zustand
     * mit einer erneut empfangenen Server-ACL.
     *
     * Diese Funktion allein bestätigt keinen Schreibvorgang.
     * Verbindung, Empfangsversion und Anfragezuordnung
     * müssen zusätzlich geprüft werden.
     */
    fun matchesExpectedContent(
        draft: MumbleAclDraft,
        received: MumbleAclManager.AclSnapshot
    ): Boolean =
        matchesExpectedContent(captureExpectedContent(draft), received)

    fun matchesExpectedContent(
        expected: ExpectedContent,
        received: MumbleAclManager.AclSnapshot
    ): Boolean {
        if (expected.channelId != received.channelId ||
            expected.inheritAcls != received.inheritAcls
        ) return false

        val receivedGroups = received.groups
            .filterNot { it.inherited }
            .map {
                it.toBuilder()
                    .clearInheritedMembers()
                    .build()
            }

        val receivedRules = received.rules
            .filterNot { it.inherited }

        // Mitgliederlisten sind Mengen von Benutzer-IDs.
        // Der Server darf ihre Reihenfolge verändern.
        // Alle übrigen Gruppeneigenschaften bleiben erhalten.
        fun normalizeGroup(
            group: se.lublin.humla.protobuf.Mumble.ACL.ChanGroup
        ): se.lublin.humla.protobuf.Mumble.ACL.ChanGroup =
            group.toBuilder()
                .clearAdd()
                .addAllAdd(group.addList.sorted())
                .clearRemove()
                .addAllRemove(group.removeList.sorted())
                .build()

        val expectedGroups = expected.groups.map(::normalizeGroup)
        val actualGroups = receivedGroups.map(::normalizeGroup)

        // ACL-Regeln einschließlich ihrer Reihenfolge exakt prüfen.
        return expectedGroups == actualGroups &&
            expected.rules == receivedRules
    }

    fun isCandidateResponse(context: Context): Boolean =
        context.connected &&
            context.expectedGeneration >= 0L &&
            context.expectedGeneration == context.currentGeneration &&
            context.expectedChannelId >= 0 &&
            context.expectedChannelId == context.receivedChannelId &&
            context.versionBeforeWrite > 0L &&
            context.receivedVersion > context.versionBeforeWrite
}
