# 项目编码规范

- 2026-11-17 账号存储统一采用规范化完整服务器地址 SHA-256 的前 16 位：内容库为 `databases/<16位摘要>/<UID>.sqlite`，附件、视频、上传源、消息图片及临时媒体文件采用 `<16位摘要>/<UID>` 分区。旧目录不迁移、不自动删除，资源内容校验仍保留完整摘要；本条取代下文历史库名规则。详见 `doc/project-modules.md#m02`，仅源码与差异检查，未构建或运行测试。

- LiveKit 地址由密码登录及会话恢复在 globalUid 核验后请求 `GET /capi/rtc/config` 获取，
  读取 `data.serverUrl` 并校验 WSS（仅回环允许 WS），不接收凭据、查询参数或片段。
  地址随绑定服务器的加密 AuthSession 写入既有 DataStore；每次重新进入都刷新，不以缓存跳过认证或配置请求。
  配置请求最多等待三秒；未配置、接口缺失、网络或响应错误时保存空媒体地址，仍正常登录并使用普通聊天。
  通话优先使用当前授权的地址缓存，空缓存使用本次 Token 接口的有效地址；媒体未配置只影响通话。
  HTTP 401/403 及外部取消继续按现有认证流程处理，不吞掉真正的身份失效或取消。
  先部署全部后端实例并保持地址一致；本次不增加业务表、依赖或手填配置，未运行 Android 构建、测试或设备联调。

- 2026-11-14 按用户要求接入认证与内容库加密：`LocalCredentialCipher` 使用 Android Keystore AES-256-GCM，认证会话及账号提示加密后存入既有 DataStore，附加认证数据绑定规范化服务器地址和用途。Room 接入 `net.zetetic:sqlcipher-android:4.20.0`，仅使用服务端核验后的 `clientSqlPassword`；库名为 `<服务器完整地址SHA-256>_<UID>.sqlite`，支持同 UID 多服务器隔离。退出/失效/页面销毁撤销访问并关闭连接，错误密钥不回退明文、不主动删库。旧明文库不转换、不自动删除，旧认证要求重新登录。详见 `doc/project-modules.md#m02`；此次只做源码及配置检查，未同步、构建、运行测试或设备验证。

- 2026-11-12 自动更新改为会话列表顶部提示，提供立即更新、查看更新内容和忽略当前版本；原 SSE 重连入口保留。忽略版本按服务器摘要写入既有 Preferences DataStore，手动检查仍可查看，启动及前台恢复自动检查（最短五分钟），复用原下载/校验/安装流程。Dashboard 从 release 目录元数据解析固定名 APK，后端比较配套 SHA-256 后发布；详见 `doc/project-modules.md#m13`。安卓未构建、测试或设备验证，本条取代下条自动弹窗行为。

- 2026-11-12 安卓更新接入启动自动检查及“设置 → 更多 → 检查更新”，复用 API 和断点下载，私有更新缓存、SHA-256/包信息/签名校验、安装来源授权及系统确认。Manifest 新增 `REQUEST_INSTALL_PACKAGES` 和仅暴露更新缓存的 FileProvider，不新增依赖或业务表；发布与验证边界见 `doc/project-modules.md#m13`。此项取代旧权限清单的缺省描述，未运行 Gradle 或设备验证。

本文件适用于整个仓库。新增或修改代码时必须遵守以下约定；未明确规定的细节遵循相关模块已有的实现方式。

## 关联项目与业务参考

- PC 端项目路径：`F:\openchat\openchat-web`。
- 服务端项目路径：`F:\openchat\openchat-server`，PC 端和 Android 端共用此服务端，不另建 Android 专用服务端。
- 开发 Android 功能或遇到业务问题时，优先阅读 PC 端对应功能的实现，学习并对齐其业务规则、交互流程、状态变化和接口调用方式，不凭空猜测业务逻辑。
- 接口路径、请求与响应字段、鉴权方式、事件协议及错误码等，以共用服务端的实际实现为准；PC 端与服务端实现不一致时，先核对差异，不直接照搬。
- 参考 PC 端的业务逻辑不等于照搬其界面或技术实现；Android 端仍遵循本仓库的原生 Compose、目录划分、状态管理和依赖约定。
- 上述路径用于定位关联项目，不写入应用运行配置或共享构建配置；查阅关联项目不代表可以顺带修改，跨项目变更需先取得用户同意。

## 数据库表结构约定

