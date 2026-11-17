package com.im.openchat.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.im.openchat.chat.data.GroupQrCode
import com.im.openchat.chat.data.GroupDetailsProtocol
import com.im.openchat.chat.data.formatChatTime
import com.im.openchat.common.network.CachedImages
import java.io.IOException
import kotlinx.coroutines.CancellationException

@Composable
internal fun GroupQrDialog(records: List<GroupQrCode>, namespace: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("群二维码") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (records.isEmpty()) item { Text("暂无群二维码") }
                itemsIndexed(records) { _, record -> GroupQrRecord(record, namespace) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun GroupQrRecord(record: GroupQrCode, namespace: String) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(record.url) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(record.url) { mutableStateOf(false) }
    var retry by remember(record.url) { mutableIntStateOf(0) }
    LaunchedEffect(record.url, namespace, retry) {
        error = false
        try {
            bitmap = CachedImages.load(context, namespace, record.url, 4L * 1024 * 1024, 1024) { record.url }.asImageBitmap()
        } catch (exception: CancellationException) { throw exception }
        catch (_: IOException) { error = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            val image = bitmap
            when {
                image != null -> Image(image, "群二维码", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                error -> TextButton(onClick = { retry++ }) { Text("加载失败，重试") }
                else -> CircularProgressIndicator()
            }
        }
        Text("生成时间：${groupRecordTime(record.createTime)}", style = MaterialTheme.typography.bodySmall)
        record.expireTime?.let {
            Text("过期时间：${groupRecordTime(it)}", style = MaterialTheme.typography.bodySmall)
        }
        record.keyword?.takeIf(String::isNotBlank)?.let { Text("密钥：$it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun groupRecordTime(value: String): String =
    formatChatTime(GroupDetailsProtocol.recordTime(value), "yyyy-MM-dd HH:mm")
