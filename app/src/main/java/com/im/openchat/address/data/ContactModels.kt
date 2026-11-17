package com.im.openchat.address.data

import com.im.openchat.common.data.ChatData

/** 通讯录联系人或群组的本地资料，也用于表示好友申请人。 */
data class Contact(
    val id: String,
    val name: String,
    val role: String,
    val avatar: String = "",
    val starred: Boolean = false,
    val group: Boolean = false,
    val updatedAt: String = "",
)

/**
 * 接受本地好友申请，将申请人加入通讯录并移除申请。
 *
 * @param id 申请人的联系人标识。
 * @return 更新后的数据；申请不存在时返回原数据，不发起网络请求或写入存储。
 */
fun ChatData.accept(id: String): ChatData {
    val contact = requests.firstOrNull { it.id == id } ?: return this
    return copy(
        contacts = if (contacts.any { it.id == id }) contacts else contacts + contact,
        requests = requests.filterNot { it.id == id },
    )
}
