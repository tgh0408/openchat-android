package com.im.openchat.me.update

import androidx.core.content.FileProvider

/** 只为系统安装器提供更新目录的临时只读 URI，不开放聊天文件。 */
class UpdateFileProvider : FileProvider()
