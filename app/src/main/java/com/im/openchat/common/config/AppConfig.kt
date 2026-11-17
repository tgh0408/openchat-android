package com.im.openchat.common.config

/**
 * 集中维护应用默认配置，不覆盖用户在本地保存的设置。
 */
internal object AppConfig {
    /** 未设置服务器地址时使用的默认 API 根地址。 */
    const val DEFAULT_SERVER_URL = "https://openvpn.sale"

    /** 聊天时间展示与服务端保持一致，不受设备默认 UTC 时区影响。 */
    const val CHAT_TIME_ZONE_ID = "Asia/Shanghai"

    /** 共用服务端 spring.jackson 当前 Date 文本格式及其明确约定的时区。 */
    const val SERVER_DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
    const val SERVER_TIME_ZONE_ID = "Asia/Shanghai"

    /** 应用字体相对于系统字号的默认缩放比例。 */
    const val DEFAULT_FONT_SCALE = 1.0f

    /** 字体设置允许的最小缩放比例。 */
    const val MIN_FONT_SCALE = 0.85f

    /** 字体设置允许的最大缩放比例。 */
    const val MAX_FONT_SCALE = 1.3f
}
