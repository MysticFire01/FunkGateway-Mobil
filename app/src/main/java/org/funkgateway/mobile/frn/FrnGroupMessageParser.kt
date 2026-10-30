package org.funkgateway.mobile.frn

data class FrnGroupReply(
    val groupId: String,
    val senderId: String,
    val sender: String,
    val text: String
)

object FrnGroupMessageParser {

    private val prefix = Regex(
        "^\\[(G(?:10|[1-9]))\\]\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun recognize(
        message: FrnChatMessage,
        server: String,
        room: String
    ): FrnGroupReply? {
        if (!message.privateMessage) return null

        val match = prefix.matchEntire(message.message.trim())
            ?: return null

        val code = match.groupValues[1].uppercase()
        val text = match.groupValues[2].trim()

        if (text.isBlank()) return null

        val knownGroups = FrnChatGroups.all()
        android.util.Log.d(
            "FRN-Group-Debug",
            "Empfangen: code=$code senderId=${message.senderId} " +
                "server=$server raum=$room"
        )
        knownGroups.forEach { group ->
            android.util.Log.d(
                "FRN-Group-Debug",
                "Gruppe: code=${group.shortCode} " +
                    "server=${group.server} raum=${group.room} " +
                    "mitglieder=${group.participants.keys.joinToString()}"
            )
        }

        val group = knownGroups.firstOrNull {
            it.shortCode.equals(code, ignoreCase = true) &&
                it.server.equals(server, ignoreCase = true) &&
                it.room.equals(room, ignoreCase = true) &&
                it.participants.keys.any { participantId ->
                    participantId.equals(message.senderId, ignoreCase = true)
                }
        } ?: return null

        return FrnGroupReply(
            groupId = group.id,
            senderId = message.senderId,
            sender = message.sender,
            text = text
        )
    }
}
