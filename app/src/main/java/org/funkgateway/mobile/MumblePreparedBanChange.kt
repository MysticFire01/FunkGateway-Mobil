package org.funkgateway.mobile

import se.lublin.humla.protobuf.Mumble

/**
 * Eine lokal vorbereitete Änderung der Mumble-Bannliste.
 *
 * Diese Datenklasse führt keine Serveränderung aus.
 */
internal data class MumblePreparedBanChange(
    val context: MumbleBanChangeContext,
    val operation: Operation,
    val originalBans: List<Mumble.BanList.BanEntry>,
    val preparedBans: List<Mumble.BanList.BanEntry>
) {
    enum class Operation {
        ADD,
        REMOVE
    }
}