- 新建或修改数据库表结构前，必须先查阅 PC 端实际表定义及迁移，确保表名、字段名、字段类型与含义、主键、约束、索引及数据存储格式与 PC 端一致，便于后续跨端数据同步；不得自行设计与 PC 端不一致的业务表结构。
- 若某些数据确实必须单独建表，包括 Android 专用的本地状态、偏好或辅助表，必须先向用户说明数据用途、无法复用 PC 端表的原因、拟建表结构及对后续同步的影响，取得用户明确同意后才能创建或编写相关迁移；不得以实现需要为由自行建表。
- 无法确认 PC 端表结构或发现两端存在差异时，先核对并向用户确认处理方案，不凭猜测建表，也不擅自改造已有表或修改 PC 端。

## 当前配置基线

以下内容于 2026-10-30 根据当前工作区核对，包含尚未提交的配置，以便后续开发直接复用。配置文件是实际生效的来源；本清单与文件不一致时先读取文件，不要为了匹配文档而回退配置。确需调整配置时同步更新本文件，不自动追逐最新版本。

### 配置文件索引

| 文件 | 职责 |
| --- | --- |
| `settings.gradle.kts` | 根项目名称、模块、插件仓库、依赖仓库及 JDK 工具链解析插件 |
| `build.gradle.kts` | 根项目插件声明，使用 Version Catalog 别名并设置 `apply false` |
| `app/build.gradle.kts` | Android 构建、SDK、编译目标、资源目录、Room 及依赖作用域 |
| `gradle/libs.versions.toml` | 依赖及主要插件版本和别名的唯一维护入口 |
| `gradle.properties` | Gradle JVM 参数、配置缓存及 Kotlin 代码风格 |
| `gradle/wrapper/gradle-wrapper.properties` | Wrapper 版本、下载地址、校验和及分发缓存位置 |
| `gradle/gradle-daemon-jvm.properties` | Gradle Daemon 的 JDK 版本及各平台工具链下载地址 |
| `local.properties` | 本机 Android SDK 定位等本地配置，不提交或复制其中的绝对路径 |
| `app/src/main/AndroidManifest.xml` | 应用入口、权限、硬件特性、主题、图标及备份配置 |
| `app/src/main/keepRules/rules.keep` | R8 保留规则，目前只有模板注释，无生效的自定义规则 |
| `app/src/main/res/common/xml/backup_rules.xml` | 传统应用备份排除规则 |
| `app/src/main/res/common/xml/data_extraction_rules.xml` | 云备份及设备迁移排除规则 |
| `app/src/main/res/common/values/` | 应用名称、XML 主题及颜色资源 |
| `app/src/main/java/com/im/openchat/common/ui/theme/` | Compose 主题、颜色及排版 |
| `.gitignore`、`app/.gitignore` | 本机配置、缓存、构建产物及敏感文件的忽略规则 |

### 模块与 Android 构建

| 配置项 | 当前值 |
| --- | --- |
| 根项目名称 | `OpenChat` |
| Gradle 模块 | 仅 `:app`；IDE 中的 `OpenChat.app.main`、`unitTest`、`androidTest` 等不是额外 Gradle 模块 |
| `namespace` / `applicationId` | `com.im.openchat` |
| `compileSdk` | `release(37)` |
| `minSdk` / `targetSdk` | `24` / `37` |
| `versionCode` / `versionName` | `1` / `1.0` |
| Java 源码及字节码兼容目标 | `JavaVersion.VERSION_11` |
| Compose | `buildFeatures.compose = true` |
| 设备测试 Runner | `androidx.test.runner.AndroidJUnitRunner` |
| Release 优化 | `optimization.enable = true` |
| Release 优化包范围 | `androidx.**`、`kotlin.**`、`kotlinx.**` |
| Room Schema 输出 | `app/schemas`，由 Room 插件配置 |
| 自定义构建配置 | 当前未声明 product flavors、自定义签名或服务端 BuildConfig 字段 |

主资源集合仅包含 `app/src/main/res/common`、`app/src/main/res/chat`、`app/src/main/res/address`、`app/src/main/res/login`、`app/src/main/res/me`，默认的 `app/src/main/res` 不再作为资源根目录，避免父子目录重复注册。公共、会话、通讯录、登录和个人页资源分别放在对应分组下的 `drawable`、`values` 等标准资源目录中，不重复创建资源模块；这些目录中的资源仍属于同一个 `R` 命名空间，应避免重名冲突。通讯录目录统一命名为 `address`，个人页目录统一命名为 `me`，不再使用 `contacts` 或 `profile` 作为业务目录名。

