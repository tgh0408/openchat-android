package com.im.openchat.me.data

/** 当前用户的本地个人资料、主题和通知偏好。 */
data class Profile(
    val name: String = "未登录",
    val bio: String = "",
    val darkMode: Boolean = false,
    val notifications: Boolean = true,
    val userId: String = "",
    val avatar: String = "",
)
