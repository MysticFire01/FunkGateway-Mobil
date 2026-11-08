package org.funkgateway.mobile

import android.util.Log
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protobuf.Mumble

class MumbleBanList {

    data class BanEntry(
        val name: String,
        val reason: String,
        val start: String,
        val durationSeconds: Int,
        val address: ByteArray,
        val mask: Int,
        val hash: String,
        val original: Mumble.BanList.BanEntry
    )


    data class ParsedBanAddress(
        val address: ByteArray,
        val mask: Int
    )

    companion object {
        fun parseBanAddress(input: String): ParsedBanAddress? {
            val value = input.trim()

            if (value.isEmpty()) return null

            return when {
                ':' in value -> parseIpv6BanAddress(value)
                '.' in value -> parseIpv4BanAddress(value)
                else -> null
            }
        }

        fun parseIpv4BanAddress(input: String): ParsedBanAddress? {
            val parts = input.trim().split("/")
            if (parts.size !in 1..2) return null

            val octets = parts[0].split(".")
            if (octets.size != 4) return null

            val values = octets.map { octet ->
                if (
                    octet.isEmpty() ||
                    octet.length > 3 ||
                    !octet.all { it in '0'..'9' } ||
                    (octet.length > 1 && octet.startsWith("0"))
                ) return null

                val value = octet.toIntOrNull() ?: return null
                if (value !in 0..255) return null
                value
            }

            val prefix = if (parts.size == 2) {
                val text = parts[1]
                if (
                    text.isEmpty() ||
                    !text.all { it in '0'..'9' }
                ) return null
                text.toIntOrNull() ?: return null
            } else {
                32
            }

            if (prefix !in 0..32) return null

            val address = ByteArray(16)
            address[10] = 0xff.toByte()
            address[11] = 0xff.toByte()

            for (i in 0..3) {
                address[12 + i] = values[i].toByte()
            }

            return ParsedBanAddress(
                address = address,
                mask = prefix + 96
            )
        }

        fun parseIpv6BanAddress(input: String): ParsedBanAddress? {
            val parts = input.trim().split("/")
            if (parts.size !in 1..2) return null

            val ip = parts[0]
            if (':' !in ip || '%' in ip || '.' in ip) return null

            val prefix = if (parts.size == 2) {
                val value = parts[1]
                if (value.isEmpty() || !value.all { it in '0'..'9' }) {
                    return null
                }
                value.toIntOrNull() ?: return null
            } else {
                128
            }
            if (prefix !in 0..128) return null

            if (ip.count { it == ':' } < 2) return null
            if (":::" in ip) return null

            val compression = ip.indexOf("::")
            if (compression >= 0 && ip.indexOf("::", compression + 2) >= 0) {
                return null
            }

            fun parseGroups(value: String): List<Int>? {
                if (value.isEmpty()) return emptyList()
                val groups = value.split(":")
                if (groups.any {
                    it.isEmpty() || it.length > 4 ||
                    !it.all { c ->
                        c in '0'..'9' ||
                        c in 'a'..'f' ||
                        c in 'A'..'F'
                    }
                }) return null

                return groups.map { it.toInt(16) }
            }

            val groups = if (compression >= 0) {
                val left = parseGroups(ip.substring(0, compression))
                    ?: return null
                val right = parseGroups(ip.substring(compression + 2))
                    ?: return null
                val missing = 8 - left.size - right.size
                if (missing < 1) return null
                left + List(missing) { 0 } + right
            } else {
                val full = parseGroups(ip) ?: return null
                if (full.size != 8) return null
                full
            }

            if (groups.size != 8) return null

            val address = ByteArray(16)
            groups.forEachIndexed { index, group ->
                address[index * 2] = (group ushr 8).toByte()
                address[index * 2 + 1] = group.toByte()
            }

            return ParsedBanAddress(address, prefix)
        }

        fun formatAddress(entry: BanEntry): String {
            val bytes = entry.address
            val mask = entry.mask

            if (bytes.size == 4) {
                val ip = bytes.joinToString(".") { (it.toInt() and 255).toString() }
                return "$ip/${mask.coerceIn(0, 32)}"
            }

            if (bytes.size == 16) {
                val ipv4Mapped =
                    bytes.take(10).all { it.toInt() == 0 } &&
                    (bytes[10].toInt() and 255) == 255 &&
                    (bytes[11].toInt() and 255) == 255

                if (ipv4Mapped && mask >= 96) {
                    val ip = bytes.takeLast(4).joinToString(".") {
                        (it.toInt() and 255).toString()
                    }
                    return "$ip/${mask - 96}"
                }

                return try {
                    val ip = java.net.InetAddress.getByAddress(bytes).hostAddress
                    "$ip/${mask.coerceIn(0, 128)}"
                } catch (e: Exception) {
                    "Ungültige IP-Adresse"
                }
            }

            return "IP-Adresse nicht verfügbar"
        }

        fun prepareBanAddition(
            existing: List<Mumble.BanList.BanEntry>,
            newBan: Mumble.BanList.BanEntry
        ): List<Mumble.BanList.BanEntry> {
            require(newBan.address.size() == 16) {
                "Eine Mumble-Sperre benötigt eine 16-Byte-IP-Adresse"
            }
            require(newBan.mask in 0..128) {
                "Ungültige Netzmaske"
            }
            return existing.toList() + newBan
        }

        fun prepareBanRemoval(
            existing: List<Mumble.BanList.BanEntry>,
            selected: Mumble.BanList.BanEntry
        ): List<Mumble.BanList.BanEntry> {
            val matches = existing.count { it == selected }
            require(matches == 1) {
                "Die ausgewählte Sperre muss genau einmal vorhanden sein"
            }

            val index = existing.indexOf(selected)
            return existing.filterIndexed { i, _ -> i != index }
        }

    }