### Gradle 与 JDK

| 配置项 | 当前值 |
| --- | --- |
| Gradle Wrapper | `9.8.2`，使用 `bin` 分发包 |
| Wrapper 分发地址 | `https://services.gradle.org/distributions/gradle-9.8.2-bin.zip` |
| 分发包 SHA-256 | `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607` |
| Wrapper 网络超时 | `10000` 毫秒 |
| Wrapper 地址校验 | `validateDistributionUrl=true` |
| 分发解压与 ZIP 缓存 | 均位于 `GRADLE_USER_HOME` 下的 `wrapper/dists` |
| Gradle Daemon JDK | `toolchainVersion=25`，按现有 Daemon JVM 配置复用本地匹配的 JDK |
| 工具链解析插件 | `org.gradle.toolchains.foojay-resolver-convention`，版本 `1.0.0` |
| Daemon JVM 参数 | `-Xmx2048m -Dfile.encoding=UTF-8` |
| 配置缓存 | `org.gradle.configuration-cache=true` |
| Kotlin 代码风格 | `kotlin.code.style=official` |
| Gradle 并行开关 | `org.gradle.parallel=true` 目前被注释，项目未显式启用 |

- Gradle 运行所需的 JDK 25 与业务代码的 Java 11 编译目标不是同一概念，不要据此把运行 JDK 改成 11，也不要随意修改编译目标。
- 当前模块未显式应用 `org.jetbrains.kotlin.android` 插件；不要仅因其他项目的模板包含它而重复添加 Kotlin Android 插件。
- `gradle-daemon-jvm.properties` 已含各平台的 Foojay 工具链地址，属于生成配置，不手工改写或无故运行 `updateDaemonJvm`。
- 不通过重新生成 Wrapper、运行 `gradle init`、切换系统 Gradle 或另建 Gradle 工程来解决普通构建问题。
- Android Studio 的 Gradle user home 应与本机 `GRADLE_USER_HOME` 一致，共用已有依赖及 Wrapper 缓存；该目录是缓存目录，不是 Gradle 安装目录。具体路径仅保存在本机 IDE 设置中，不写入项目配置。
- 用户明确要求 IDE 使用本机已安装的同版本 Gradle 时，在被 Git 忽略的 `.idea/gradle.xml` 中同时设置 `distributionType=LOCAL` 和本机安装目录 `myGradleHome`；仅记录安装目录不代表已选用本地分发。此覆盖仅限本机 IDE，命令行仍使用仓库 Wrapper，不将本机路径写入共享构建配置。
- Wrapper 按分发地址匹配 `wrapper/dists` 中的缓存，不会自动复用任意目录中的同版本 ZIP 或解压目录；排查重复下载时先核对 IDE 与命令行的缓存目录、分发地址及对应缓存是否完整，不随意改写分发地址。

### 仓库配置

- 插件解析顺序为 `google()`、`mavenCentral()`、`gradlePluginPortal()`。其中 Google 插件仓库仅接受 `com.android.*`、`com.google.*`、`androidx.*` 匹配组。
- 业务依赖使用 `google()`、`mavenCentral()`；另有 `https://jitpack.io`，通过 `exclusiveContent` 仅解析 `com.github.davidliu:audioswitch`。
- `repositoriesMode` 为 `FAIL_ON_PROJECT_REPOS`，依赖仓库统一维护在 `settings.gradle.kts`，不得在模块中追加 `repositories`。
- JitPack 中的 AudioSwitch 已有专用解析规则；不要为 LiveKit 的依赖重复新增 AudioSwitch 的直接声明或放宽整个 JitPack 的解析范围。
- 不随意增加镜像、`mavenLocal()`、`flatDir` 或远程脚本，不以切换仓库地址作为下载慢或离线失败的默认解决办法。

### 插件版本

除 Foojay 插件在 `settings.gradle.kts` 中声明外，下列插件均通过 `gradle/libs.versions.toml` 管理，并由根项目和 `:app` 复用。

| 插件别名 | 插件 ID | 固定版本 |
| --- | --- | --- |
| `libs.plugins.android.application` | `com.android.application` | `9.4.1` |
| `libs.plugins.kotlin.compose` | `org.jetbrains.kotlin.plugin.compose` | `2.2.10` |
| `libs.plugins.ksp` | `com.google.devtools.ksp` | `2.3.12` |
| `libs.plugins.androidx.room` | `androidx.room` | `2.8.5` |

### 已声明依赖与用途

