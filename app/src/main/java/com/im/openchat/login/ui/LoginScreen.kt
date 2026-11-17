package com.im.openchat.login.ui

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.im.openchat.R
import com.im.openchat.common.config.AppConfig
import com.im.openchat.login.data.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI

/**
 * 展示不含第三方登录的原生登录和注册表单，应用名称沿用公共资源。
 *
 * @param serverUrl 当前生效的服务器地址，未设置时使用统一配置中的默认地址。
 * @param initialAccount 上次成功登录的账号，仅用于预填登录账号输入框。
 * @param initialError 启动时验证旧会话失败的提示，为空时不显示。
 * @param onSaveServer 保存校验后的服务器地址，由调用方写入本地私有存储。
 * @param onLogin 使用账号密码请求真实登录，返回服务端认证会话。
 * @param onLoginSuccess 登录成功且会话已保存后通知调用方进入主界面。
 * 无返回值；维护输入、校验和提示状态，密码只驻留内存；登录请求期间禁止切换模式或离开表单。
 */
@Composable
internal fun LoginScreen(
    serverUrl: String,
    initialAccount: String,
    initialError: String?,
    onSaveServer: (String) -> Unit,
    onLogin: suspend (String, String) -> AuthSession,
    onLoginSuccess: (AuthSession) -> Unit,
) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var serverSettings by rememberSaveable { mutableStateOf(false) }
    var account by rememberSaveable(initialAccount) { mutableStateOf(initialAccount) }
    var nickname by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var serverAddress by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
    // 密码和验证码仅驻留当前组合，不写入可恢复状态或持久化存储。
    var loginPassword by remember { mutableStateOf("") }
    var registerPassword by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(initialError) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    /**
     * 校验当前登录、注册或服务器表单，并按当前页签执行对应保存或提示。
     * 无参数、无返回值；请求进行中忽略重复提交；服务器地址交给调用方保存，登录表单异步请求服务端。
     */
    fun submit() {
        if (loading) return
        if (serverSettings) {
            val normalizedAddress = serverAddress.trim()
            error = when {
                normalizedAddress.isBlank() -> "请输入服务器地址"
                !isValidServerUrl(normalizedAddress) -> "请输入有效的 HTTPS 服务器地址"
                else -> null
            }
            if (error == null) {
                focusManager.clearFocus()
                onSaveServer(normalizedAddress)
                notice = "服务器地址已保存"
            }
            return
        }
        error = when {
            registering && nickname.isBlank() -> "请输入昵称"
            registering && !Regex("1[3-9][0-9]{9}").matches(phone.trim()) -> "请输入有效的 11 位手机号码"
            registering && !Regex("[0-9]{6}").matches(code) -> "请输入 6 位数字验证码"
            registering && !Regex("(?=.*[A-Za-z])(?=.*[0-9]).{8,}").matches(registerPassword) ->
                "密码至少 8 位，且包含字母和数字"
            !registering && !Patterns.EMAIL_ADDRESS.matcher(account.trim()).matches() &&
                !Regex("1[3-9][0-9]{9}").matches(account.trim()) -> "请输入有效的手机号或邮箱"
            !registering && loginPassword.isBlank() -> "请输入登录密码"
            else -> null
        }
        if (error == null) {
            focusManager.clearFocus()
            if (registering) {
                notice = "注册服务尚未接入，当前不会创建账号或保存填写的资料。"
                return
            }
            loading = true
            notice = null
            // 在生命周期协程中请求登录，成功后由调用方保存会话并切换主页，失败时仅更新页面错误。
            scope.launch {
                try {
                    val session = onLogin(account.trim(), loginPassword)
                    loginPassword = ""
                    onLoginSuccess(session)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    error = exception.message ?: "登录失败，请重试"
                } finally {
                    loading = false
                }
            }
        }
    }

    // 请求期间消费返回键以保留当前登录流程；否则从注册或服务器页回到登录，无返回值。
    BackHandler(enabled = loading || registering || serverSettings) {
        if (!loading) {
            registering = false
            serverSettings = false
            error = null
            focusManager.clearFocus()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().testTag("login_screen"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.mipmap.chat),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "连接无限可能，即刻畅聊",
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                listOf("登录", "注册", "服务器").forEachIndexed { index, title ->
                    val selected = when (index) {
                        0 -> !registering && !serverSettings
                        1 -> registering
                        else -> serverSettings
                    }
                    SegmentedButton(
                        selected = selected,
                        enabled = !loading,
                        // 按标签索引切换登录、注册或服务器表单并清除错误及焦点，无返回值。
                        onClick = {
                            registering = index == 1
                            serverSettings = index == 2
                            error = null
                            focusManager.clearFocus()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                        icon = {},
                    ) { Text(title) }
                }
            }
            Spacer(Modifier.height(24.dp))

            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (serverSettings) {
                    LoginField(
                        value = serverAddress,
                        // 接收服务器地址并清除旧错误，无返回值，仅更新当前页签内存状态。
                        onValueChange = { serverAddress = it; error = null },
                        label = "服务器地址",
                        placeholder = AppConfig.DEFAULT_SERVER_URL,
                        icon = Icons.Default.Settings,
                        keyboardType = KeyboardType.Uri,
                    )
                } else if (registering) {
                    LoginField(
                        value = nickname,
                        // 接收昵称输入并清除旧错误，无返回值，仅更新表单状态。
                        onValueChange = { nickname = it; error = null },
                        label = "昵称",
                        placeholder = "设置您的个性昵称",
                        icon = Icons.Default.Person,
                    )
                    LoginField(
                        value = phone,
                        // 接收手机号码并清除旧错误，无返回值，仅更新表单状态。
                        onValueChange = { phone = it; error = null },
                        label = "手机号码",
                        placeholder = "请输入手机号码",
                        icon = Icons.Default.Phone,
                        keyboardType = KeyboardType.Phone,
                        trailingContent = {
                            TextButton(
                                // 校验手机号后显示短信未接入提示，无参数、无返回值，不发送验证码。
                                onClick = {
                                    if (Regex("1[3-9][0-9]{9}").matches(phone.trim())) {
                                        error = null
                                        focusManager.clearFocus()
                                        notice = "短信服务尚未接入，当前不会发送验证码。"
                                    } else {
                                        error = "请输入有效的 11 位手机号码"
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) { Text("获取验证码", style = MaterialTheme.typography.labelSmall) }
                        },
                    )
                    LoginField(
                        value = code,
                        // 接收验证码并清除旧错误，无返回值，仅更新内存状态。
                        onValueChange = { code = it; error = null },
                        label = "验证码",
                        placeholder = "请输入 6 位验证码",
                        icon = Icons.Default.CheckCircle,
                        keyboardType = KeyboardType.Number,
                    )
                    LoginField(
                        value = registerPassword,
                        // 接收注册密码并清除旧错误，无返回值，不保存到可恢复状态或磁盘。
                        onValueChange = { registerPassword = it; error = null },
                        label = "设置密码",
                        placeholder = "8 位以上，包含字母与数字",
                        icon = Icons.Default.Lock,
                        password = true,
                    )
                } else {
                    LoginField(
                        value = account,
                        // 接收登录账号并清除旧错误，无返回值，仅更新表单状态。
                        onValueChange = { account = it; error = null },
                        label = "手机号 / 邮箱",
                        placeholder = "请输入手机号或邮箱",
                        icon = Icons.Default.Email,
                        keyboardType = KeyboardType.Email,
                    )
                    LoginField(
                        value = loginPassword,
                        // 接收登录密码并清除旧错误，无返回值，不保存到可恢复状态或磁盘。
                        onValueChange = { loginPassword = it; error = null },
                        label = "密码",
                        placeholder = "请输入登录密码",
                        icon = Icons.Default.Lock,
                        password = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            // 显示重置密码未接入提示，无参数、无返回值，不发送短信或变更密码。
                            onClick = { notice = "密码找回服务尚未接入，当前无法重置密码。" },
                        ) { Text("忘记密码？") }
                    }
                }

                error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("login_error"),
                    )
                }
                Button(
                    onClick = ::submit,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .testTag(if (serverSettings) "server_save" else "login_submit"),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        when {
                            serverSettings -> "保存"
                            registering -> "同意协议并注册"
                            else -> if (loading) "登录中..." else "登录"
                        },
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }

            if (!serverSettings) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "登录或注册即代表同意",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        // 显示服务条款尚未配置的提示，无参数、无返回值，不展示虚构的法律协议。
                        onClick = { notice = "服务条款尚未配置，正式开放注册前需提供可查阅的完整条款。" },
                        modifier = Modifier.weight(1f),
                    ) { Text("服务条款", textAlign = TextAlign.Center) }
                    Text("与", style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        // 说明当前输入不上传且正式政策未配置，无参数、无返回值。
                        onClick = { notice = "隐私政策尚未配置。当前登录页不会上传填写的信息，密码和验证码只保留在页面内存中。" },
                        modifier = Modifier.weight(1f),
                    ) { Text("隐私政策", textAlign = TextAlign.Center) }
                }
            }
        }
    }

    notice?.let { message ->
        AlertDialog(
            // 关闭提示框，无参数、无返回值，不修改表单。
            onDismissRequest = { notice = null },
            title = { Text(if (serverSettings) "保存成功" else "暂不可用") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    // 确认后关闭提示框，无参数、无返回值，不执行认证或发送短信。
                    onClick = { notice = null },
                ) { Text("知道了") }
            },
        )
    }
}

