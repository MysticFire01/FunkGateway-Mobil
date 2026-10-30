package org.funkgateway.mobile.frn

import java.util.UUID

data class FrnChatGroup(
    val id: String,
    var name: String,
    val participants: LinkedHashMap<String, String>,
    var shortCode: String = "",
    val server: String = "",
    val room: String = "",
    var hostId: String = "",
    var hostCallsign: String = "",
    var hostFrnId: String = "",
    val gatewayMemberIds: MutableSet<String> = linkedSetOf(),
    var hostToken: String = UUID.randomUUID().toString(),
    var hostVersion: Long = 1L,
    var active: Boolean = true,
    var pendingTransferId: String = "",
    var pendingSuccessorFrnId: String = "",
    var pendingSuccessorGatewayId: String = "",
    var hostTransferLocked: Boolean = false,
    var hostTransferPhase: String = "NONE"
)

object FrnChatGroups {
    private val groups = linkedMapOf<String, FrnChatGroup>()

    private var revision: Long = 0L

    @Synchronized
    fun currentRevision(): Long = revision

    @Synchronized
    fun create(
        contacts: List<FrnChatContact>,
        server: String = "",
        room: String = "",
        creatorId: String = "",
        creatorCallsign: String = "",
        initialMembers: List<FrnChatContact> = contacts
    ): FrnChatGroup {
        require(contacts.size >= 2) {
            "Mindestens zwei Teilnehmer erforderlich."
        }

        val id = UUID.randomUUID().toString()
        val number = groups.size + 1
        val usedCodes = groups.values
            .filter {
                it.server.equals(server, ignoreCase = true) &&
                it.room.equals(room, ignoreCase = true)
            }
            .map { it.shortCode }
            .toSet()

        val shortCode = (1..10)
            .map { "G$it" }
            .firstOrNull { it !in usedCodes }
            ?: error("Alle Gruppenkennungen G1 bis G10 sind belegt.")

        val group = FrnChatGroup(
            id = id,
            name = "Gruppe $number",
            participants = LinkedHashMap<String, String>().apply {
                initialMembers.forEach { contact ->
                    put(contact.id, contact.name)
                }
            },
            shortCode = shortCode,
            server = server,
            room = room,
            hostId = creatorId,
            hostCallsign = creatorCallsign,
            hostFrnId = ""
        )

        groups[id] = group
        revision++
        return group
    }

    @Synchronized
    fun usedCodes(server: String, room: String): Set<String> =
        groups.values.filter {
            it.server.equals(server, ignoreCase = true) &&
            it.room.equals(room, ignoreCase = true)
        }.map { it.shortCode }.toSet()

    @Synchronized
    fun isHost(groupId: String, installationId: String): Boolean {
        val group = groups[groupId] ?: return false
        return group.active &&
            !group.hostTransferLocked &&
            group.hostTransferPhase in setOf("NONE", "OFFERED", "ACCEPTED") &&
            installationId.isNotBlank() &&
            group.hostId.isNotBlank() &&
            group.hostId == installationId
    }

    @Synchronized
    fun beginHostTransfer(
        groupId: String,
        installationId: String,
        successorFrnId: String
    ): String? {
        if (!isHost(groupId, installationId)) return null
        val group = groups[groupId] ?: return null
        if (group.pendingTransferId.isNotBlank()) return null
        if (successorFrnId.isBlank()) return null
        if (!group.gatewayMemberIds.contains(successorFrnId)) return null
        if (!group.participants.containsKey(successorFrnId)) return null
        if (successorFrnId == group.hostFrnId) return null

        val transferId = UUID.randomUUID().toString()
        group.pendingTransferId = transferId
        group.pendingSuccessorFrnId = successorFrnId
        group.hostTransferPhase = "OFFERED"
        revision++
        return transferId
    }

    @Synchronized
    fun commitHostTransfer(
        groupId: String,
        installationId: String,
        transferId: String
    ): Boolean {
        val group = groups[groupId] ?: return false

        if (!isHost(groupId, installationId)) return false
        if (group.hostTransferPhase != "ACCEPTED") return false
        if (transferId.isBlank() || group.pendingTransferId != transferId) return false
        if (group.pendingSuccessorFrnId.isBlank()) return false
        if (group.pendingSuccessorGatewayId.isBlank()) return false
        if (!group.gatewayMemberIds.contains(group.pendingSuccessorFrnId)) return false
        if (!group.participants.containsKey(group.pendingSuccessorFrnId)) return false

        group.hostTransferLocked = true
        group.hostTransferPhase = "COMMITTING"
        revision++
        return true
    }

