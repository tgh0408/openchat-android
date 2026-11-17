package com.im.openchat.address.data

/** 仅为当前授权内的导航参数；来源房间不是发消息目标，不代表好友关系。 */
internal data class UserProfileTarget(
    val uid: Long,
    val name: String = "",
    val avatar: String = "",
    val sourceRoomId: Long? = null,
    val friend: DirectoryFriend? = null,
) {
    init {
        require(uid > 0 && (sourceRoomId == null || sourceRoomId > 0))
        require(friend == null || friend.uid == uid)
    }
}
