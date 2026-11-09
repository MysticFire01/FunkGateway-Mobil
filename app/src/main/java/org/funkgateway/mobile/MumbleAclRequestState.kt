package org.funkgateway.mobile

/**
 * Beobachtungszustand einer lesenden Mumble-ACL-Abfrage.
 *
 * Eine empfangene ACL-Nachricht ist keine eindeutige
 * Bestätigung der zuletzt gesendeten Anfrage.
 */
class MumbleAclRequestState {

    enum class Status {
        IDLE,
        PENDING,
        MESSAGE_OBSERVED,
        TIMED_OUT
    }

    data class Snapshot(
        val status: Status,
        val channelId: Int?,
        val requestId: Long
    )

    private var nextRequestId = 0L
    private var current = Snapshot(Status.IDLE, null, 0L)

    @Synchronized
    fun snapshot(): Snapshot = current

    @Synchronized
    fun start(channelId: Int): Snapshot {
        require(channelId >= 0)
        current = Snapshot(
            Status.PENDING,
            channelId,
            ++nextRequestId
        )
        return current
    }

    @Synchronized
    fun observe(channelId: Int): Boolean {
        if (current.status != Status.PENDING ||
            current.channelId != channelId
        ) return false

        current = current.copy(status = Status.MESSAGE_OBSERVED)
        return true
    }

    @Synchronized
    fun timeout(requestId: Long): Boolean {
        if (current.status != Status.PENDING ||
            current.requestId != requestId
        ) return false

        current = current.copy(status = Status.TIMED_OUT)
        return true
    }

    @Synchronized
    fun reset() {
        current = Snapshot(Status.IDLE, null, ++nextRequestId)
    }
}
