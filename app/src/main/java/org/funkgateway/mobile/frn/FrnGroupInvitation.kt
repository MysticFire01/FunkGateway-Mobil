package org.funkgateway.mobile.frn

data class FrnGroupInvitation(
    val invitationId: String,
    val groupId: String,
    val groupName: String,
    val shortCode: String,
    val server: String,
    val room: String,
    val senderId: String,
    val senderName: String,
    val hostId: String
)
