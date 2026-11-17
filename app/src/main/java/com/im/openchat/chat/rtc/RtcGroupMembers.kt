package com.im.openchat.chat.rtc

internal data class RtcGroupMemberTile(val uid: String, val waiting: Boolean)

/** Presentation timeouts do not change invitations; the layout applies its own visible limit. */
internal fun rtcGroupMemberTiles(
    target: RtcTarget,
    call: RtcCall?,
    ownUid: String,
    entered: Set<String>,
    waitingExpired: Boolean,
): List<RtcGroupMemberTile> {
    val order = if (!target.incoming) {
        listOf(ownUid) + target.inviteeUids
    } else listOf(ownUid) + call?.participants?.keys.orEmpty().toList()
    return order.distinct().mapNotNull { uid ->
        val state = call?.participants?.get(uid)
        if (call != null && state !in setOf(RtcParticipantState.INVITED, RtcParticipantState.ACCEPTED))
            return@mapNotNull null
        val waiting = uid !in entered
        if (waiting && waitingExpired && uid != ownUid) null else RtcGroupMemberTile(uid, waiting)
    }.sortedBy { member ->
        when {
            member.uid == ownUid -> 0
            !member.waiting -> 1
            else -> 2
        }
    }
}
