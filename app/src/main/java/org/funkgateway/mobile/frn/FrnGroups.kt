package org.funkgateway.mobile.frn

data class FrnServerGroup(
    val manager: String,
    val server: String,
    val port: Int,
    val rooms: List<FrnRoom>
) {
    val roomCount: Int
        get() = rooms.size

    val clientCount: Int
        get() = rooms.sumOf { it.clients }
}

object FrnStats {
    fun summary(rooms: List<FrnRoom>): String {
        val groups = FrnGroups.group(rooms)
        val servers = groups.size
        val roomCount = groups.sumOf { it.roomCount }
        val clients = groups.sumOf { it.clientCount }
        return "$servers Server · $roomCount Räume · $clients Teilnehmer"
    }
}

object FrnGroups {
    fun group(rooms: List<FrnRoom>): List<FrnServerGroup> {
        return rooms
            .groupBy { Triple(it.manager, it.server, it.port) }
            .map { (key, entries) ->
                FrnServerGroup(
                    manager = key.first,
                    server = key.second,
                    port = key.third,
                    rooms = entries.sortedBy { it.room.lowercase() }
                )
            }
            .sortedWith(
                compareBy(
                    { it.manager },
                    { it.server.lowercase() },
                    { it.port }
                )
            )
    }
}
