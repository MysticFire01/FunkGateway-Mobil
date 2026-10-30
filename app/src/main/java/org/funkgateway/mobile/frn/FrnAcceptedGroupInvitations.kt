package org.funkgateway.mobile.frn

object FrnAcceptedGroupInvitations {
    private const val TIMEOUT_MS = 10 * 60 * 1000L

    private val pending = linkedMapOf<String, FrnGroupInvitation>()
    private val createdAt = linkedMapOf<String, Long>()
    private val confirmed = mutableSetOf<String>()
    private const val RETRY_INTERVAL_MS = 10_000L
    private const val MAX_RETRIES = 5
    private val retryCount = mutableMapOf<String, Int>()
    private val lastRetryAt = mutableMapOf<String, Long>()

    @Synchronized
    fun takeDueRetries(): List<FrnGroupInvitation> {
        cleanup()
        val now = System.currentTimeMillis()
        return pending.values.filter { invitation ->
            val id = invitation.invitationId
            val count = retryCount[id] ?: 0
            val last = lastRetryAt[id] ?: createdAt[id] ?: now

            if (id in confirmed ||
                count >= MAX_RETRIES ||
                now < last ||
                now - last < RETRY_INTERVAL_MS
            ) {
                false
            } else {
                true
            }
        }.toList()
    }

    @Synchronized
    fun markRetryStarted(invitationId: String): Boolean {
        cleanup()
        val invitation = pending[invitationId] ?: return false
        if (invitation.invitationId in confirmed) return false

        val now = System.currentTimeMillis()
        val count = retryCount[invitationId] ?: 0
        val last = lastRetryAt[invitationId]
            ?: createdAt[invitationId]
            ?: return false

        if (count >= MAX_RETRIES ||
            now < last ||
            now - last < RETRY_INTERVAL_MS
        ) return false

        retryCount[invitationId] = count + 1
        lastRetryAt[invitationId] = now
        return true
    }

    @Synchronized
    fun markConfirmed(invitationId: String): Boolean {
        cleanup()
        if (!pending.containsKey(invitationId)) return false
        confirmed.add(invitationId)
        return true
    }

    @Synchronized
    fun awaitingConfirmation(): List<FrnGroupInvitation> {
        cleanup()
        return pending.values.filter {
            it.invitationId !in confirmed
        }.toList()
    }

    @Synchronized
    fun register(invitation: FrnGroupInvitation): Boolean {
        cleanup()

        if (invitation.invitationId.isBlank() ||
            invitation.groupId.isBlank() ||
            invitation.senderId.isBlank() ||
            invitation.server.isBlank() ||
            invitation.room.isBlank() ||
            pending.containsKey(invitation.invitationId)
        ) return false

        pending[invitation.invitationId] = invitation
        createdAt[invitation.invitationId] = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun find(
        invitationId: String,
        groupId: String,
        senderId: String,
        server: String,
        room: String
    ): FrnGroupInvitation? {
        cleanup()

        return pending[invitationId]?.takeIf {
            it.groupId == groupId &&
                it.senderId == senderId &&
                it.server.equals(server, ignoreCase = true) &&
                it.room.equals(room, ignoreCase = true)
        }
    }

    @Synchronized
    fun complete(invitationId: String): Boolean {
        createdAt.remove(invitationId)
        confirmed.remove(invitationId)
        retryCount.remove(invitationId)
        lastRetryAt.remove(invitationId)
        return pending.remove(invitationId) != null
    }

    @Synchronized
    fun cleanup() {
        val now = System.currentTimeMillis()
        val expired = createdAt.filterValues {
            now < it || now - it >= TIMEOUT_MS
        }.keys.toList()

        expired.forEach {
            pending.remove(it)
            createdAt.remove(it)
            confirmed.remove(it)
            retryCount.remove(it)
            lastRetryAt.remove(it)
        }
    }
}
