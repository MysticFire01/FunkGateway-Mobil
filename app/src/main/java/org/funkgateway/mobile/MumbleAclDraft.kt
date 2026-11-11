package org.funkgateway.mobile

import se.lublin.humla.protobuf.Mumble

/**
 * Lokaler Entwurf für die Mumble-ACL-Verwaltung.
 *
 * Enthält keinerlei Netzwerk- oder Schreibfunktionen.
 * Der ursprüngliche Serverzustand bleibt unverändert.
 */
class MumbleAclDraft(
    val original: MumbleAclManager.AclSnapshot
) {
    val channelId: Int = original.channelId

    var inheritAcls: Boolean = original.inheritAcls
        private set

    private val groups =
        original.groups.toMutableList()

    private val rules =
        original.rules.toMutableList()

    fun getGroups(): List<Mumble.ACL.ChanGroup> =
        groups.toList()

    fun getRules(): List<Mumble.ACL.ChanACL> =
        rules.toList()


    /**
     * Fügt eine neue lokale ACL-Regel am Ende hinzu.
     * Geerbte Regeln dürfen nicht eingefügt werden.
     */
    fun addRule(rule: Mumble.ACL.ChanACL): Boolean {
        if (rule.inherited) return false
        rules.add(rule)
        return true
    }

    /**
     * Bearbeitet ausschließlich eine lokale ACL-Regel.
     * Ihre Position innerhalb der Regelliste bleibt erhalten.
     */
    fun updateRule(
        index: Int,
        rule: Mumble.ACL.ChanACL
    ): Boolean {
        if (index !in rules.indices) return false
        if (rules[index].inherited) return false
        if (rule.inherited) return false

        rules[index] = rule
        return true
    }

    /**
     * Entfernt ausschließlich eine lokale ACL-Regel.
     */
    fun removeRule(index: Int): Boolean {
        if (index !in rules.indices) return false
        if (rules[index].inherited) return false

        rules.removeAt(index)
        return true
    }


    /**
     * Verschiebt eine lokale ACL-Regel um genau eine Position.
     *
     * Geerbte Regeln dürfen weder verschoben
     * noch durch eine lokale Regel verdrängt werden.
     */
    fun moveRule(index: Int, direction: Int): Boolean {
        if (direction != -1 && direction != 1) return false
        if (index !in rules.indices) return false

        val target = index + direction
        if (target !in rules.indices) return false

        if (rules[index].inherited) return false
        if (rules[target].inherited) return false

        val rule = rules.removeAt(index)
        rules.add(target, rule)
        return true
    }

    fun setInheritance(enabled: Boolean) {
        inheritAcls = enabled
    }

    /**
     * Legt eine neue ACL-Gruppe ausschließlich im lokalen Entwurf an.
     * Der Server wird erst über die bestehende ACL-Schreibfunktion geändert.
     */
    fun addGroup(name: String): Boolean {
        val normalizedName = name.trim()

        if (normalizedName.isEmpty()) return false
        if (groups.any { it.name == normalizedName }) return false

        val group = Mumble.ACL.ChanGroup.newBuilder()
            .setName(normalizedName)
            .setInherited(false)
            .setInherit(false)
            .setInheritable(true)
            .build()

        groups.add(group)
        return true
    }

    fun updateGroup(
        index: Int,
        group: Mumble.ACL.ChanGroup
    ): Boolean {
        if (index !in groups.indices) return false
        if (groups[index].inherited) return false
        if (group.inherited) return false

        groups[index] = group
        return true
    }

    /**
     * Entfernt eine lokale ACL-Gruppe aus dem Entwurf.
     * Geerbte Gruppen dürfen nicht gelöscht werden.
     * Der Server bleibt bis zur bestätigten ACL-Übertragung unverändert.
     */
    fun removeGroup(index: Int): Boolean {
        if (index !in groups.indices) return false
        if (groups[index].inherited) return false

        groups.removeAt(index)
        return true
    }

    /**
     * Ändert ausschließlich lokale Gruppenmitgliedschaften.
     *
     * Direkte und ausgeschlossene Mitglieder dürfen sich nicht
     * überschneiden. Geerbte Gruppen bleiben unverändert.
     */
    fun updateGroupMembers(
        index: Int,
        directMembers: Set<Int>,
        excludedMembers: Set<Int>,
        inheritToSubchannels: Boolean
    ): Boolean {
        if (index !in groups.indices) return false

        val current = groups[index]
        if (current.inherited) return false

        if (directMembers.any { it < 0 }) return false
        if (excludedMembers.any { it < 0 }) return false

        if (directMembers.intersect(excludedMembers).isNotEmpty()) {
            return false
        }

        val updated = current.toBuilder()
            .setInheritable(inheritToSubchannels)
            .clearAdd()
            .addAllAdd(directMembers.sorted())
            .clearRemove()
            .addAllRemove(excludedMembers.sorted())
            .build()

        groups[index] = updated
        return true
    }

    /**
     * Erstellt eine lokale Überschreibung für eine geerbte Gruppe.
     *
     * Die übergeordnete Gruppe bleibt unverändert.
     * Ausschlüsse gelten zunächst nur im lokalen ACL-Entwurf.
     */
    fun excludeInheritedGroupMember(
        groupIndex: Int,
        userId: Int
    ): Boolean {
        if (userId < 0 || groupIndex !in groups.indices) {
            return false
        }

        val group = groups[groupIndex]
        if (!group.inherited) return false

        val local = groups.firstOrNull {
            !it.inherited && it.name == group.name
        }

        val exclusions = local?.removeList?.toMutableSet()
            ?: mutableSetOf()

        exclusions.add(userId)

        return updateInheritedGroupExclusions(
            groupIndex,
            exclusions
        )
    }

    /**
     * Bereitet lokale Ausschlüsse einer geerbten Gruppe vor.
     * Die übergeordnete Gruppendefinition bleibt unverändert.
     */
    fun updateInheritedGroupExclusions(
        groupIndex: Int,
        excludedMembers: Set<Int>,
        inheritToSubchannels: Boolean = false
    ): Boolean {
        if (groupIndex !in groups.indices) return false
        if (excludedMembers.any { it < 0 }) return false

        val inheritedGroup = groups[groupIndex]
        if (!inheritedGroup.inherited) return false
        if (inheritedGroup.name.isBlank()) return false

        val localIndices = groups.indices.filter {
            !groups[it].inherited &&
                groups[it].name == inheritedGroup.name
        }

        if (localIndices.size > 1) return false

        val localIndex = localIndices.firstOrNull()

        if (localIndex != null) {
            val local = groups[localIndex]
            val direct = local.addList.toMutableSet()

            // Ein ausgeschlossener Benutzer darf nicht
            // gleichzeitig direktes Mitglied sein.
            direct.removeAll(excludedMembers)

            val updated = local.toBuilder()
                .setInheritable(inheritToSubchannels)
                .clearAdd()
                .addAllAdd(direct.sorted())
                .clearRemove()
                .addAllRemove(excludedMembers.sorted())
                .build()

            return updateGroup(localIndex, updated)
        }

        val localGroup = Mumble.ACL.ChanGroup.newBuilder()
            .setName(inheritedGroup.name)
            .setInherited(false)
            .setInherit(true)
            .setInheritable(inheritToSubchannels)
            .addAllRemove(excludedMembers.sorted())
            .build()

        groups.add(localGroup)
        return true
    }

    /**
     * Liefert die lokalen Ausschlüsse für eine geerbte Gruppe.
     * Verändert weder Entwurf noch Server.
     */
    fun getLocalExclusionsForInheritedGroup(
        groupIndex: Int
    ): Set<Int> {
        val inherited = groups.getOrNull(groupIndex)
            ?: return emptySet()

        if (!inherited.inherited) return emptySet()

        return groups.firstOrNull {
            !it.inherited && it.name == inherited.name
        }?.removeList?.toSet() ?: emptySet()
    }

    fun hasChanges(): Boolean =
        inheritAcls != original.inheritAcls ||
            groups != original.groups ||
            rules != original.rules

    fun discardChanges() {
        inheritAcls = original.inheritAcls

        groups.clear()
        groups.addAll(original.groups)

        rules.clear()
        rules.addAll(original.rules)
    }
}
