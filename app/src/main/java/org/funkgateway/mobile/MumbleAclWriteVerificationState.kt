package org.funkgateway.mobile

/**
 * Zustandsverwaltung für die ACL-Nachkontrolle.
 *
 * Keine Netzwerkkommunikation und keine Schreibfunktion.
 * Eine beobachtete Antwort ist keine eindeutige
 * Bestätigung eines bestimmten Schreibvorgangs.
 */
class MumbleAclWriteVerificationState {

    enum class Status {
        IDLE,
        ARMED,
        PENDING,
        CONTENT_MATCHED,
        CONTENT_MISMATCH,
        READBACK_FAILED,
        TIMED_OUT
    }

    data class Snapshot(
        val status: Status,
        val verificationId: Long,
        val connectionGeneration: Long,
        val channelId: Int,
        val versionBeforeWrite: Long,
        val completedReceiveVersion: Long = -1L
    )

    private var nextId = 0L

    private var current = Snapshot(
        Status.IDLE, 0L, -1L, -1, -1L
    )

    @Synchronized
    fun snapshot(): Snapshot = current

    @Synchronized
    fun start(
        generation: Long,
        channelId: Int,
        versionBeforeWrite: Long
    ): Snapshot {
        require(generation >= 0L)
        require(channelId >= 0)
        require(versionBeforeWrite > 0L)

        current = Snapshot(
            Status.ARMED,
            ++nextId,
            generation,
            channelId,
            versionBeforeWrite
        )
        return current
    }

    /**
     * Gibt eine vorbereitete Nachkontrolle erst nach erfolgreicher
     * Übergabe der Schreibnachricht für Serverantworten frei.
     */
    @Synchronized
    fun activate(verificationId: Long): Boolean {
        if (current.status != Status.ARMED ||
            current.verificationId != verificationId
        ) return false

        current = current.copy(status = Status.PENDING)
        return true
    }

    @Synchronized
    fun observe(
        verificationId: Long,
        context: MumbleAclWriteVerification.Context,
        contentMatches: Boolean
    ): Boolean {
        if (current.status != Status.PENDING ||
            current.verificationId != verificationId ||
            current.connectionGeneration != context.expectedGeneration ||
            current.channelId != context.expectedChannelId ||
            current.versionBeforeWrite != context.versionBeforeWrite ||
            !MumbleAclWriteVerification.isCandidateResponse(context)
        ) {
            return false
        }

        current = current.copy(
            status = if (contentMatches) {
                Status.CONTENT_MATCHED
            } else {
                Status.CONTENT_MISMATCH
            },
            completedReceiveVersion = context.receivedVersion
        )
        return true
    }

    /**
     * Die Schreibnachricht kann bereits übertragen worden sein.
     * Ohne erfolgreiche Rückleseanfrage darf keine spätere
     * ACL-Nachricht diesen Schreibvorgang bestätigen.
     */
    @Synchronized
    fun readbackFailed(verificationId: Long): Boolean {
        if (current.status != Status.PENDING ||
            current.verificationId != verificationId
        ) return false

        current = current.copy(status = Status.READBACK_FAILED)
        return true
    }

    @Synchronized
    fun timeout(verificationId: Long): Boolean {
        if (current.status != Status.PENDING ||
            current.verificationId != verificationId
        ) return false

        current = current.copy(status = Status.TIMED_OUT)
        return true
    }

    @Synchronized
    fun reset() {
        current = Snapshot(
            Status.IDLE, ++nextId, -1L, -1, -1L
        )
    }
}
