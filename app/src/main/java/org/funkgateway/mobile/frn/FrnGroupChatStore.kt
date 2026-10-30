package org.funkgateway.mobile.frn

object FrnGroupChatStore {
    private const val MAX_MESSAGES_PER_GROUP = 300

    private val histories =
        linkedMapOf<String, ArrayDeque<FrnChatStore.Entry>>()

    @Synchronized
    fun add(
        groupId: String,
        sender: String,
        senderId: String,
        message: String,
        outgoing: Boolean
    ) {
        val history = histories.getOrPut(groupId) { ArrayDeque() }

        history.addLast(
            FrnChatStore.Entry(
                time = System.currentTimeMillis(),
                sender = sender,
                senderId = senderId,
                message = message,
                privateMessage = true,
                outgoing = outgoing
            )
        )

        while (history.size > MAX_MESSAGES_PER_GROUP) {
            history.removeFirst()
        }
    }

    @Synchronized
    fun messages(groupId: String): List<FrnChatStore.Entry> =
        histories[groupId]?.toList().orEmpty()

    @Synchronized
    fun clear(groupId: String) {
        histories.remove(groupId)
    }
}
