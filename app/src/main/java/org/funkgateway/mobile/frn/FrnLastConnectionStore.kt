package org.funkgateway.mobile.frn

import android.content.Context

/**
 * Speichert den zuletzt erfolgreich verbundenen FRN-Raum.
 * Zugangsdaten bleiben ausschließlich in den vorhandenen Profilen.
 */
object FrnLastConnectionStore {
    private const val PREFS = "frn_last_connection"

    data class LastConnection(
        val room: FrnRoom,
        val profileId: String
    )

    fun save(context: Context, room: FrnRoom, profileId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("manager", room.manager)
            .putString("server", room.server)
            .putInt("port", room.port)
            .putString("room", room.room)
            .putString("profile_id", profileId)
            .apply()
    }

    fun load(context: Context): LastConnection? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val server = prefs.getString("server", null)
            ?.takeIf { it.isNotBlank() } ?: return null
        val roomName = prefs.getString("room", null)
            ?.takeIf { it.isNotBlank() } ?: return null
        val port = prefs.getInt("port", 0)
        if (port !in 1..65535) return null

        val profileId = prefs.getString("profile_id", null)
            ?.takeIf { it.isNotBlank() } ?: return null

        return LastConnection(
            room = FrnRoom(
                manager = prefs.getString("manager", "") ?: "",
                server = server,
                port = port,
                room = roomName,
                clients = 0
            ),
            profileId = profileId
        )
    }
}
