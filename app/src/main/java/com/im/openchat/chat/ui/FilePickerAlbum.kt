package com.im.openchat.chat.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.im.openchat.chat.data.*
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException

internal data class FilePickerAlbumState(
    val assets: List<GalleryAsset> = emptyList(),
    val loading: Boolean = false,
    val access: Boolean = false,
    val limited: Boolean = false,
    val error: String? = null,
    val onPermission: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

@Composable
internal fun rememberFilePickerAlbum(visible: Boolean, active: Boolean): FilePickerAlbumState {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var assets by remember { mutableStateOf<List<GalleryAsset>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(context.hasGalleryAccess()) }
    var limited by remember { mutableStateOf(context.hasLimitedGalleryAccess()) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
    }
    LaunchedEffect(visible) {
        if (visible && active && !context.hasGalleryAccess()) permission.launch(galleryPermissions())
    }
    LaunchedEffect(visible, active, revision) {
        access = context.hasGalleryAccess()
        limited = context.hasLimitedGalleryAccess()
        if (!active || !access) assets = emptyList()
        if (!visible || !active) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            assets = if (access) queryGallery(context) else emptyList()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            assets = emptyList()
            error = "相册读取失败，请重试或检查照片访问权限"
        } finally {
            loading = false
        }
    }
    return FilePickerAlbumState(
        assets = if (active) assets else emptyList(), loading = loading || !active,
        access = access, limited = limited, error = error,
        onPermission = { permission.launch(galleryPermissions()) },
        onRetry = { revision++ },
        onSettings = {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri()))
            } catch (_: Exception) { error = "无法打开权限设置" }
        },
    )
}

@Composable
internal fun FilePickerAlbumContent(
    state: FilePickerAlbumState,
    query: String,
    selectedOnly: Boolean,
    selected: Set<Uri>,
    selectionCount: Int,
    enabled: Boolean,
    onToggle: (GalleryAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(state.assets, query, selectedOnly, selected) {
        state.assets.filter { (!selectedOnly || it.uri in selected) && it.name.contains(query.trim(), ignoreCase = true) }
    }
    val now = remember(state.assets) { System.currentTimeMillis() }
    val groups = remember(visible, now) { visible.groupBy { chatFileDateGroup(it.addedAt * 1000L, now) } }
    Column(modifier.testTag("file_picker_album")) {
        if (state.limited && state.access) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("仅可访问部分照片和视频", Modifier.weight(1f), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = state.onPermission, enabled = enabled) { Text("管理") }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            !state.access || state.error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error ?: "未获得照片和视频访问权限", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.access) {
                    ToolButton(Icons.Default.Refresh, "重试相册", enabled = enabled, onClick = state.onRetry)
                } else {
                    TextButton(onClick = state.onPermission, enabled = enabled) { Text("允许访问") }
                    TextButton(onClick = state.onSettings, enabled = enabled) { Text("权限设置") }
                }
            }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        query.isNotBlank() -> "未找到相关文件"
                        selectedOnly -> "尚未选择相册文件"
                        else -> "暂无照片或视频"
                    },
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().testTag("file_album_grid"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                groups.forEach { (date, assets) ->
                    item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                        Text(date, Modifier.padding(top = 10.dp, bottom = 18.dp),
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(assets, key = { it.uri.toString() }) { asset ->
                        FilePickerAlbumTile(
                            asset, asset.uri in selected,
                            enabled && (asset.uri in selected || selectionCount < MAX_CHAT_FILE_SELECTION),
                            onClick = { onToggle(asset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePickerAlbumTile(asset: GalleryAsset, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(asset) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(asset) { mutableStateOf(false) }
    LaunchedEffect(asset) {
        try {
            bitmap = galleryThumbnail(context, asset)
            failed = bitmap == null
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(
        Modifier.fillMaxWidth().testTag("file_album_asset_${asset.uri}")
            .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(bottom = 18.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (failed) Text("缩略图不可用", Modifier.align(Alignment.Center).padding(4.dp),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (asset.isVideo) {
                val seconds = asset.durationMillis / 1000
                Text(
                    if (seconds > 0) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "视频",
                    Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
                    fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                    .background(if (selected) Color(0xFF07C160) else Color.White.copy(alpha = 0.35f), CircleShape)
                    .border(1.5.dp, if (selected) Color(0xFF07C160) else Color(0xFF969696), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
            }
        }
        Text(asset.name, Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatChatFileSize(asset.sizeBytes), Modifier.fillMaxWidth().padding(top = 3.dp),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