    @Synchronized
    fun acceptHostTransfer(
        groupId: String,
        installationId: String,
        transferId: String,
        successorFrnId: String,
        successorGatewayId: String
    ): Boolean {
        if (!isHost(groupId, installationId)) return false
        val group = groups[groupId] ?: return false
        if (group.pendingTransferId != transferId ||
            transferId.isBlank()) return false
        if (group.pendingSuccessorFrnId != successorFrnId ||
            successorFrnId.isBlank()) return false
        if (!group.gatewayMemberIds.contains(successorFrnId) ||
            !group.participants.containsKey(successorFrnId)) return false
        val validGatewayId = try {
            UUID.fromString(successorGatewayId).toString()
                .equals(successorGatewayId, ignoreCase = true)
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!validGatewayId || successorGatewayId == installationId) {
            return false
        }
        if (group.hostTransferPhase !in setOf("OFFERED", "ACCEPTED")) {
            return false
        }
        if (group.pendingSuccessorGatewayId.isNotBlank()) {
            return group.hostTransferPhase == "ACCEPTED" &&
                group.pendingSuccessorGatewayId == successorGatewayId
        }
        if (group.hostTransferPhase != "OFFERED") return false
        group.pendingSuccessorGatewayId = successorGatewayId
        group.hostTransferPhase = "ACCEPTED"
        revision++
        return true
    }

    @Synchronized
    fun addConfirmedMember(
        groupId: String,
        installationId: String,
        memberId: String,
        memberName: String
    ): Boolean {
        if (!isHost(groupId, installationId)) return false
        if (memberId.isBlank() || memberName.isBlank()) return false

        val group = groups[groupId] ?: return false
        if (!group.active || group.participants.containsKey(memberId)) {
            return false
        }

        group.participants[memberId] = memberName
        group.gatewayMemberIds.add(memberId)
        group.hostVersion++
        revision++
        return true
    }

    @Synchronized
    fun hasConfirmedMember(
        groupId: String,
        installationId: String,
        memberId: String
    ): Boolean {
        if (!isHost(groupId, installationId)) return false
        if (memberId.isBlank()) return false

        val group = groups[groupId] ?: return false

        return group.active &&
            group.participants.containsKey(memberId) &&
            group.gatewayMemberIds.contains(memberId)
    }

    @Synchronized
    fun removeMember(
        groupId: String,
        installationId: String,
        memberId: String,
        ownParticipantId: String
    ): Boolean {
        if (!isHost(groupId, installationId)) return false
        if (memberId.isBlank() || memberId == ownParticipantId) return false

        val group = groups[groupId] ?: return false
        val removed = group.participants.remove(memberId) != null
        if (removed) {
            group.gatewayMemberIds.remove(memberId)
            group.hostVersion++
            revision++
        }
        return removed
    }

    @Synchronized
    fun joinInvitation(invitation: FrnGroupInvitation): FrnChatGroup? {
        if (groups.containsKey(invitation.groupId)) return null
        if (invitation.senderId.isBlank()) return null
        if (invitation.hostId.isBlank()) return null
        if (invitation.server.isBlank() || invitation.room.isBlank()) {
            return null
        }
        if (invitation.shortCode in usedCodes(
                invitation.server, invitation.room
            )
        ) return null

        val group = FrnChatGroup(
            id = invitation.groupId,
            name = invitation.groupName,
            participants = linkedMapOf(
                invitation.senderId to invitation.senderName
            ),
            shortCode = invitation.shortCode,
            server = invitation.server,
            room = invitation.room,
            hostId = invitation.hostId,
            hostCallsign = invitation.senderName,
            hostFrnId = invitation.senderId
        )
        groups[group.id] = group
        revision++
        return group
    }

    @Synchronized
    fun getGatewaySyncRecipients(
        groupId: String,
        installationId: String
    ): List<String> {
        if (!isHost(groupId, installationId)) return emptyList()

        val group = groups[groupId] ?: return emptyList()

        return group.gatewayMemberIds
            .filter { it.isNotBlank() && group.participants.containsKey(it) }
            .distinct()
    }

