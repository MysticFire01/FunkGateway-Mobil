package org.funkgateway.mobile.frn

data class FrnCompleteMemberSync(
    val groupId: String,
    val version: Long,
    val senderId: String,
    val members: LinkedHashMap<String, String>
)

object FrnGroupMemberSyncBuffer {
    private const val TIMEOUT_MS = 60_000L
    private const val MAX_PENDING_SYNCS = 32

    private data class Pending(
        val total: Int,
        val createdAt: Long,
        val entries: MutableMap<Int, Pair<String, String>> = mutableMapOf()
    )

    private val pending =
        mutableMapOf<Triple<String, Long, String>, Pending>()

    @Synchronized
    fun clear() {
        pending.clear()
    }

    @Synchronized
    fun accept(
        message: FrnGroupMemberSync
    ): FrnCompleteMemberSync? {
        val now = System.currentTimeMillis()

        pending.entries.removeAll {
            now - it.value.createdAt > TIMEOUT_MS
        }

        if (message.version <= 0 ||
            message.total !in 1..100 ||
            message.index !in 1..message.total ||
            message.senderId.isBlank() ||
            message.memberId.isBlank() ||
            message.memberName.isBlank()
        ) return null

        val key = Triple(
            message.groupId,
            message.version,
            message.senderId
        )

        if (!pending.containsKey(key) &&
            pending.size >= MAX_PENDING_SYNCS
        ) {
            val oldest = pending.minByOrNull { it.value.createdAt }
            if (oldest != null) {
                pending.remove(oldest.key)
            }
        }

        val batch = pending.getOrPut(key) {
            Pending(message.total, now)
        }

        if (batch.total != message.total) {
            pending.remove(key)
            return null
        }

        val entry = message.memberId to message.memberName
        val previous = batch.entries[message.index]

        if (previous != null && previous != entry) {
            pending.remove(key)
            return null
        }

        batch.entries[message.index] = entry

        if (batch.entries.size != batch.total) return null

        val members = linkedMapOf<String, String>()

        for (index in 1..batch.total) {
            val member = batch.entries[index] ?: return null

            if (members.containsKey(member.first)) {
                pending.remove(key)
                return null
            }

            members[member.first] = member.second
        }

        pending.remove(key)

        return FrnCompleteMemberSync(
            message.groupId,
            message.version,
            message.senderId,
            members
        )
    }
}
