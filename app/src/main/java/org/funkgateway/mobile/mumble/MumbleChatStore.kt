package org.funkgateway.mobile.mumble

import org.funkgateway.mobile.MumbleConnectionManager
import org.funkgateway.mobile.frn.FrnChatStore

object MumbleChatStore {
    private const val MAX_MESSAGES = 300

    enum class Scope {
        SERVER,
        CHANNEL,
        PRIVATE,
        UNKNOWN
    }

    data class Entry(
        val time: Long,
        val sender: String,
        val senderSessionId: Int,
        val text: String,
        val scope: Scope,
        val channelIds: List<Int> = emptyList(),
        val treeIds: List<Int> = emptyList(),
        val userSessionIds: List<Int> = emptyList(),
        val outgoing: Boolean = false
    ) {
        fun asBubble(): FrnChatStore.Entry =
            FrnChatStore.Entry(
                time = time,
                sender = sender,
                senderId = senderSessionId.toString(),
                message = text,
                privateMessage = scope == Scope.PRIVATE,
                outgoing = outgoing
            )
    }

    private val messages = ArrayDeque<Entry>()

    @Synchronized
    fun addIncoming(
        message: MumbleConnectionManager.MumbleChatMessage
    ) {
        val scope = when {
            message.targetUserSessionIds.isNotEmpty() ->
                Scope.PRIVATE
            0 in message.targetTreeIds ->
                Scope.SERVER
            message.targetChannelIds.isNotEmpty() ||
                message.targetTreeIds.isNotEmpty() ->
                Scope.CHANNEL
            else ->
                Scope.UNKNOWN
        }

        add(
            Entry(
                time = message.receivedAt.takeIf { it > 0L }
                    ?: System.currentTimeMillis(),
                sender = message.senderName,
                senderSessionId = message.senderSessionId,
                text = message.text,
                scope = scope,
                channelIds = message.targetChannelIds,
                treeIds = message.targetTreeIds,
                userSessionIds = message.targetUserSessionIds
            )
        )
    }

    @Synchronized
    fun add(entry: Entry) {
        messages.addLast(entry)
        while (messages.size > MAX_MESSAGES) {
            messages.removeFirst()
        }
    }

    @Synchronized
    fun all(): List<Entry> = messages.toList()

    @Synchronized
    fun server(): List<Entry> =
        messages.filter { it.scope == Scope.SERVER }

    @Synchronized
    fun channel(channelId: Int): List<Entry> =
        messages.filter {
            it.scope == Scope.CHANNEL &&
                (channelId in it.channelIds ||
                    channelId in it.treeIds)
        }

    @Synchronized
    fun privateWith(sessionId: Int): List<Entry> =
        messages.filter {
            it.scope == Scope.PRIVATE &&
                (it.senderSessionId == sessionId ||
                    sessionId in it.userSessionIds)
        }

    @Synchronized
    fun clear() {
        messages.clear()
    }
}