下表列出当前全部直接依赖；传递依赖由 Gradle 解析，不为“补全依赖”而逐个添加直接声明。

| Version Catalog 引用 | Maven 坐标 | 版本 | 当前作用域 / 用途 |
| --- | --- | --- | --- |
| `libs.livekit.android` | `io.livekit:livekit-android` | `2.29.2` | `implementation`，实时音视频 SDK |
| `libs.androidx.room.runtime` | `androidx.room:room-runtime` | `2.8.5` | `implementation`，Room 运行库 |
| `libs.sqlcipher.android` | `net.zetetic:sqlcipher-android` | `4.20.0` | `implementation`，Room 加密驱动；其运行依赖声明 `androidx.sqlite:sqlite:2.7.0` |
| `libs.androidx.room.compiler` | `androidx.room:room-compiler` | `2.8.5` | `ksp`，Room 代码生成 |
| `libs.okhttp.sse` | `com.squareup.okhttp3:okhttp-sse` | `5.3.2` | `implementation`，SSE 及现有 OkHttp 网络能力 |
| `libs.androidx.compose.bom` | `androidx.compose:compose-bom` | `2026.02.01` | 主代码及设备测试均通过 `platform(...)` 引入 |
| `libs.androidx.activity.compose` | `androidx.activity:activity-compose` | `1.8.0` | `implementation`，Activity 与 Compose 集成 |
| `libs.androidx.compose.material3` | `androidx.compose.material3:material3` | BOM 管理 | `implementation`，Material 3 组件 |
| `libs.androidx.compose.material.icons.core` | `androidx.compose.material:material-icons-core` | `1.7.8` | `implementation`，现有 Compose 图标 |
| `libs.androidx.compose.ui` | `androidx.compose.ui:ui` | BOM 管理 | `implementation`，Compose UI |
| `libs.androidx.compose.ui.graphics` | `androidx.compose.ui:ui-graphics` | BOM 管理 | `implementation`，图形 API |
| `libs.androidx.compose.ui.tooling.preview` | `androidx.compose.ui:ui-tooling-preview` | BOM 管理 | `implementation`，预览注解及支持 |
| `libs.androidx.core.ktx` | `androidx.core:core-ktx` | `1.10.1` | `implementation`，Android Kotlin 扩展 |
| `libs.androidx.lifecycle.runtime.ktx` | `androidx.lifecycle:lifecycle-runtime-ktx` | `2.6.1` | `implementation`，生命周期运行支持 |
| `libs.junit` | `junit:junit` | `4.13.2` | `testImplementation`，本地单元测试 |
| `libs.androidx.compose.ui.test.junit4` | `androidx.compose.ui:ui-test-junit4` | BOM 管理 | `androidTestImplementation`，Compose 交互测试 |
| `libs.androidx.espresso.core` | `androidx.test.espresso:espresso-core` | `3.7.0` | `androidTestImplementation`，设备 UI 测试 |
| `libs.androidx.junit` | `androidx.test.ext:junit` | `1.3.0` | `androidTestImplementation`，Android JUnit |
| `libs.androidx.compose.ui.test.manifest` | `androidx.compose.ui:ui-test-manifest` | BOM 管理 | `debugImplementation`，Compose 测试宿主 |
| `libs.androidx.compose.ui.tooling` | `androidx.compose.ui:ui-tooling` | BOM 管理 | `debugImplementation`，调试工具 |

- BOM 管理的 Compose 库不额外指定版本；`material-icons-core` 当前显式使用 `1.7.8`，不要自行改为与 BOM 日期或其他组件版本一致。
- 版本表反映已声明版本，并非本次实际解析出的完整依赖树；缓存中是否齐全需要按需离线验证。
- 已有 Room、LiveKit、OkHttp SSE。会话、消息、联系人、好友申请和个人资料使用 Room；主题、字体缩放、通知偏好、服务器地址和认证信息仍使用 Preferences DataStore。不要重复添加 Room/KSP，也不要为了网络、JSON、图标或图片展示顺带引入 Retrofit、Ktor、Gson、Moshi、Coil、Glide 或扩展图标包。
- 确实需要增加依赖时，先说明现有能力不能满足的具体原因、拟新增坐标及版本，取得用户同意后通过 Version Catalog 添加，不使用动态版本、`+` 或 `SNAPSHOT`。

### 应用运行配置