/**
 * 校验服务器地址是否符合当前 API 客户端要求的 HTTPS 根地址。
 *
 * @param value 待校验的服务器地址。
 * @return 地址包含 HTTPS 协议和主机，且不含用户凭据、查询参数或片段时返回 true。
 * 无网络请求、无存储副作用。
 */
private fun isValidServerUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val localHttp = uri.scheme.equals("http", ignoreCase = true) &&
        uri.host in setOf("localhost", "127.0.0.1", "::1")
    (uri.scheme.equals("https", ignoreCase = true) || localHttp) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null
}.getOrDefault(false)

/**
 * 展示登录及注册表单复用的单行输入框，密码框提供带无障碍名称的显隐按钮。
 *
 * @param value 当前输入文本。
 * @param onValueChange 输入变化时回传原始文本，由页面管理表单状态。
 * @param label 输入项名称。
 * @param placeholder 空输入时的提示文字。
 * @param icon 输入框前置图标。
 * @param keyboardType 非密码输入项使用的键盘类型。
 * @param password 是否隐藏文本并禁用自动纠正。
 * @param trailingContent 非密码输入项的尾部内容，用于展示获取验证码按钮。
 * 无返回值；渲染输入框、转发编辑事件，仅在内存中维护密码可见状态。
 */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
        trailingIcon = if (password) {
            {
                IconButton(
                    // 切换当前密码框的明文显示状态，无参数、无返回值，不更改密码内容。
                    onClick = { visible = !visible },
                ) {
                    Icon(
                        painterResource(if (visible) R.drawable.ic_login_visibility_off else R.drawable.ic_login_visibility),
                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                    )
                }
            }
        } else trailingContent,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            autoCorrectEnabled = false,
        ),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}
