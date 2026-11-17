package com.im.openchat.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.im.openchat.common.data.AvatarProfile
import com.im.openchat.common.data.AvatarRepository
import com.im.openchat.chat.data.Conversation
import com.im.openchat.chat.data.RoomType
import com.im.openchat.common.ui.rememberAvatarProfiles

/**
 * 仅在可见群聊缺少标题时按群头像成员读取公开昵称，头像自身由公共组件懒加载。
 * @param conversation 当前房间及最多九位群头像成员 ID。
 * @param repository 当前授权的头像资料仓库，null 时只显示已有资料。
 * @return 与成员顺序一致的资料状态，失败保留对应占位；离开组合时取消请求，成功资料由仓库缓存。
 */
@Composable
internal fun rememberConversationTitleProfiles(
    conversation: Conversation,
    repository: AvatarRepository?,
): State<List<AvatarProfile?>> {
    val uids = if (conversation.roomType == RoomType.GROUP && conversation.title.isBlank()) {
        conversation.avatarUids
    } else emptyList()
    return rememberAvatarProfiles(uids, repository, conversation.contactId)
}
