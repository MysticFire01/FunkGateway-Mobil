package org.funkgateway.mobile

/**
 * Lokale Bearbeitung der Mumble-ACL-Berechtigungen.
 *
 * Unbekannte Berechtigungsbits bleiben erhalten.
 * Diese Klasse sendet keine Daten an den Server.
 */
object MumbleAclPermissionEditor {

    enum class State {
        UNSET,
        ALLOW,
        DENY
    }

    fun state(grant: Int, deny: Int, bit: Int): State {
        require(Integer.bitCount(bit) == 1) {
            "Es muss genau ein Berechtigungsbit angegeben werden."
        }

        return when {
            (deny and bit) != 0 -> State.DENY
            (grant and bit) != 0 -> State.ALLOW
            else -> State.UNSET
        }
    }

    fun update(
        grant: Int,
        deny: Int,
        bit: Int,
        state: State
    ): Pair<Int, Int> {
        require(Integer.bitCount(bit) == 1) {
            "Es muss genau ein Berechtigungsbit angegeben werden."
        }

        var newGrant = grant and bit.inv()
        var newDeny = deny and bit.inv()

        when (state) {
            State.ALLOW -> newGrant = newGrant or bit
            State.DENY -> newDeny = newDeny or bit
            State.UNSET -> Unit
        }

        return newGrant to newDeny
    }
}
