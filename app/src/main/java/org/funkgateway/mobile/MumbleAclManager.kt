package org.funkgateway.mobile

import android.util.Log
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protobuf.Mumble

/**
 * Mumble-ACL-Verwaltung.
 *
 * Erste Entwicklungsstufe: ausschließlich lesender Zugriff.
 * Es werden keine Gruppen oder Berechtigungen verändert.
 */
class MumbleAclManager {

    data class AclSnapshot(
        val channelId: Int,
        val inheritAcls: Boolean,
        val groups: List<Mumble.ACL.ChanGroup>,
        val rules: List<Mumble.ACL.ChanACL>
    )


    companion object {
        private val permissionLabels = listOf(
            1 to "Kanal und Rechte verwalten",
            2 to "Kanal durchqueren",
            4 to "Kanal betreten",
            8 to "Sprechen",
            16 to "Benutzer stummschalten",
            32 to "Benutzer verschieben",
            64 to "Kanäle erstellen",
            128 to "Kanäle verknüpfen",
            256 to "Flüstern",
            512 to "Textnachrichten senden",
            1024 to "Temporäre Kanäle erstellen",
            65536 to "Benutzer hinauswerfen",
            131072 to "Benutzer sperren",
            262144 to "Benutzer registrieren",
            524288 to "Sich selbst registrieren"
        )

        fun permissionNames(mask: Int): List<String> {
            val names = permissionLabels
                .filter { (bit, _) -> (mask and bit) != 0 }
                .map { (_, name) -> name }

            val knownMask = permissionLabels.fold(0) { acc, pair ->
                acc or pair.first
            }
            val unknown = mask and knownMask.inv()

            return if (unknown != 0) {
                names + "Weitere Rechte (0x${unknown.toUInt().toString(16)})"
            } else {
                names
            }
        }
    }

    var onAclReceived: ((AclSnapshot) -> Unit)? = null

    private var connection: HumlaConnection? = null

    private val listener = object : HumlaTCPMessageListener.Stub() {
        override fun messageACL(message: Mumble.ACL) {
            if (!message.hasChannelId()) {
                Log.w("FGW-Mumble-ACL", "ACL ohne Kanal-ID ignoriert")
                return
            }

            val snapshot = AclSnapshot(
                channelId = message.channelId,
                inheritAcls = message.inheritAcls,
                groups = message.groupsList.toList(),
                rules = message.aclsList.toList()
            )

            Log.i(
                "FGW-Mumble-ACL",
                "ACL empfangen: Kanal ${snapshot.channelId}, " +
                    "${snapshot.groups.size} Gruppen, " +
                    "${snapshot.rules.size} Regeln"
            )

            onAclReceived?.invoke(snapshot)
        }
    }

    fun attach(newConnection: HumlaConnection) {
        detach()
        connection = newConnection
        newConnection.addTCPMessageHandlers(listener)
    }

    fun detach() {
        connection?.removeTCPMessageHandler(listener)
        connection = null
    }

    fun requestAcl(channelId: Int): Boolean {
        if (channelId < 0) return false
        val active = connection ?: return false
        if (!active.isSynchronized) return false

        return try {
            val request = Mumble.ACL.newBuilder()
                .setChannelId(channelId)
                .setQuery(true)
                .build()

            active.sendTCPMessage(
                request,
                HumlaTCPMessageType.ACL
            )

            Log.i(
                "FGW-Mumble-ACL",
                "ACL-Abfrage gesendet: Kanal $channelId"
            )
            true
        } catch (e: Exception) {
            Log.e(
                "FGW-Mumble-ACL",
                "ACL-Abfrage fehlgeschlagen: Kanal $channelId",
                e
            )
            false
        }
    }
}