    var onBansReceived: ((List<BanEntry>) -> Unit)? = null

    private var connection: HumlaConnection? = null
    private var bans: List<BanEntry> = emptyList()
    private var serverListReceived = false
    private var receivedListVersion = 0L
    private var requestPending = false
    private var requestTimedOut = false
    private val timeoutHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private var timeoutTask: Runnable? = null
    var onRequestTimeout: (() -> Unit)? = null

    private var listener: HumlaTCPMessageListener? = null

    @Synchronized
    fun attach(newConnection: HumlaConnection) {
        detach()

        val newListener = object : HumlaTCPMessageListener.Stub() {
            override fun messageBanList(message: Mumble.BanList) {
                if (message.getQuery()) return

                val received = message.bansList.map { ban ->
                    BanEntry(
                        name = ban.name,
                        reason = ban.reason,
                        start = ban.start,
                        durationSeconds = ban.duration,
                        address = ban.address.toByteArray(),
                        mask = ban.mask,
                        hash = ban.hash,
                        original = ban
                    )
                }

                synchronized(this@MumbleBanList) {
                    if (
                        connection !== newConnection ||
                        listener !== this
                    ) return

                    if (!requestPending || requestTimedOut) return

                    timeoutTask?.let { timeoutHandler.removeCallbacks(it) }
                    timeoutTask = null
                    bans = received
                    ++receivedListVersion
                    serverListReceived = true
                    requestPending = false
                }

                Log.i(
                    "FGW-Mumble",
                    "Mumble-Bannliste empfangen: ${received.size} Einträge"
                )
                onBansReceived?.invoke(received)
            }
        }

        connection = newConnection
        listener = newListener
        newConnection.addTCPMessageHandlers(newListener)
    }

    @Synchronized
    fun detach() {
        val oldConnection = connection
        val oldListener = listener

        connection = null
        listener = null
        timeoutTask?.let { timeoutHandler.removeCallbacks(it) }
        timeoutTask = null
        bans = emptyList()
        serverListReceived = false
        requestPending = false
        requestTimedOut = false

        if (oldConnection != null && oldListener != null) {
            oldConnection.removeTCPMessageHandler(oldListener)
        }
    }

