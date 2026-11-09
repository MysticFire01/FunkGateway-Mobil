package org.funkgateway.mobile

/**
 * Sicherheitsprüfungen vor dem späteren Speichern
 * von Mumble-ACL-Änderungen.
 *
 * Keine Netzwerkkommunikation und keine Schreibfunktion.
 */
object MumbleAclPreparation {

    /**
     * Prüft lokale Gruppen vor einer späteren Schreiboperation.
     * Geerbte Gruppen werden nicht als lokale Definitionen gezählt.
     */
    fun validateLocalGroups(draft: MumbleAclDraft): List<String> {
        val errors = mutableListOf<String>()
        val groups = localGroups(draft)
        val names = mutableSetOf<String>()

        groups.forEach { group ->
            val name = group.name

            if (name.isBlank()) {
                errors.add("Leerer Gruppenname")
            } else if (!names.add(name)) {
                errors.add("Doppelte lokale Gruppe: $name")
            }

            if (group.addList.any { it < 0 } ||
                group.removeList.any { it < 0 }) {
                errors.add("Ungültige Benutzer-ID in Gruppe: $name")
            }

            if (group.addList.toSet()
                    .intersect(group.removeList.toSet())
                    .isNotEmpty()) {
                errors.add("Mitglied gleichzeitig hinzugefügt und entfernt: $name")
            }
        }

        return errors
    }


    /**
     * Nur lokale Gruppendefinitionen übernehmen.
     * Vom Server berechnete Mitglieder nicht zurückschreiben.
     */
    fun localGroups(draft: MumbleAclDraft) =
        draft.getGroups()
            .filterNot { it.inherited }
            .map {
                it.toBuilder()
                    .clearInheritedMembers()
                    .build()
            }

    /**
     * Nur lokale Berechtigungsregeln übernehmen.
     * Grant/Deny-Bitmasken und Reihenfolge bleiben erhalten.
     */
    fun localRules(draft: MumbleAclDraft) =
        draft.getRules().filterNot { it.inherited }


    fun isServerStateUnchanged(
        original: MumbleAclManager.AclSnapshot,
        current: MumbleAclManager.AclSnapshot
    ): Boolean {
        return original.channelId == current.channelId &&
            original.inheritAcls == current.inheritAcls &&
            original.groups == current.groups &&
            original.rules == current.rules
    }

    fun canPrepareChanges(
        draft: MumbleAclDraft,
        current: MumbleAclManager.AclSnapshot
    ): Boolean {
        return draft.hasChanges() &&
            isServerStateUnchanged(draft.original, current)
    }

    /**
     * Erstellt eine ACL-Schreibnachricht ausschließlich lokal.
     *
     * Keine Netzwerkkommunikation.
     * Der Aufrufer muss vor einer tatsächlichen Übertragung
     * einen frischen Server-Snapshot abrufen.
     */

    /**
     * Prüft ausschließlich lokale ACL-Regeln.
     * Berechtigungsbits und Regelreihenfolge bleiben unverändert.
     */
    fun validateLocalRules(draft: MumbleAclDraft): List<String> {
        val errors = mutableListOf<String>()

        localRules(draft).forEachIndexed { index, rule ->
            val number = index + 1

            if (rule.hasUserId() && rule.hasGroup()) {
                errors.add("Regel $number: Benutzer und Gruppe gleichzeitig gesetzt")
            }

            if (rule.hasUserId() && rule.userId < 0) {
                errors.add("Regel $number: Ungültige Benutzer-ID")
            }

            if (rule.hasGroup() && rule.group.isBlank()) {
                errors.add("Regel $number: Leerer Gruppenname")
            }
        }

        return errors
    }

    fun prepareWriteMessage(
        draft: MumbleAclDraft,
        current: MumbleAclManager.AclSnapshot
    ): se.lublin.humla.protobuf.Mumble.ACL? {
        if (!canPrepareChanges(draft, current)) return null
        if (validateLocalGroups(draft).isNotEmpty()) return null
        if (validateLocalRules(draft).isNotEmpty()) return null

        return se.lublin.humla.protobuf.Mumble.ACL.newBuilder()
            .setChannelId(draft.channelId)
            .setQuery(false)
            .setInheritAcls(draft.inheritAcls)
            .addAllGroups(localGroups(draft))
            .addAllAcls(localRules(draft))
            .build()
    }

}
