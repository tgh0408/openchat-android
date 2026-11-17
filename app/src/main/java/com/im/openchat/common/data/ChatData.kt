package com.im.openchat.common.data

import com.im.openchat.address.data.Contact
import com.im.openchat.chat.data.Conversation
import com.im.openchat.me.data.Profile

/** 本机业务快照所属服务器和账号，防止不同授权之间复用聊天数据。 */
data class ChatDataOwner(val serverUrl: String, val userId: String)

/** 汇总各业务的数据快照，供应用导航和统一存储使用；业务模型与操作由对应业务目录维护。 */
data class ChatData(
    val contacts: List<Contact> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val requests: List<Contact> = emptyList(),
    val profile: Profile = Profile(),
    val owner: ChatDataOwner? = null,
)