    @Synchronized
    fun getBans(): List<BanEntry> = bans.toList()

    /**
     * Liefert Bannliste und lokale Version als gemeinsamen Snapshot.
     * Bei ausstehender oder ungültiger Serverantwort wird null geliefert.
     */
    @Synchronized
    internal fun getBanListSnapshot():
        Pair<Long, List<Mumble.BanList.BanEntry>>? {
        if (!isBanListWriteSafe()) return null

        return Pair(
            receivedListVersion,
            bans.map { it.original }
        )
    }

    @Synchronized
    fun getReceivedListVersion(): Long? =
        if (isBanListWriteSafe()) receivedListVersion else null

    @Synchronized
    fun hasReceivedServerList(): Boolean =
        serverListReceived && !requestTimedOut

    @Synchronized
    fun isBanListWriteSafe(): Boolean =
        serverListReceived && !requestPending && !requestTimedOut

    /**
     * Überträgt eine vollständig vorbereitete Bannliste.
     * Der Aufrufer muss Berechtigung, Sitzung und Bestätigung prüfen.
     * Ein erfolgreicher Versand bestätigt keine Übernahme durch den Server.
     */
    @Synchronized
    internal fun sendPreparedBanList(
        expectedVersion: Long,
        entries: List<Mumble.BanList.BanEntry>
    ): Boolean {
        val active = connection ?: return false

        if (!isBanListWriteSafe()) return false
        if (receivedListVersion != expectedVersion) return false

        return try {
            val message = Mumble.BanList.newBuilder()
                .setQuery(false)
                .addAllBans(entries)
                .build()

            active.sendTCPMessage(
                message,
                HumlaTCPMessageType.BanList
            )

            // Nach dem Versand keine veraltete Liste weiterverwenden.
            serverListReceived = false
            bans = emptyList()

            Log.i(
                "FGW-Mumble",
                "Bannlistenänderung gesendet; erneutes Laden erforderlich"
            )
            true
        } catch (e: Exception) {
            serverListReceived = false
            bans = emptyList()
            Log.e("FGW-Mumble", "Bannlistenversand fehlgeschlagen", e)
            false
        }
    }

    @Synchronized
    fun requestBans(): Boolean {
        val active = connection ?: return false

        if (requestPending || requestTimedOut) {
            Log.w("FGW-Mumble", "Bannlisten-Anfrage blockiert")
            return false
        }

        requestPending = true
        serverListReceived = false
        bans = emptyList()

        return try {
            val request = Mumble.BanList.newBuilder()
                .setQuery(true)
                .build()

            active.sendTCPMessage(
                request,
                HumlaTCPMessageType.BanList
            )
            val expectedConnection = active
            val task = Runnable {
                val notifyTimeout = synchronized(this) {
                    if (
                        connection !== expectedConnection ||
                        !requestPending
                    ) {
                        false
                    } else {
                        requestPending = false
                        requestTimedOut = true
                        serverListReceived = false
                        bans = emptyList()
                        timeoutTask = null
                        true
                    }
                }
                if (notifyTimeout) {
                    Log.w("FGW-Mumble", "Bannlisten-Anfrage: Zeitüberschreitung")
                    onRequestTimeout?.invoke()
                }
            }
            timeoutTask = task
            timeoutHandler.postDelayed(task, 10_000L)

            Log.i("FGW-Mumble", "Mumble-Bannliste angefordert")
            true
        } catch (e: Exception) {
            timeoutTask?.let { timeoutHandler.removeCallbacks(it) }
            timeoutTask = null
            requestPending = false
            requestTimedOut = true
            serverListReceived = false
            bans = emptyList()
            Log.e("FGW-Mumble", "Bannliste konnte nicht angefordert werden", e)
            false
        }
    }
}
