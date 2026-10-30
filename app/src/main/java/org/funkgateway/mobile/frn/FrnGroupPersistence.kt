package org.funkgateway.mobile.frn

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject

object FrnGroupPersistence {

    private const val FILE_NAME = "frn_groups.json"
    private const val FORMAT_VERSION = 1

    // Vorläufig deaktiviert. Der Code bleibt für die spätere Entwicklung erhalten.
    private const val HOST_TRANSFER_ENABLED = false

    @Volatile
    private var writingAllowed = false

    @Volatile
    private var transferStorageFailed = false

    @Synchronized
    fun initialize(context: Context): Boolean {
        if (HOST_TRANSFER_ENABLED &&
            FrnHostTransferSafety.hasCriticalTransfer(context)) {
            writingAllowed = false
            transferStorageFailed = true
            android.util.Log.e(
                "FunkGateway-Groups",
                "Kritische Hostübergabe erkannt: " +
                    "Gruppeninitialisierung bis zur Klärung gesperrt."
            )
            return false
        }
        if (transferStorageFailed) {
            android.util.Log.e(
                "FunkGateway-Groups",
                "Initialisierung gesperrt: Hostübergabe-Speicherfehler."
            )
            return false
        }
        // Bereits erfolgreich initialisiert:
        // Activity-Neustarts dürfen die Speicherung nicht deaktivieren.
        if (writingAllowed) {
            return true
        }

        writingAllowed = false
        val file = context.applicationContext
            .getFileStreamPath(FILE_NAME)

        val legacyBackup = java.io.File(file.path + ".bak")
        val newBackup = java.io.File(file.path + ".new")

        if (!file.exists() && !legacyBackup.exists() && !newBackup.exists()) {
            val restored = FrnChatGroups.restore(emptyList())
            writingAllowed = restored
            return restored
        }

        val data = load(context) ?: return false

        if (data.optJSONArray("groups") == null) {
            android.util.Log.w(
                "FunkGateway-Groups",
                "Gruppendatei enthält keine gültige Gruppenliste."
            )
            return false
        }

        val restored = decodeGroups(data)

        if (restored.size != data.getJSONArray("groups").length()) {
            android.util.Log.w(
                "FunkGateway-Groups",
                "Gruppendatei enthält ungültige Einträge."
            )
            return false
        }

        val success = FrnChatGroups.restore(restored)
        writingAllowed = success
        return success
    }