    @Synchronized
    fun createMemberSyncMessages(
        groupId: String,
        installationId: String
    ): List<String> {
        if (!isHost(groupId, installationId)) return emptyList()

        val group = groups[groupId] ?: return emptyList()
        val members = group.participants.toList()

        if (members.isEmpty() || members.size > 100) {
            return emptyList()
        }

        val version = group.hostVersion
        val total = members.size

        return try {
            members.mapIndexed { index, member ->
                FrnGroupMemberSyncParser.encode(
                    group.id,
                    version,
                    index + 1,
                    total,
                    member.first,
                    member.second
                )
            }
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    @Synchronized
    fun applyMemberSync(sync: FrnCompleteMemberSync): Boolean {
        val group = groups[sync.groupId] ?: return false

        if (!group.active) return false
        if (group.hostFrnId.isBlank()) return false
        if (sync.senderId != group.hostFrnId) return false

        if (sync.version <= group.hostVersion) return false
        if (sync.members.isEmpty()) return false

        val updatedMembers = linkedMapOf(
            group.hostFrnId to group.hostCallsign
        )
        sync.members.forEach { (memberId, memberName) ->
            if (memberId != group.hostFrnId) {
                updatedMembers[memberId] = memberName
            }
        }

        group.participants.clear()
        group.participants.putAll(updatedMembers)
        group.hostVersion = sync.version
        revision++

        return true
    }

    @Synchronized
    fun restore(savedGroups: List<FrnChatGroup>): Boolean {
        if (groups.isNotEmpty()) return false

        val restored = linkedMapOf<String, FrnChatGroup>()
        val used = mutableSetOf<String>()

        for (group in savedGroups) {
            if (group.id.isBlank() ||
                group.shortCode !in (1..10).map { "G$it" } ||
                group.server.isBlank() ||
                group.room.isBlank()
            ) return false

            val codeKey = listOf(
                group.server.lowercase(),
                group.room.lowercase(),
                group.shortCode
            ).joinToString("|")

            if (restored.containsKey(group.id) ||
                !used.add(codeKey)
            ) return false

            if (group.hostTransferPhase !in
                setOf("NONE", "OFFERED", "ACCEPTED", "COMMITTING")
            ) {
                group.hostTransferLocked = true
            }
            if (group.hostTransferPhase == "COMMITTING") {
                group.hostTransferLocked = true
            }
            if (group.hostTransferPhase in setOf("OFFERED", "ACCEPTED") &&
                (group.pendingTransferId.isBlank() ||
                 group.pendingSuccessorFrnId.isBlank() ||
                 (group.hostTransferPhase == "ACCEPTED" &&
                  group.pendingSuccessorGatewayId.isBlank()))
            ) {
                group.hostTransferLocked = true
            }
            restored[group.id] = group
        }

        groups.putAll(restored)
        revision++
        return true
    }

    @Synchronized
    fun get(id: String): FrnChatGroup? = groups[id]

    @Synchronized
    fun snapshotWithRevision(): Pair<Long, List<FrnChatGroup>> =
        revision to groups.values.map { group ->
            group.copy(
                participants = LinkedHashMap(group.participants),
                gatewayMemberIds = group.gatewayMemberIds.toMutableSet()
            )
        }

    @Synchronized
    fun snapshot(): List<FrnChatGroup> =
        groups.values.map { group ->
            group.copy(
                participants = LinkedHashMap(group.participants),
                gatewayMemberIds = group.gatewayMemberIds.toMutableSet()
            )
        }

    @Synchronized
    fun all(): List<FrnChatGroup> = groups.values.toList()

    @Synchronized
    fun applyDissolution(
        dissolution: FrnGroupDissolution
    ): Boolean {
        val group = groups[dissolution.groupId] ?: return false

        if (!group.active) return false
        if (group.hostFrnId.isBlank()) return false
        if (dissolution.senderId != group.hostFrnId) return false
        if (dissolution.version < group.hostVersion) return false

        groups.remove(group.id)
        revision++
        return true
    }

    @Synchronized
    fun remove(id: String) {
        if (groups.remove(id) != null) {
            revision++
        }
    }
}
