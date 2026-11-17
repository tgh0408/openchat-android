package com.im.openchat.common.network

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

/**
 * 复用单个 OkHttpClient 发起异步 API 请求，统一携带 clientId、X-Device-Id 和 Bearer token。
 *
 * 每个服务地址应复用一个实例；只保留应用的 ContentResolver，不持有 Activity。
 * Token 仅保存在内存中，未登录时不发送 Authorization 请求头；不打印凭据、不建立登录会话。
 *
 * @param context 用于取得应用的 ContentResolver，ANDROID_ID 在请求工作线程读取。
 * @param baseUrl 服务根地址，可包含路径前缀；缺少末尾斜杠时自动补齐，仅允许 HTTPS 或回环 HTTP。
 * Android 使用 Web/Electron 协议中的 PHONE 平台 clientId，不生成或猜测其值。
 */
class ApiClient(
    context: Context,
    baseUrl: String
) {
    private val clientId = "e4d3c2b1a09f8e7d6c5b4a39281706f5"
    private val contentResolver = context.applicationContext.contentResolver
    private val token = AtomicReference("")
    // 缓存当前实例读取到的设备标识摘要；首次请求时读取，后续请求复用内存值，不写入磁盘。
    private val deviceId: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readDeviceId()
    }

    /** 比较已接听通知中的平台和设备摘要，不对外暴露本机标识。 */
    internal fun matchesDevice(terminal: String, id: String): Boolean =
        terminal == "PHONE" && id == deviceId

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    // 校验服务根地址并补齐末尾斜杠，返回规范化地址，不修改输入或发起请求。
    private val baseUrl = baseUrl.toHttpUrl().let { url ->
        val localHttp = url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1")
        require(url.isHttps || localHttp) { "API base URL must use HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "API base URL must not contain credentials"
        }
        require(url.query == null && url.fragment == null) {
            "API base URL must not contain a query or fragment"
        }
        if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
    }

    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor(::intercept)
        .build()

    // 复用连接池和拦截器；连续 45 秒无字节时超时，允许服务端每 15 秒发送注释心跳。
    private val eventStreamClient by lazy {
        client.newBuilder()
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // 关闭通知复用普通客户端资源，但限制整个请求耗时，避免退出流程长期等待。
    private val eventStreamCloseClient by lazy {
        client.newBuilder().callTimeout(1500, TimeUnit.MILLISECONDS).build()
    }

    private val logoutClient by lazy {
        client.newBuilder().callTimeout(3000, TimeUnit.MILLISECONDS).build()
    }

    private val rtcClient by lazy {
        client.newBuilder().callTimeout(10000, TimeUnit.MILLISECONDS).build()
    }

    /** 呼叫请求固定原授权，迟到的取消/挂断不能借用另一个账号的令牌。 */
    internal suspend fun rtcRequest(method: String, path: String, json: String?, accessToken: String): ApiResponse {
        val body = if (method == "POST") (json ?: "{}").toRequestBody(jsonMediaType) else null
        val request = buildRequest(method, path, body = body).newBuilder()
            .tag(RequestToken::class.java, RequestToken(accessToken))
            .build()
        return rtcClient.newCall(request).awaitResponse()
    }

    /** 固定流请求的认证快照，避免延迟执行的旧连接读取新账号的令牌。 */
    private class RequestToken(val value: String)

    /**
     * 更新后续请求使用的 Token，无返回值；不写入磁盘或修改已发出的请求。
     *
     * @param value 服务端返回的原始 JWT；null 或空字符串用于退出登录。
     */
    fun updateToken(value: String?) {
        token.set(value.orEmpty())
    }

    /**
     * 发起 GET 请求并读取文本响应，网络读写在 OkHttp 工作线程进行。
     *
     * @param path 相对于服务根地址的接口路径，解析后必须仍位于该根地址下。
     * @param query 查询参数，由 OkHttp 编码。
     * @param headers 本次额外请求头，不能覆盖三个公共请求头。
     * @return 已读取并关闭响应体的快照；失败与取消规则同 [request]。
     */
    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = request("GET", path, query = query, headers = headers)

    /**
     * 以 application/json 发起 POST 请求，传入字符串必须已按业务协议序列化。
     *
     * @param path 接口路径，解析后必须仍位于服务根地址下。
     * @param json JSON 正文，可使用 Android 自带的 JSONObject 或 JSONArray 构造后转成字符串。
     * @param query 查询参数，由 OkHttp 编码。
     * @param headers 本次额外请求头，不能覆盖三个公共请求头。
     * @return 已读取并关闭响应体的快照；失败与取消规则同 [request]。
     */
    suspend fun postJson(
        path: String,
        json: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = request(
        method = "POST",
        path = path,
        query = query,
        body = json.toRequestBody(jsonMediaType),
        headers = headers,
    )

    /**
     * 以 application/x-www-form-urlencoded 发起 POST 请求，不手动拼接或编码表单字符串。
     *
     * @param path 接口路径，解析后必须仍位于服务根地址下。
     * @param fields 原始表单字段，由 OkHttp 编码后放入请求体。
     * @param query 查询参数，由 OkHttp 编码。
     * @param headers 本次额外请求头，不能覆盖三个公共请求头。
     * @return 已读取并关闭响应体的快照；失败与取消规则同 [request]。
     */
    suspend fun postForm(
        path: String,
        fields: Map<String, String>,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val body = FormBody.Builder()
        for ((name, value) in fields) {
            body.add(name, value)
        }
        return request("POST", path, query, body.build(), headers)
    }

    /**
     * 统一发起请求，支持 PUT、PATCH、DELETE 及 OkHttp 的表单、JSON、MultipartBody 等请求体。
     *
     * @param method 大写 HTTP 方法；方法和请求体的组合遵循 OkHttp 约束。
     * @param path 相对于服务根地址解析的路径，不允许跨源、越过根路径或包含 URL 凭据及片段。
     * @param query 查询参数，与路径内已有的查询参数合并，由 OkHttp 编码。
     * @param body 请求体；GET、HEAD 等不允许请求体的方法应传 null。
     * @param headers 本次额外请求头，公共请求头始终由拦截器设置。
     * @return 含状态码、响应头和完整正文的 2xx 响应快照，204 等空响应返回空正文。
     * @throws ApiHttpException 非 2xx 响应，包括被禁止自动跟随的重定向。
     * @throws IOException 网络、超时、设备标识读取或响应读取失败。
     * @throws IllegalArgumentException 地址、请求头或方法与请求体组合不合法。
     *
     * 无阻塞网络读写；协程取消时取消对应 Call 并传播取消异常。
     * 不增加业务重试、刷新 Token、识别业务状态码或持久化响应；写接口仍需服务端保证幂等。
     */
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = client.newCall(buildRequest(method, path, query, body, headers)).awaitResponse()

    /**
     * 使用固定令牌异步建立事件流，协议解析交给 OkHttp SSE，无业务返回值解析。
     *
     * @param path 事件流接口的相对路径，沿用普通 API 的同源和根路径限制。
     * @param accessToken 本次连接所属账号的原始 JWT，不读取后续更新的内存令牌。
     * @param query 建连参数，由 OkHttp 编码。
     * @param headers SSE 专用请求头，不能覆盖公共身份头。
     * @param listener 在 OkHttp 工作线程接收协议回调，不应在其中直接修改 UI。
     * @return 已启动的事件源，调用方必须在取消或释放时调用 cancel。
     */
    internal fun openEventStream(
        path: String,
        accessToken: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        listener: EventSourceListener,
    ): EventSource {
        val request = buildRequest("GET", path, query, headers = headers)
            .newBuilder()
            .tag(RequestToken::class.java, RequestToken(accessToken))
            .build()
        return EventSources.createFactory(eventStreamClient).newEventSource(request, listener)
    }

    /**
     * 使用原连接的令牌发送有时限的关闭通知，不改动普通 API 客户端的当前令牌。
     *
     * @param path 服务端事件流关闭接口路径。
     * @param accessToken 待关闭连接使用的原始 JWT。
     * @return 已关闭底层响应的快照；HTTP、网络错误及取消由调用方决定如何反馈。
     * 发起异步 GET，请求全程最多等待 1500 毫秒；协程取消同时取消请求。
     */
    internal suspend fun closeEventStream(path: String, accessToken: String): ApiResponse {
        val request = buildRequest("GET", path)
            .newBuilder()
            .tag(RequestToken::class.java, RequestToken(accessToken))
            .build()
        return eventStreamCloseClient.newCall(request).awaitResponse()
    }

    /** 退出只携带原授权快照；普通请求的令牌已清空或变化也不影响本次撤销。 */
    internal suspend fun logout(accessToken: String): ApiResponse {
        val request = buildRequest(
            "POST", "/capi/user/loginOut", body = ByteArray(0).toRequestBody(null),
        ).newBuilder()
            .tag(RequestToken::class.java, RequestToken(accessToken))
            .build()
        return logoutClient.newCall(request).awaitResponse()
    }

    /**
     * 校验目标地址并构造请求，不发起网络或读取设备标识。
     *
     * @param method HTTP 方法，方法与正文组合遵循 OkHttp 约束。
     * @param path 相对接口路径，不允许跨源或越过根路径。
     * @param query 待编码的查询参数。
     * @param body 可选请求体。
     * @param headers 额外请求头，公共身份头仍由拦截器覆盖。
     * @return 可交给普通 API 或 SSE 客户端执行的请求，无外部副作用。
     */
    private fun buildRequest(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): Request {
        val url = requireNotNull(baseUrl.resolve(path)) { "Invalid API path" }
        require(
            url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port &&
                url.encodedPath.startsWith(baseUrl.encodedPath),
        ) { "API path must remain within the configured base URL" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "API path must not contain credentials or a fragment"
        }

        val urlBuilder = url.newBuilder()
        for ((name, value) in query) {
            urlBuilder.addQueryParameter(name, value)
        }
        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .method(method, body)
        for ((name, value) in headers) {
            builder.header(name, value)
        }
        return builder.build()
    }

    /**
     * 在 OkHttp 工作线程附加当前公共请求头并继续请求，无业务返回值解析。
     *
     * @param chain 当前调用的拦截器链，额外请求头中的同名凭据会被覆盖或移除。
     * @return 下游返回的原始响应，交由异步回调读取和关闭。
     * @throws IOException 无法读取有效设备标识或下游请求失败。
     */
    private fun intercept(chain: Interceptor.Chain): Response {
        val accessToken = chain.request().tag(RequestToken::class.java)?.value ?: token.get()
        val request = chain.request().newBuilder()
            .header("clientId", clientId)
            .header("X-Device-Id", deviceId)
            .removeHeader("Authorization")
            .apply {
                // 将认证快照或当前令牌写入请求；无令牌时不保留调用方传入的授权头。
                accessToken.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()
        return chain.proceed(request)
    }

    /**
     * 读取并 SHA-256 摘要化 Settings.Secure.ANDROID_ID，不生成替代设备号、不写入本机存储。
     *
     * @return 当前应用设备标识的 64 位小写十六进制摘要，仅在请求工作线程调用。
     * @throws IOException 系统拒绝读取或返回空标识，阻止发送缺少设备标识的请求。
     */
    @SuppressLint("HardwareIds")
    private fun readDeviceId(): String {
        val value = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } catch (exception: SecurityException) {
            throw IOException("Unable to read ANDROID_ID", exception)
        }
        if (value.isNullOrBlank()) {
            throw IOException("ANDROID_ID is unavailable")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * 将单次 OkHttp 回调转换为可取消的挂起调用，不把原始响应交给 UI 持有。
 *
 * @return 已在 OkHttp 工作线程完整读取的响应快照，底层响应始终关闭。
 * @throws IOException 网络或响应读取失败。
 * @throws ApiHttpException 服务端返回非 2xx 响应。
 *
 * 启动异步请求；协程取消时取消此 Call，取消后的回调不再读取正文。
 */
private suspend fun Call.awaitResponse(): ApiResponse =
    // 注册当前 Call 的单次异步回调，将结果或异常交回挂起协程，不阻塞调用线程。
    suspendCancellableCoroutine { continuation ->
        // 接收协程取消原因并取消当前网络调用，无返回值，不影响其他请求。
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            /**
             * 将网络错误传回挂起协程，无返回值，不转换或吞掉协程取消。
             *
             * @param call 已失败的调用。
             * @param e OkHttp 报告的网络、超时或取消异常。
             */
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            /**
             * 读取并关闭响应，将 HTTP 错误或响应快照传回协程，无返回值。
             *
             * @param call 当前完成连接的调用。
             * @param response 需要读取并关闭的原始响应，取消后也必须关闭。
             */
            override fun onResponse(call: Call, response: Response) {
                try {
                    // 消费当前响应并关闭资源；已取消则仅关闭，不再读取或恢复协程。
                    val result = response.use {
                        if (!continuation.isActive) return
                        val snapshot = ApiResponse(it.code, it.headers, it.body.string())
                        if (!it.isSuccessful) throw ApiHttpException(snapshot)
                        snapshot
                    }
                    continuation.resumeWith(Result.success(result))
                } catch (exception: IOException) {
                    continuation.resumeWithException(exception)
                }
            }
        })
    }
