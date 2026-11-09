package org.funkgateway.mobile

import se.lublin.humla.protobuf.Mumble

/**
 * Hilfsfunktionen für den lokalen Mumble-ACL-Regeleditor.
 *
 * Keine Netzwerkzugriffe oder Serveränderungen.
 */
object MumbleAclRuleEditor {

    enum class TargetType {
        GENERAL,
        USER,
        GROUP
    }

    /**
     * Findet alle ACL-Regeln einer Gruppe in ihrer Originalreihenfolge.
     * Geerbte Regeln bleiben zur Anzeige enthalten.
     */
    fun rulesForGroup(
        rules: List<Mumble.ACL.ChanACL>,
        groupName: String
    ): List<Mumble.ACL.ChanACL> {
        val name = groupName.trim()
        if (name.isEmpty()) return emptyList()

        return rules.filter {
            it.hasGroup() && it.group == name
        }
    }

    fun updatePermissions(
        rule: Mumble.ACL.ChanACL,
        selections: Map<Int, MumbleAclPermissionEditor.State>
    ): Mumble.ACL.ChanACL {
        val editableBits = MumbleAclManager.editablePermissions()
            .map { it.first }
            .toSet()

        require(selections.keys.all { it in editableBits }) {
            "Unbekannte Berechtigungsauswahl."
        }

        var grant = rule.grant
        var deny = rule.deny

        selections.forEach { (bit, state) ->
            val updated = MumbleAclPermissionEditor.update(
                grant, deny, bit, state
            )
            grant = updated.first
            deny = updated.second
        }

        return rule.toBuilder()
            .setGrant(grant)
            .setDeny(deny)
            .build()
    }

    fun targetType(rule: Mumble.ACL.ChanACL): TargetType =
        when {
            rule.hasUserId() -> TargetType.USER
            rule.hasGroup() -> TargetType.GROUP
            else -> TargetType.GENERAL
        }

    fun updateTarget(
        rule: Mumble.ACL.ChanACL,
        type: TargetType,
        userId: Int? = null,
        groupName: String? = null
    ): Mumble.ACL.ChanACL {
        val builder = rule.toBuilder()
            .clearUserId()
            .clearGroup()

        when (type) {
            TargetType.GENERAL -> Unit

            TargetType.USER -> {
                require(userId != null && userId >= 0) {
                    "Eine gültige Benutzer-ID ist erforderlich."
                }
                builder.setUserId(userId)
            }

            TargetType.GROUP -> {
                require(!groupName.isNullOrBlank()) {
                    "Ein Gruppenname ist erforderlich."
                }
                builder.setGroup(groupName.trim())
            }
        }

        return builder.build()
    }
}
