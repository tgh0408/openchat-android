package com.im.openchat.address.data

import kotlinx.serialization.Serializable

/** 好友关系仅驻留本次授权，不将备注写进公开 contacts.nickname。 */
@Serializable
internal data class DirectoryFriend(
    val uid: Long,
    val roomId: Long? = null,
    val name: String,
    val remark: String? = null,
    val displayName: String,
    val indexLetter: String,
)

/** 群列表独立于会话列表，不能由历史会话推断当前成员关系。 */
@Serializable
internal data class DirectoryGroup(
    val groupId: Long,
    val roomId: Long,
    val name: String,
    val memberCount: Int,
    val avatarUidList: List<Long>? = null,
)

@Serializable
internal data class DirectoryFriendPage(
    val cursor: String? = null,
    val isFirst: Boolean,
    val isLast: Boolean,
    val list: List<DirectoryFriend>,
)

/** 与 PC 申请方向一致，发送方不能审批自己的申请。 */
@Serializable
internal enum class ApplicationDirection { RECEIVED, SENT }

internal object ApplicationStatus {
    const val PENDING = 1
    const val ACCEPTED = 2
    const val REJECTED = 3
}

@Serializable
internal data class ApplicationMessage(val uid: Long, val msg: String)

@Serializable
internal data class FriendApplication(
    val applyId: Long,
    val uid: Long,
    val peerUid: Long,
    val direction: ApplicationDirection,
    val name: String,
    val msg: List<ApplicationMessage>,
    val status: Int,
    val type: Int,
) {
    /** 返回申请方最新说明；协议校验保证该消息存在，无副作用。 */
    val message: String get() = msg.last { it.uid == uid }.msg
    /** 仅收到的待处理申请可显示验证操作，最终权限仍由后端确认。 */
    val canApprove: Boolean get() = direction == ApplicationDirection.RECEIVED && status == ApplicationStatus.PENDING
}

@Serializable
internal data class FriendApplicationPage(val list: List<FriendApplication>, val isLast: Boolean)

@Serializable
internal data class ApplicationUnread(val uid: Long, val applyStatus: Int)

@Serializable
internal data class DirectoryProfile(
    val uid: Long,
    val name: String,
    val roomId: Long? = null,
    val remark: String? = null,
    val avatar: String? = null,
    val personalitySignature: String? = null,
    val sex: Int? = null,
    val blacklisted: Boolean = false,
)

@Serializable
internal data class ApplicationResult(
    val status: String,
    val uid: Long,
    val roomId: Long,
    val indexLetter: String,
)

/** 搜索后的身份通过真实好友列表确认，不能以添加权限字段推断关系。 */
internal data class DirectorySearchResult(val profile: DirectoryProfile, val friend: DirectoryFriend?, val self: Boolean)

/** 保留服务端页内顺序，只对字母组排序，# 放最后。 */
internal fun groupDirectoryFriends(friends: List<DirectoryFriend>, query: String): Map<String, List<DirectoryFriend>> {
    val term = query.trim()
    return friends.filter {
        term.isEmpty() || listOf(it.displayName, it.name, it.remark.orEmpty(), it.uid.toString()).any { text ->
            text.contains(term, ignoreCase = true)
        }
    }.groupBy { it.indexLetter }
        .toSortedMap(compareBy<String> { it == "#" }.thenBy { it })
}

/** 按收发方向展示申请状态，不把未读或 HTTP 请求完成当作已添加。 */
internal fun applicationStatusLabel(application: FriendApplication): String = when (application.status) {
    ApplicationStatus.PENDING -> if (application.direction == ApplicationDirection.SENT) "等待对方通过" else "等待验证"
    ApplicationStatus.ACCEPTED -> "已添加"
    else -> if (application.direction == ApplicationDirection.SENT) "对方已拒绝" else "已拒绝"
}
