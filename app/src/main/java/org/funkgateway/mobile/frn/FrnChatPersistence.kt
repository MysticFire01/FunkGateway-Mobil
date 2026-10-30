package org.funkgateway.mobile.frn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object FrnChatPersistence {
    private const val FILE_NAME = "frn_private_chat_history.json"
    private const val MAX_SAVED = 300

    fun load(context: Context): List<FrnChatStore.Entry> {
        val file = context.getFileStreamPath(FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val entries = mutableListOf<FrnChatStore.Entry>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val senderId = item.optString("senderId")
                val message = item.optString("message")

                if (senderId.isBlank() || message.isBlank()) continue

                entries.add(
                    FrnChatStore.Entry(
                        time = item.optLong("time"),
                        sender = item.optString("sender", "FRN"),
                        senderId = senderId,
                        message = message,
                        privateMessage = true,
                        outgoing = item.optBoolean("outgoing")
                    )
                )
            }

            entries.takeLast(MAX_SAVED)
        } catch (e: Exception) {
            android.util.Log.w(
                "FunkGateway-Chat",
                "Privatchat-Verlauf konnte nicht gelesen werden.",
                e
            )
            emptyList()
        }
    }

    fun save(context: Context, entries: List<FrnChatStore.Entry>) {
        val array = JSONArray()

        entries.filter { it.privateMessage }
            .takeLast(MAX_SAVED)
            .forEach { entry ->
                array.put(JSONObject().apply {
                    put("time", entry.time)
                    put("sender", entry.sender)
                    put("senderId", entry.senderId)
                    put("message", entry.message)
                    put("outgoing", entry.outgoing)
                })
            }

        context.openFileOutput(
            FILE_NAME,
            Context.MODE_PRIVATE
        ).bufferedWriter(Charsets.UTF_8).use {
            it.write(array.toString())
        }
    }
}