- 启动入口为 `.MainActivity`，`exported=true`，声明 `MAIN` / `LAUNCHER`，软键盘模式为 `adjustResize`；Activity 开启 edge-to-edge 并渲染 `OpenChatApp()`。
- 已声明权限：`INTERNET`、`CAMERA`、`RECORD_AUDIO`、`MODIFY_AUDIO_SETTINGS`、`ACCESS_NETWORK_STATE`。权限声明不等于已经完成运行时授权或真实通话接入。
- 摄像头、自动对焦、麦克风硬件特性均为 `required=false`。
- 应用名称为 `OpenChat`，XML 主题为 `Theme.OpenChat`，继承 `android:Theme.Material.Light.NoActionBar`，支持 RTL。
- Manifest 当前引用 `@mipmap/ic_launcher` 和 `@mipmap/ic_launcher_round`。工作区另有 `openchat` 系列图标资源，但尚未由 Manifest 选用，不要混淆资源存在与实际配置。
- `android:allowBackup=true`，但 `files/datastore/openchat_local.preferences_pb` 和数据库目录已同时排除传统备份、云备份及设备迁移；不要在新增存储时丢失聊天数据保护规则。
- Compose 使用已有 `OpenChatTheme`、颜色及 `Typography`，动态配色默认关闭，应用通过本地 `profile.darkMode` 控制深色模式；现有排版所有字距为 `0.sp`。

### 状态、存储与业务边界

- `common/ui/OpenChatApp.kt` 使用 Compose 状态和 `rememberSaveable` 管理当前页面；导航沿用已有实现，没有配置 Navigation Compose、Hilt 或其他状态管理框架。
- `chat/data/ChatModels.kt` 定义消息、会话、示例会话及会话搜索、打开、发送、置顶、免打扰和删除操作；操作以 `ChatData` 扩展函数保留原有调用形式。
- `address/data/ContactModels.kt` 定义联系人、示例通讯录、示例好友申请及接受申请操作；`me/data/Profile.kt` 定义个人资料、主题和通知偏好。
- `common/data/ChatData.kt` 仅汇总各业务的数据快照和示例数据；业务模型即使被其他页面引用，也仍由所属业务目录维护。
- `common/data/ChatStore.kt` 使用私有 Preferences DataStore 保存偏好及 Keystore 加密后的认证会话、账号提示；认证 JSON 继续使用 `org.json`，禁止明文写入。内容库由 Room + SQLCipher 管理，以完整规范化服务器地址 SHA-256 的前 16 位作为目录，库名为 `<UID>.sqlite`，不以域名或 UID 单独作为隔离边界。数据库密码必须来自本次服务端核验，不放入 `ChatDataOwner` 或数据库文件名。
- 当前个人资料使用 `profile` 字段和 JSON 键，业务目录名为 `me`；数据格式需要调整时遵循下文“数据变更与历史兼容”，无需保留旧格式。
- Room 数据库为空时加载空业务快照；不要把示例数据当成服务端返回的数据。
- 本地自建会话发送只保存本机；真实会话普通文本通过 `POST /capi/chat/msg` 提交，Room 保存待发送状态，复用现有历史刷新确认及原 `clientMsgId` 手动重试，HTTP 成功不等于送达。已接入密码登录、会话校验及授权后的资源 SSE 原始事件接收，尚未接入 SSE 正文业务处理、媒体发送、真实语音通话、系统推送通知、完整多端同步或端到端加密。服务器地址沿用 AppConfig 和本地设置；不自行猜测、写入或下载 LiveKit Token 或其他服务密钥。
- 现有通用组件位于 `common/ui/Components.kt`；头像已打包在 `res/common/drawable-nodpi`，图标优先使用已有 drawable 和 Compose 图标，不为展示现有素材再下载库或图片。
- 页面及实现边界见 `doc/project-modules.md`，创建与移植顺序见 `doc/ios-implementation-roadmap.md`；Android 界面使用原生 Compose，不引入 WebView 重建。

## 数据库访问约束

- Android 端所有本地数据库操作必须统一经过 Room，包括数据库创建、表结构管理、增删改查和事务；复用已声明的 Room、KSP 依赖及插件，不另建一套数据库访问实现。
- 数据库通过 Room 的数据库构建器创建，由 `@Database`、`@Entity` 和 `@Dao` 定义数据库、表结构及访问接口；业务读写通过 DAO，事务使用 Room 提供的能力。
- 禁止手动创建、打开或管理 SQLite 数据库，禁止自行使用 `SQLiteOpenHelper`、`SQLiteDatabase`、`Context.openOrCreateDatabase`、SQLite 驱动或其他底层接口替代 Room。
- 禁止通过 Room 的底层连接、`openHelper` 等接口绕过 DAO 执行业务 SQL，也不手写建表、改表或删表逻辑；需要 SQL 查询时在 Room DAO 中声明，由 Room 管理执行。
- 数据结构变更遵循下文“数据变更与历史兼容”，需要重建数据库时也通过 Room 配置完成，不增加手写 SQLite 迁移逻辑。
- 本约束针对本地数据库访问，不代表本次需要将现有 SharedPreferences 配置存储全部迁入 Room；涉及具体存储调整时按需求限定修改范围。