    fun encodeGroups(groups: List<FrnChatGroup>): JSONObject {
        val array = JSONArray()

        groups.forEach { group ->
            val members = JSONArray()
            group.participants.forEach { (id, name) ->
                members.put(JSONObject().apply {
                    put("id", id)
                    put("name", name)
                })
            }

            val gatewayMembers = JSONArray()
            group.gatewayMemberIds.forEach { id ->
                gatewayMembers.put(id)
            }

            array.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("shortCode", group.shortCode)
                put("server", group.server)
                put("room", group.room)
                put("hostId", group.hostId)
                put("hostCallsign", group.hostCallsign)
                put("hostFrnId", group.hostFrnId)
                put("hostToken", group.hostToken)
                put("hostVersion", group.hostVersion)
                put("active", group.active)
                put("pendingTransferId", group.pendingTransferId)
                put("pendingSuccessorFrnId", group.pendingSuccessorFrnId)
                put("pendingSuccessorGatewayId", group.pendingSuccessorGatewayId)
                put("hostTransferLocked", group.hostTransferLocked)
                put("hostTransferPhase", group.hostTransferPhase)
                put("participants", members)
                put("gatewayMemberIds", gatewayMembers)
            })
        }

        return JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("groups", array)
        }
    }

    fun decodeGroups(data: JSONObject): List<FrnChatGroup> {
        if (data.optInt("formatVersion") != FORMAT_VERSION) {
            return emptyList()
        }

        val array = data.optJSONArray("groups") ?: return emptyList()
        val result = mutableListOf<FrnChatGroup>()
        val seenIds = mutableSetOf<String>()
        val usedCodes = mutableSetOf<String>()

        for (i in 0 until minOf(array.length(), 100)) {
            val item = array.optJSONObject(i) ?: continue

            val id = item.optString("id")
            val code = item.optString("shortCode")
            val server = item.optString("server")
            val room = item.optString("room")

            if (runCatching { java.util.UUID.fromString(id) }.isFailure) {
                continue
            }
            if (code !in (1..10).map { "G$it" }) continue
            if (server.isBlank() || room.isBlank()) continue
            if (!seenIds.add(id)) continue

            val codeKey = "$server|$room|$code".lowercase()
            if (!usedCodes.add(codeKey)) continue

            val members = linkedMapOf<String, String>()
            val memberArray = item.optJSONArray("participants")
            if (memberArray != null) {
                for (j in 0 until minOf(memberArray.length(), 100)) {
                    val member = memberArray.optJSONObject(j) ?: continue
                    val memberId = member.optString("id")
                    val memberName = member.optString("name")
                    if (memberId.isNotBlank() && memberName.isNotBlank()) {
                        members[memberId] = memberName
                    }
                }
            }

            val gatewayIds = linkedSetOf<String>()
            val gatewayArray = item.optJSONArray("gatewayMemberIds")
            if (gatewayArray != null) {
                for (j in 0 until minOf(gatewayArray.length(), 100)) {
                    val memberId = gatewayArray.optString(j)
                    if (memberId in members) gatewayIds.add(memberId)
                }
            }

            result.add(
                FrnChatGroup(
                    id = id,
                    name = item.optString("name", code),
                    participants = members,
                    shortCode = code,
                    server = server,
                    room = room,
                    hostId = item.optString("hostId"),
                    hostCallsign = item.optString("hostCallsign"),
                    hostFrnId = item.optString("hostFrnId"),
                    gatewayMemberIds = gatewayIds,
                    hostToken = item.optString("hostToken")
                        .ifBlank { java.util.UUID.randomUUID().toString() },
                    hostVersion = item.optLong("hostVersion", 1L)
                        .coerceAtLeast(1L),
                    active = item.optBoolean("active", true),
                    pendingTransferId = item.optString("pendingTransferId"),
                    pendingSuccessorFrnId = item.optString("pendingSuccessorFrnId"),
                    pendingSuccessorGatewayId = item.optString("pendingSuccessorGatewayId"),
                    hostTransferLocked = item.optBoolean("hostTransferLocked", false),
                    hostTransferPhase = item.optString("hostTransferPhase", "NONE")
                )
            )
        }

        return result
    }

    fun load(context: Context): JSONObject? {
        val file = AtomicFile(
            context.applicationContext.getFileStreamPath(FILE_NAME)
        )

        val legacyBackup = java.io.File(file.baseFile.path + ".bak")
        val newBackup = java.io.File(file.baseFile.path + ".new")

        if (!file.baseFile.exists() &&
            !legacyBackup.exists() &&
            !newBackup.exists()
        ) return null

        return try {
            val bytes = file.openRead().use { it.readBytes() }
            val data = JSONObject(String(bytes, Charsets.UTF_8))

            if (data.optInt("formatVersion") != FORMAT_VERSION) {
                android.util.Log.w(
                    "FunkGateway-Groups",
                    "Unbekannte Version der Gruppendatei."
                )
                null
            } else {
                data
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "FunkGateway-Groups",
                "Gruppendatei konnte nicht gelesen werden.",
                e
            )
            null
        }
    }

    @Synchronized
    fun commitHostTransferPersisted(
        context: Context,
        groupId: String,
        installationId: String,
        transferId: String
    ): Boolean {
        if (!HOST_TRANSFER_ENABLED) return false
        if (!writingAllowed || transferStorageFailed) return false

        if (!FrnHostTransferSafety.markCriticalTransfer(
                context, groupId, transferId
            )) {
            android.util.Log.e(
                "FunkGateway-Groups",
                "Hostübergabe abgebrochen: Sicherheitsvermerk nicht gespeichert."
            )
            return false
        }

        if (!FrnChatGroups.commitHostTransfer(
                groupId, installationId, transferId
            )
        ) return false

        val saved = saveCurrentGroups(context)
        if (!saved) {
            transferStorageFailed = true
            writingAllowed = false
            android.util.Log.e(
                "FunkGateway-Groups",
                "Hostübergabe: Speicherung fehlgeschlagen. " +
                    "Weitere Gruppenspeicherung gesperrt."
            )
        }
        return saved
    }

    @Synchronized
    fun saveCurrentGroups(context: Context): Boolean {
        if (!writingAllowed) {
            android.util.Log.w(
                "FunkGateway-Groups",
                "Speichern gesperrt: Initialisierung fehlgeschlagen."
            )
            return false
        }

        return try {
            repeat(5) {
                val (revision, groups) = FrnChatGroups.snapshotWithRevision()
                val data = encodeGroups(groups)

                if (!save(context, data)) {
                    return false
                }

                if (FrnChatGroups.currentRevision() == revision) {
                    return true
                }
            }

            android.util.Log.w(
                "FunkGateway-Groups",
                "Gruppen während der Speicherung mehrfach verändert."
            )
            false
        } catch (e: Exception) {
            android.util.Log.e(
                "FunkGateway-Groups",
                "Gruppenbestand konnte nicht gespeichert werden.",
                e
            )
            false
        }
    }

    @Synchronized
    fun save(context: Context, data: JSONObject): Boolean {
        if (!writingAllowed) {
            android.util.Log.w(
                "FunkGateway-Groups",
                "Speichern gesperrt: Gruppen wurden nicht erfolgreich geladen."
            )
            return false
        }

        val file = AtomicFile(
            context.applicationContext.getFileStreamPath(FILE_NAME)
        )

        val output = try {
            file.startWrite()
        } catch (e: Exception) {
            android.util.Log.e(
                "FunkGateway-Groups",
                "Gruppendatei konnte nicht geöffnet werden.",
                e
            )
            return false
        }

        return try {
            data.put("formatVersion", FORMAT_VERSION)
            output.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            true
        } catch (e: Exception) {
            file.failWrite(output)
            android.util.Log.e(
                "FunkGateway-Groups",
                "Gruppendatei konnte nicht gespeichert werden.",
                e
            )
            false
        }
    }
}
