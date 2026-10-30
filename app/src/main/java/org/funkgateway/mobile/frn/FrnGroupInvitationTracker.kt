package org.funkgateway.mobile.frn

enum class FrnInvitationStatus {
    PENDING,
    ACCEPTED,
    CONFIRMED
}

data class FrnPendingGroupInvitation(
    val invitationId: String,
    val groupId: String,
    val recipientId: String,
    val server: String,
    val room: String,
    val createdAt: Long = System.currentTimeMillis(),
    var status: FrnInvitationStatus = FrnInvitationStatus.PENDING
)

object FrnGroupInvitationTracker {
    private const val TIMEOUT_MS = 10 * 60 * 1000L
    private val pending = linkedMapOf<String, FrnPendingGroupInvitation>()
    private const val RETRY_INTERVAL_MS = 10_000L
    private const val MAX_RETRIES = 5
    private val retryCount = mutableMapOf<String, Int>()
    private val lastRetryAt = mutableMapOf<String, Long>()

    @Synchronized
    fun takeDueConfirmRetries(): List<FrnPendingGroupInvitation> {
        cleanup()
        val now = System.currentTimeMillis()
        return pending.values.filter { invitation ->
            val id = invitation.invitationId
            val count = retryCount[id] ?: 0
            val last = lastRetryAt[id] ?: invitation.createdAt

            invitation.status == FrnInvitationStatus.ACCEPTED &&
                count < MAX_RETRIES &&
                now >= last &&
                now - last >= RETRY_INTERVAL_MS
        }.toList()
    }

    @Synchronized
    fun markConfirmRetryStarted(invitationId: String): Boolean {
        cleanup()
        val invitation = pending[invitationId] ?: return false
        if (invitation.status != FrnInvitationStatus.ACCEPTED) {
            return false
        }

        val now = System.currentTimeMillis()
        val count = retryCount[invitationId] ?: 0
        val last = lastRetryAt[invitationId] ?: invitation.createdAt

        if (count >= MAX_RETRIES ||
            now < last ||
            now - last < RETRY_INTERVAL_MS
        ) return false

        retryCount[invitationId] = count + 1
        lastRetryAt[invitationId] = now
        return true
    }

    @Synchronized
    fun register(invitation: FrnPendingGroupInvitation): Boolean {
        cleanup()
        if (invitation.invitationId.isBlank() ||
            invitation.groupId.isBlank() ||
            invitation.recipientId.isBlank() ||
            invitation.server.isBlank() ||
            invitation.room.isBlank() ||
            pending.containsKey(invitation.invitationId)
        ) return false

        pending[invitation.invitationId] = invitation
        return true
    }

    @Synchronized
    fun find(
        invitationId: String,
        groupId: String,
        senderId: String,
        server: String,
        room: String
    ): FrnPendingGroupInvitation? {
        cleanup()
        val invitation = pending[invitationId] ?: return null

        return invitation.takeIf {
            it.groupId == groupId &&
            it.recipientId == senderId &&
            it.server.equals(server, ignoreCase = true) &&
            it.room.equals(room, ignoreCase = true)
        }
    }

    @Synchronized
    fun markAccepted(
        invitationId: String,
        groupId: String,
        senderId: String,
        server: String,
        room: String
    ): Boolean {
        val invitation = find(
            invitationId,
            groupId,
            senderId,
            server,
            room
        ) ?: return false

        if (invitation.status != FrnInvitationStatus.PENDING) {
            return false
        }

        invitation.status = FrnInvitationStatus.ACCEPTED
        lastRetryAt[invitationId] = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun isAwaitingAck(
        invitationId: String,
        groupId: String,
        senderId: String,
        server: String,
        room: String
    ): Boolean {
        val invitation = find(
            invitationId,
            groupId,
            senderId,
            server,
            room
        ) ?: return false

        return invitation.status == FrnInvitationStatus.ACCEPTED
    }

    @Synchronized
    fun markConfirmed(
        invitationId: String,
        groupId: String,
        senderId: String,
        server: String,
        room: String
    ): Boolean {
        val invitation = find(
            invitationId,
            groupId,
            senderId,
            server,
            room
        ) ?: return false

        if (invitation.status != FrnInvitationStatus.ACCEPTED) {
            return false
        }

        invitation.status = FrnInvitationStatus.CONFIRMED
        return true
    }

    @Synchronized
    fun rollbackConfirmation(
        invitationId: String,
        groupId: String,
        senderId: String,
        server: String,
        room: String
    ): Boolean {
        val invitation = find(
            invitationId,
            groupId,
            senderId,
            server,
            room
        ) ?: return false

        if (invitation.status != FrnInvitationStatus.CONFIRMED) {
            return false
        }

        invitation.status = FrnInvitationStatus.ACCEPTED
        return true
    }

    @Synchronized
    fun complete(invitationId: String): Boolean {
        cleanup()
        retryCount.remove(invitationId)
        lastRetryAt.remove(invitationId)
        return pending.remove(invitationId) != null
    }

    @Synchronized
    fun cleanup() {
        val now = System.currentTimeMillis()
        val expired = pending.values.filter {
            now - it.createdAt >= TIMEOUT_MS ||
                now < it.createdAt
        }.map { it.invitationId }

        expired.forEach {
            pending.remove(it)
            retryCount.remove(it)
            lastRetryAt.remove(it)
        }
    }
}