## 数据变更与历史兼容

- 所有数据均不要求兼容历史版本。数据模型、字段、序列化格式、SharedPreferences、数据库及缓存等发生变更时，直接按当前需求修改相关读写代码，不为保留历史数据增加迁移逻辑。
- 不新增旧字段兼容、旧格式解析、版本迁移、双读双写或仅用于历史兼容的回退分支；修改范围内已有的相关兼容逻辑不再需要时直接移除。
- 旧数据与新结构不兼容时，允许按需丢弃旧数据、清空相关存储或重建数据库，无需迁移、转换或恢复历史数据；仅处理本次变更涉及的数据，不顺带清理无关数据。此约定不改变共用服务端当前接口协议及跨项目修改需先取得同意的要求。

## 避免重复下载依赖

以下规则约束后续工具调用和配置修改；仅记录配置不能保证缓存完整，也不能从技术上阻止构建工具联网。

1. 执行构建前先读本文件及相关配置，优先复用已有依赖、SDK、JDK 和 Wrapper。文档或静态资源说明修改不运行 Gradle，不为了确认环境而触发同步或依赖下载。
2. 使用仓库的 `.\gradlew.bat`，沿用当前 `GRADLE_USER_HOME`；未设置时复用当前用户默认的 `.gradle`。不为每个任务创建新的缓存目录，不随意覆盖 `GRADLE_USER_HOME`、`JAVA_HOME`、SDK 路径或 IDE 的 Gradle JDK。
3. 仅在需要且用户明确要求执行构建、测试或静态检查时，先使用下列 `--offline` 命令；功能修改默认由用户手动测试，不主动运行验证任务。离线失败时，区分缺少依赖、插件、Wrapper 分发包、JDK 或 Android SDK，不把所有失败都归因于依赖版本。
4. `--offline` 主要约束 Gradle 依赖解析，不保证 Wrapper 引导、JDK 工具链或 Android SDK 自动获取也被禁止。执行前应确认已有 Gradle 9.8.2、匹配的 JDK 25 和当前 SDK 组件；缺失时先报告，取得用户同意后仅获取缺失项。
5. 不使用 `--refresh-dependencies`，不删除全局或项目 `.gradle`、`.kotlin`、Wrapper 分发缓存或 SDK/JDK 来重试；不要把 `clean` 作为每次构建的前置步骤。
6. 不升级或降级 Gradle、AGP、Kotlin、Compose BOM、Room、KSP、LiveKit、OkHttp，不重新安装现有工具链。版本确需变更或缓存确有损坏时，先说明证据、影响及必要下载，取得用户同意后最小化处理。
7. 同一工作区的验证顺序执行；需要多个任务时可合并为一次 Wrapper 调用，避免代理、命令行和 IDE 同时启动重复构建或下载。
8. 经同意联网补齐当前固定版本后，继续使用原有缓存及离线验证；不要另行用浏览器、脚本、Maven 或其他包管理器重复下载同一 Maven 制品，不手动复制 JAR/AAR 绕过现有依赖声明。
9. 下载失败时报告具体坐标或工具版本和错误原因，不无限重试、不自动更换版本或仓库。临时诊断参数不要未经确认写入全局 Gradle 配置。
10. 后续配置变更须同步本文件中的对应条目；保留 `local.properties`、IDE 用户配置、代理凭据及签名信息在本机，不将其内容写入本文档。

## 项目结构与技术栈

