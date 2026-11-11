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

        /**
         * Bekannte Berechtigungen für den lokalen ACL-Editor.
         * Die interne Liste bleibt unveränderbar.
         */
        fun editablePermissions(): List<Pair<Int, String>> =
            permissionLabels.toList()

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

    var onAclReceived: ((VersionedAclSnapshot) -> Unit)? = null

    // Originale Berechtigungsnachricht des Mumble-Servers.
    var onPermissionQueryReceived: ((Mumble.PermissionQuery) -> Unit)? = null

    private val aclLock = Any()
    private var connection: HumlaConnection? = null

    // Beobachtung lesender ACL-Abfragen, keine Schreibfreigabe.
    private val requestState = MumbleAclRequestState()
    private val timeoutHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private var timeoutTask: Runnable? = null

    // Meldet nur, dass innerhalb der Frist keine passende
    // ACL-Nachricht beobachtet wurde.
    var onAclRequestTimeout: ((Int, Long) -> Unit)? = null

    fun getAclRequestState(): MumbleAclRequestState.Snapshot =
        requestState.snapshot()

    // Zählt gültige ACL-Nachrichten innerhalb einer Verbindung.
    // Keine Bestätigung einer bestimmten Serveranfrage.
    private var aclReceiveVersion = 0L

    data class VersionedAclSnapshot(
        val version: Long,
        val snapshot: AclSnapshot
    )

    private val latestAclSnapshots =
        mutableMapOf<Int, VersionedAclSnapshot>()

    fun getLatestAclSnapshot(channelId: Int): VersionedAclSnapshot? =
        synchronized(aclLock) { latestAclSnapshots[channelId] }

    fun getAclReceiveVersion(): Long =
        synchronized(aclLock) { aclReceiveVersion }

    // Jede Verbindung erhält ihren eigenen Listener.
    // Alte Callbacks werden nach einem Wechsel verworfen.
    private var listener: HumlaTCPMessageListener? = null
    private var connectionGeneration = 0L

    fun attach(newConnection: HumlaConnection) {
        detach()

        val generation = synchronized(aclLock) {
            connection = newConnection
            aclReceiveVersion = 0L
            latestAclSnapshots.clear()
            connectionGeneration
        }

        val newListener = object : HumlaTCPMessageListener.Stub() {
            private fun isCurrent(): Boolean =
                synchronized(aclLock) {
                    connection === newConnection &&
                        connectionGeneration == generation
                }

            override fun messagePermissionQuery(message: Mumble.PermissionQuery) {
                Log.i(
                    "FGW-Mumble-ACL",
                    "PermissionQuery empfangen: " +
                        "Kanal=${if (message.hasChannelId()) message.channelId else "fehlt"}, " +
                        "Maske=${if (message.hasPermissions()) message.permissions else "fehlt"}, " +
                        "Flush=${message.hasFlush() && message.flush}"
                )
                val callback = synchronized(aclLock) {
                    if (connection === newConnection &&
                        connectionGeneration == generation
                    ) onPermissionQueryReceived else null
                }
                callback?.invoke(message)
            }

            override fun messageACL(message: Mumble.ACL) {
                if (!isCurrent()) return

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

                if (!isCurrent()) return

                Log.i(
                    "FGW-Mumble-ACL",
                    "ACL empfangen: Kanal ${snapshot.channelId}, " +
                        "${snapshot.groups.size} Gruppen, " +
                        "${snapshot.rules.size} Regeln"
                )

                val result = synchronized(aclLock) {
                    if (connection === newConnection &&
                        connectionGeneration == generation
                    ) {
                        ++aclReceiveVersion
                        val received = VersionedAclSnapshot(
                            aclReceiveVersion, snapshot
                        )
                        latestAclSnapshots[snapshot.channelId] = received
                        if (requestState.observe(snapshot.channelId)) {
                            timeoutTask?.let {
                                timeoutHandler.removeCallbacks(it)
                            }
                            timeoutTask = null
                            Log.i(
                                "FGW-Mumble-ACL",
                                "Passende ACL-Nachricht beobachtet: " +
                                    "Kanal ${snapshot.channelId}"
                            )
                        }
                        Pair(onAclReceived, received)
                    } else null
                }
                result?.first?.invoke(result.second)
            }
        }

        listener = newListener
        newConnection.addTCPMessageHandlers(newListener)
    }

    fun detach() {
        val (oldConnection, oldListener) = synchronized(aclLock) {
            ++connectionGeneration
            val previous = Pair(connection, listener)
            connection = null
            listener = null
            aclReceiveVersion = 0L
            latestAclSnapshots.clear()
            timeoutTask?.let { timeoutHandler.removeCallbacks(it) }
            timeoutTask = null
            requestState.reset()
            previous
        }

        if (oldListener != null) {
            oldConnection?.removeTCPMessageHandler(oldListener)
        }
    }

    /**
     * Technischer ACL-Schreibtransport.
     *
     * Darf ausschließlich nach zentraler Sicherheitsprüfung
     * und ausdrücklicher Benutzerbestätigung aufgerufen werden.
     * Ein erfolgreicher Versand bestätigt keine Serverübernahme.
     */
    internal fun sendPreparedAclWrite(
        message: Mumble.ACL,
        expectedConnection: HumlaConnection
    ): Boolean {
        if (!message.hasChannelId() ||
            message.channelId < 0 ||
            !message.hasQuery() ||
            message.query
        ) return false

        val prepared = synchronized(aclLock) {
            val active = connection?.takeIf {
                it === expectedConnection && it.isSynchronized
            } ?: return false
            Pair(active, connectionGeneration)
        }

        val (active, generation) = prepared

        return try {
            synchronized(aclLock) {
                if (connection !== active ||
                    active !== expectedConnection ||
                    connectionGeneration != generation ||
                    !active.isSynchronized
                ) {
                    return false
                }
                active.sendTCPMessage(message, HumlaTCPMessageType.ACL)
            }
            true
        } catch (e: Exception) {
            Log.e("FGW-Mumble-ACL", "ACL-Übertragung fehlgeschlagen", e)
            false
        }
    }

    fun requestAcl(channelId: Int): Boolean {
        if (channelId < 0) return false

        val prepared = synchronized(aclLock) {
            val active = connection ?: return false
            if (!active.isSynchronized) return false

            timeoutTask?.let { timeoutHandler.removeCallbacks(it) }
            timeoutTask = null

            Triple(
                active,
                connectionGeneration,
                requestState.start(channelId)
            )
        }

        val (active, generation, request) = prepared

        val stillCurrent = synchronized(aclLock) {
            val current = requestState.snapshot()
            connection === active &&
                connectionGeneration == generation &&
                current.requestId == request.requestId &&
                current.channelId == channelId &&
                (
                    current.status == MumbleAclRequestState.Status.PENDING ||
                    current.status ==
                        MumbleAclRequestState.Status.MESSAGE_OBSERVED
                )
        }
        if (!stillCurrent) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Abfrage verworfen: Verbindung oder Anfrage gewechselt"
            )
            return false
        }

        return try {
            val message = Mumble.ACL.newBuilder()
                .setChannelId(channelId)
                .setQuery(true)
                .build()

            active.sendTCPMessage(message, HumlaTCPMessageType.ACL)

            val requestStillCurrent = synchronized(aclLock) {
                val current = requestState.snapshot()
                val sameRequest =
                    connection === active &&
                    connectionGeneration == generation &&
                    current.requestId == request.requestId &&
                    current.channelId == channelId

                if (sameRequest &&
                    current.status == MumbleAclRequestState.Status.PENDING
                ) {
                    val task = Runnable {
                        val notify = synchronized(aclLock) {
                            if (connection !== active ||
                                connectionGeneration != generation ||
                                !requestState.timeout(request.requestId)
                            ) {
                                false
                            } else {
                                timeoutTask = null
                                true
                            }
                        }

                        if (notify) {
                            Log.w(
                                "FGW-Mumble-ACL",
                                "Keine passende ACL-Nachricht innerhalb " +
                                    "von 10 Sekunden: Kanal $channelId"
                            )
                            onAclRequestTimeout?.invoke(
                                channelId,
                                request.requestId
                            )
                        }
                    }

                    timeoutTask = task
                    timeoutHandler.postDelayed(task, 10_000L)
                    true
                } else {
                    sameRequest &&
                        current.status ==
                            MumbleAclRequestState.Status.MESSAGE_OBSERVED
                }
            }

            if (requestStillCurrent) {
                Log.i(
                    "FGW-Mumble-ACL",
                    "ACL-Abfrage gesendet: Kanal $channelId"
                )
            } else {
                Log.w(
                    "FGW-Mumble-ACL",
                    "ACL-Abfrage nach Versand nicht mehr aktuell: Kanal $channelId"
                )
            }
            requestStillCurrent
        } catch (e: Exception) {
            synchronized(aclLock) {
                if (connection === active &&
                    connectionGeneration == generation &&
                    requestState.snapshot() == request
                ) {
                    requestState.reset()
                    timeoutTask?.let { timeoutHandler.removeCallbacks(it) }
                    timeoutTask = null
                }
            }

            Log.e(
                "FGW-Mumble-ACL",
                "ACL-Abfrage fehlgeschlagen: Kanal $channelId",
                e
            )
            false
        }
    }

}
