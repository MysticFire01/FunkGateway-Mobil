package org.funkgateway.mobile.frn

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object FrnParticipantDirectory {
    private const val FILE_NAME = "frn_participants.json"
    private const val MAX_ENTRIES = 1000

    fun load(context: Context): Map<String, String> {
        val file = context.getFileStreamPath(FILE_NAME)
        if (!file.exists()) return emptyMap()

        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val array = root.optJSONArray("participants") ?: JSONArray()
            val result = linkedMapOf<String, String>()

            for (i in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name").trim()

                val validId = try {
                    UUID.fromString(id).toString().lowercase()
                } catch (_: IllegalArgumentException) {
                    continue
                }

                if (name.isNotBlank()) {
                    result[validId] = name.take(128)
                }
            }

            result
        } catch (e: Exception) {
            Log.w("FunkGateway-FRN", "Teilnehmerverzeichnis nicht lesbar", e)
            emptyMap()
        }
    }

    fun save(context: Context, names: Map<String, String>) {
        val participants = JSONArray()

        names.entries.toList().takeLast(MAX_ENTRIES).forEach { (id, name) ->
            participants.put(JSONObject().apply {
                put("id", id)
                put("name", name)
            })
        }

        val root = JSONObject().apply {
            put("version", 1)
            put("participants", participants)
        }

        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)
            .bufferedWriter(Charsets.UTF_8).use {
                it.write(root.toString())
            }
    }
}