- 本项目是 Android 群聊应用，使用 Kotlin、Jetpack Compose、Material 3 和 Gradle Kotlin DSL。
- 业务源码位于 `app/src/main/java/com/im/openchat/`，先按业务分组，再按职责分层：`chat/ui/`、`chat/data/` 放会话界面与数据，`address/ui/`、`address/data/` 放通讯录界面与数据，`me/ui/`、`me/data/` 放个人页、设置与个人资料，`login/ui/` 预留登录界面。不使用 `ui/chat` 等反向层级，个人页不另建 `profile` 业务目录。
- 跨业务共用的界面和入口位于 `common/ui/`，主题位于 `common/ui/theme/`，整体数据汇总与统一存储位于 `common/data/`；不将各业务模型和操作全部放回公共目录，不为了预留目录创建空实现或新 Gradle 模块。
- 本地单元测试位于 `app/src/test/`，设备端测试位于 `app/src/androidTest/`。
- 依赖版本与别名统一在 `gradle/libs.versions.toml` 中维护，模块通过 `libs` 引用。
- 新代码遵循现有目录和职责划分，不为尚未落地的需求预先引入新模块、架构层或框架。

## 组件复用与抽取

- 已有组件或 UI 组件库能够满足需求时，必须优先复用；先检查项目现有组件以及 Compose、Material 3 提供的能力。
- 只有当组件职责明确，并且已经或合理预期会在多个地方使用时，才抽取为公共组件。合理预期必须有具体使用场景，不能仅基于可能的未来需求。
- 不要为了缩短模板、包裹单个元素或预想未来需求而创建组件。一次性、页面专属的简单 UI 保留在页面内部。
- 页面内部确有独立职责的复杂 UI 可以拆为文件私有的组合函数，但不应因此扩大为公共 API。
- 公共 UI 组件沿用项目现有组织方式；页面状态、导航和业务逻辑不得为复用而强行塞入通用组件。
- 优先使用现有主题、颜色、排版和图标资源，避免重复定义同义样式或另建一套设计系统。

## 依赖与开发模式

- 优先使用仓库中已有的依赖和开发模式。只有现有工具无法合理满足需求时，才新增依赖。
- 新增依赖前确认必要性、兼容性及维护成本，避免引入职责重叠的库；不得顺带升级无关依赖。
- 新依赖必须通过 Version Catalog 管理，不在业务代码或模块构建脚本中散落硬编码版本。
- 网络与 SSE 相关实现优先使用已引入的 OkHttp SSE，不重复实现底层协议解析，不额外引入功能重叠的网络库。
- 资源 SSE 公共框架位于 `common/network/sse/`，接入说明见 `doc/project-modules.md#m05`；由 OpenChatApp 统一管理授权连接，密码登录后用 LOGIN 建连，会话恢复用 RECONNECT，未登录不建连。原始推送交给 onResourceMessage 回调，尚不解析或存储业务正文；各业务复用唯一连接，不各自建连。
- 协议类型先查服务端对应枚举，再在 Android 端使用具名枚举并显式映射协议值；禁止用裸数字、猜测的类型值或枚举 ordinal 代替协议。SSE 的外层事件名与正文业务类型、服务端内部推送路由枚举必须区分。
- 沿用已有状态管理、持久化和异步处理方式，不在同一职责内混用多种开发模式。

## 修改原则

- 修改前先阅读相关代码、调用方和必要的测试，并遵循项目已有的目录结构和编码习惯。
- 修改范围应聚焦于当前需求，不要格式化或重构无关文件。
- 不得删除或覆盖与当前任务无关的用户已有修改；相关文件已有修改时，应在理解后增量调整。
- 使用清晰的命名，避免无意义缩写；仅在解释复杂或不明显的实现行为时添加简洁的行内注释。
- 方法和函数的文档注释是强制要求，不受“仅为复杂行为添加行内注释”的限制。
- 优先采用能够解决当前问题的简单实现，不为假设的扩展点引入额外抽象。
- 不直接编辑构建产物、缓存或自动生成文件，不提交本机路径、密钥、令牌或其他敏感信息。

## 注释规范

