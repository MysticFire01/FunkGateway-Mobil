package org.funkgateway.mobile

import android.util.Log
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protobuf.Mumble

class MumbleRegisteredUsers {

    data class RegisteredUser(
        val id: Int,
        val name: String,
        val lastSeen: String,
        val lastChannel: Int?
    )

    var onUsersReceived: ((List<RegisteredUser>) -> Unit)? = null

    private var connection: HumlaConnection? = null

    private val listener = object : HumlaTCPMessageListener.Stub() {
        override fun messageUserList(message: Mumble.UserList) {
            val users = message.usersList.map { user ->
                RegisteredUser(
                    id = user.userId,
                    name = user.name,
                    lastSeen = if (user.hasLastSeen()) user.lastSeen else "",
                    lastChannel = if (user.hasLastChannel()) user.lastChannel else null
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            Log.i("FGW-Mumble", "Registrierte Benutzer empfangen: ${users.size}")
            onUsersReceived?.invoke(users)
        }
    }

    fun attach(newConnection: HumlaConnection) {
        detach()
        connection = newConnection
        newConnection.addTCPMessageHandlers(listener)
    }

    fun detach() {
        connection?.removeTCPMessageHandler(listener)
        connection = null
    }


    fun unregisterUser(userId: Int): Boolean {
        if (userId <= 0) return false
        val active = connection ?: return false

        return try {
            val user = Mumble.UserList.User.newBuilder()
                .setUserId(userId)
                .build()

            val message = Mumble.UserList.newBuilder()
                .addUsers(user)
                .build()

            active.sendTCPMessage(
                message,
                HumlaTCPMessageType.UserList
            )
            Log.i("FGW-Mumble", "Deregistrierung angefordert: ID $userId")
            true
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Deregistrierung fehlgeschlagen", e)
            false
        }
    }

    fun requestUsers(): Boolean {
        val active = connection ?: return false
        return try {
            active.sendTCPMessage(
                Mumble.UserList.newBuilder().build(),
                HumlaTCPMessageType.UserList
            )
            true
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Benutzerliste konnte nicht angefordert werden", e)
            false
        }
    }
}
