package com.im.openchat.common.network

import okhttp3.Headers
import java.io.IOException

/**
 * 已完整读取并关闭底层连接响应体的 API 响应快照，不持有需要调用方释放的资源。
 *
 * @property statusCode HTTP 状态码，不代表服务端约定的业务状态码。
 * @property headers 服务端返回的 HTTP 响应头。
 * @property body 响应正文；空响应体以空字符串表示，不预设业务 JSON 结构。
 */
data class ApiResponse(
    val statusCode: Int,
    val headers: Headers,
    val body: String,
)

/**
 * 表示非 2xx HTTP 响应，保留响应快照供业务层按协议处理，不自动清除 Token 或重试。
 *
 * @property response 服务端返回的状态码、响应头和正文；异常消息不包含正文或请求凭据。
 */
class ApiHttpException(val response: ApiResponse) : IOException("HTTP ${response.statusCode}")