- 所有方法和函数都必须添加中文注释，说明方法的用途、关键参数、返回结果以及重要的副作用。
- 此要求覆盖顶层函数、成员函数、私有函数、扩展函数、局部函数、表达式函数、重写方法、测试函数和 `@Composable` 函数。
- 回调函数、事件处理函数、生命周期函数、计算属性、监听器和异步方法同样必须添加中文注释。
- Kotlin 中自定义属性访问器，以及承担计算属性职责的派生状态计算，均应遵守本规范；有业务逻辑的匿名函数和 Lambda 也不能遗漏。
- 具名函数优先使用中文 KDoc；关键参数使用 `@param` 说明，有实际返回值时使用 `@return` 说明。返回 `Unit` 时在正文说明无返回值即可。
- 注释必须说明重要副作用，例如修改状态、触发回调、发起网络请求、写入存储或释放资源；无副作用时可简洁注明。
- `@Composable` 函数需说明展示职责、关键入参及交互回调；返回 `Unit`，渲染 UI，有其他副作用时还应明确说明。
- 匿名回调、事件处理 Lambda 和自定义访问器可使用紧邻定义的中文行注释或块注释，说明用途、关键输入、输出和重要副作用；无参数、无返回值的情况可合并简述。
- 注释应紧贴对应的方法或函数，放在其注解或声明之前，内容应准确反映当前实现；修改逻辑时必须同步更新注释。
- 简单的模板绑定或不包含业务逻辑的配置可以不额外添加注释，但其中定义的方法仍必须遵守本规范。
- Compose 中纯展示的布局 DSL、简单属性绑定和静态配置不必逐项注释；其中承担事件、状态修改、异步任务或其他业务逻辑的函数与 Lambda 仍必须注释。
- 不使用“处理逻辑”“执行操作”等无法说明实际行为的注释，不保留过期描述或整段被注释掉的旧实现。
- 本规范约束所有方法和函数；实施时必须覆盖本次新增或修改的相关代码，不得为补齐历史注释而顺带批量修改无关文件。

## Android 与 Compose 约定

- 组合函数专注于界面渲染和交互声明，不在组合执行过程中直接发起网络请求、写入存储或启动长期任务。
- 状态和副作用沿用已有 Compose 与 Android 生命周期管理方式；连接、监听器及异步任务必须有明确的释放或取消时机。
- 使用 `pointerInput` 时避免循环触发、重复注册或通过状态更新反复重启监听；单次手势应在一次 `awaitEachGesture` 中完成采样，不得在事件循环中持续修改会导致自身重组的状态。
- 列表场景不得为每个条目长期创建独立的 `pointerInput` 监听；需要记录触摸位置或统一处理手势时，优先在列表或更高层级设置一个稳定监听，并使用稳定 key，避免触摸事件累计和主线程负载放大。
- `pointerInput` 仅在确有手势处理需要时使用；不消费事件的坐标记录应尽量合并到已有手势处理，避免与 `combinedClickable`、滚动容器或其他指针监听器叠加造成重复分发。
- 不在主线程执行阻塞网络或磁盘操作，不在长生命周期对象中持有 Activity 或界面上下文而导致泄漏。
- 可复用组件优先通过明确的参数和事件回调与调用方交互，避免隐式访问页面状态。
- 涉及网络、存储或实时数据时，按需求处理加载、空数据、失败和取消状态，不吞掉需要反馈的异常。

## 验证与交付

- 修改功能后默认不运行自动化测试，由用户手动测试并反馈结果，再根据反馈继续调整。
- 除非用户明确要求，不主动执行单元测试、Compose 测试或设备端测试，也不为验证功能主动执行 Gradle 构建、Lint、启动模拟器或安装应用。
- 可以阅读相关代码和已有测试、检查修改差异；不能将代码阅读或未执行的测试描述为验证通过。
- 用户明确要求构建时，使用仓库的 Gradle Wrapper：Windows 下运行 `.\gradlew.bat --offline :app:assembleDebug`；纯文档修改无需构建。
- 用户明确要求补充或更新测试时，根据影响范围在已有测试中增量覆盖；只有明确要求运行测试时才执行相应任务。
- 经用户要求执行的任务默认先加 `--offline`，需要联网时遵循“避免重复下载依赖”中的确认流程。
- 交付时简要说明修改内容，并注明未运行测试、待用户手动验证；如用户要求执行了验证，如实说明结果及仍存在的限制。

以下离线验证命令仅供用户明确要求执行时按范围选择，不默认运行：

```powershell
.\gradlew.bat --offline :app:assembleDebug
.\gradlew.bat --offline :app:testDebugUnitTest
.\gradlew.bat --offline :app:lintDebug
.\gradlew.bat --offline :app:connectedDebugAndroidTest
```

用户明确要求同时执行构建、单元测试和静态检查时，可合并为一次调用：

```powershell
.\gradlew.bat --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`connectedDebugAndroidTest` 需要已启动且可用的模拟器或已连接设备；没有设备时明确说明未执行，不为验证普通改动擅自下载系统镜像或创建模拟器。

当前测试入口为 `app/src/test/java/com/im/openchat/ChatDataTest.kt`、`ExampleUnitTest.kt`，以及 `app/src/androidTest/java/com/im/openchat/OpenChatUiTest.kt`、`ExampleInstrumentedTest.kt`。用户明确要求补充或更新测试时，优先在这些测试中增量覆盖，不重复引入测试框架。
