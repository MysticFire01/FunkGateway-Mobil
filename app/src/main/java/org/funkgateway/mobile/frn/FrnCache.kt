package org.funkgateway.mobile.frn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object FrnCache {
    var rooms: List<FrnRoom> = emptyList()
    var selectedManager: Int = 0
    var lastUpdated: Long = 0L

    fun save(context: Context) {
        val data = JSONObject()
        data.put("selectedManager", selectedManager)
        data.put("lastUpdated", lastUpdated)

        val entries = JSONArray()
        for (room in rooms) {
            val item = JSONObject()
            item.put("manager", room.manager)
            item.put("server", room.server)
            item.put("port", room.port)
            item.put("room", room.room)
            item.put("clients", room.clients)
            entries.put(item)
        }

        data.put("rooms", entries)

        context.openFileOutput(
            "frn_cache.json",
            Context.MODE_PRIVATE
        ).bufferedWriter().use {
            it.write(data.toString())
        }
    }

    fun load(context: Context) {
        val file = context.getFileStreamPath("frn_cache.json")
        if (!file.exists()) return

        try {
            val data = JSONObject(file.readText())
            val entries = data.getJSONArray("rooms")
            val loaded = mutableListOf<FrnRoom>()

            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                loaded.add(
                    FrnRoom(
                        item.getString("manager"),
                        item.getString("server"),
                        item.getInt("port"),
                        item.getString("room"),
                        item.getInt("clients")
                    )
                )
            }

            rooms = loaded
            selectedManager = data.optInt("selectedManager", 0)
            lastUpdated = data.optLong("lastUpdated", 0L)
        } catch (e: Exception) {
            android.util.Log.e(
                "FrnCache",
                "Gespeicherte Serverliste konnte nicht gelesen werden",
                e
            )
        }
    }
}
