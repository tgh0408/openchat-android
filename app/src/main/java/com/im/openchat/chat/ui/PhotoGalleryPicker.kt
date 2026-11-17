package com.im.openchat.chat.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.im.openchat.chat.data.*
import com.im.openchat.common.ui.ToolButton
import kotlinx.coroutines.CancellationException
import androidx.core.net.toUri

private val GalleryBackground = Color(0xFF303030)
private val GalleryGreen = Color(0xFF07C160)

@Stable
internal class GalleryUiState {
    val expanded = mutableStateOf(false)
    val search = mutableStateOf(false)
    val query = mutableStateOf("")

    fun back(dismiss: () -> Unit) {
        when {
            expanded.value -> expanded.value = false
            search.value -> { search.value = false; query.value = "" }
            else -> dismiss()
        }
    }
}

@Composable
internal fun PhotoGalleryPicker(onDismiss: () -> Unit, onSend: (List<GallerySelection>) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var assets by remember { mutableStateOf<List<GalleryAsset>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf(context.hasGalleryAccess()) }
    var limited by remember { mutableStateOf(context.hasLimitedGalleryAccess()) }
    var selection by remember { mutableStateOf<List<GallerySelection>>(emptyList()) }
    var submitted by remember { mutableStateOf(false) }
    val ui = remember { GalleryUiState() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
    }
    LaunchedEffect(Unit) {
        if (!context.hasGalleryAccess()) permission.launch(galleryPermissions())
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                active = true
                revision++
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                active = false
                assets = emptyList()
                selection = emptyList()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, active) {
        if (!active) return@LaunchedEffect
        loading = true
        error = null
        access = context.hasGalleryAccess()
        limited = context.hasLimitedGalleryAccess()
        assets = emptyList()
        try {
            if (access) {
                val loaded = queryGallery(context)
                if (!active) return@LaunchedEffect
                assets = loaded
                val allowed = assets.map { it.uri }.toSet()
                selection = selection.filter { it.uri in allowed }
            } else selection = emptyList()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            selection = emptyList()
            error = "相册读取失败，请重试或检查照片访问权限"
        } finally {
            loading = false
        }
    }
    Dialog(onDismissRequest = { ui.back(onDismiss) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        PhotoGalleryContent(
            assets = assets, selection = selection, loading = loading || !active,
            error = error, access = access, limited = limited, enabled = active && !submitted,
            onSelection = { selection = it },
            onDismiss = onDismiss,
            onRetry = { revision++ },
            onPermission = { permission.launch(galleryPermissions()) },
            onSettings = {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri()))
                } catch (_: Exception) { error = "无法打开权限设置" }
            },
            onSend = {
                if (active && !submitted && selection.isNotEmpty() && context.hasGalleryAccess()) {
                    submitted = true
                    onSend(selection.toList())
                } else revision++
            },
            state = ui,
        )
    }
}

@Composable
internal fun PhotoGalleryContent(
    assets: List<GalleryAsset>,
    selection: List<GallerySelection>,
    loading: Boolean,
    error: String?,
    access: Boolean,
    limited: Boolean,
    enabled: Boolean,
    onSelection: (List<GallerySelection>) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onSend: () -> Unit,
    state: GalleryUiState = remember { GalleryUiState() },
) {
    var sourceId by remember { mutableStateOf(GALLERY_ALL) }
    var expanded by state.expanded
    var search by state.search
    var query by state.query
    var original by remember { mutableStateOf(false) }
    var limitError by remember { mutableStateOf(false) }
    val sources = remember(assets) { gallerySources(assets) }
    val source = sources.firstOrNull { it.id == sourceId } ?: sources.first()
    val visible = remember(source, query) {
        source.assets.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    val grid = rememberLazyGridState()
    LaunchedEffect(source.id, query) { grid.scrollToItem(0) }
    BackHandler { state.back(onDismiss) }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(Modifier.fillMaxSize().background(GalleryBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing).testTag("photo_gallery_picker")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Default.Close, "关闭相册", onClick = onDismiss)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF454545))
                        .clickable(enabled = !loading && access) { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 8.dp).testTag("gallery_source"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(source.name, Modifier.weight(1f, fill = false),
                            fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            "相册来源", Modifier.size(24.dp))
                    }
                }
                ToolButton(Icons.Default.Search, "搜索相册", enabled = access && !loading,
                    onClick = { search = !search; if (!search) query = ""; expanded = false })
            }
            if (search) OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("搜索文件名") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = GalleryGreen, unfocusedBorderColor = Color.Gray,
                    cursorColor = GalleryGreen, unfocusedPlaceholderColor = Color.LightGray,
                ),
            )
            if (limited) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("仅可访问部分照片和视频", Modifier.weight(1f), fontSize = 12.sp)
                TextButton(onClick = onPermission) { Text("管理", color = GalleryGreen) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = GalleryGreen)
                    !access || error != null -> Column(
                        Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error ?: "未获得照片和视频访问权限")
                        TextButton(onClick = if (access) onRetry else onPermission) {
                            Text(if (access) "重试" else "允许访问", color = GalleryGreen)
                        }
                        TextButton(onClick = onSettings) { Text("权限设置", color = Color.White) }
                    }
                    expanded -> LazyColumn(Modifier.fillMaxSize().testTag("gallery_sources")) {
                        items(sources, key = { it.id }) { item ->
                            Row(Modifier.fillMaxWidth().clickable {
                                sourceId = item.id
                                expanded = false
                            }.testTag("gallery_source_${item.id}"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(64.dp).background(Color(0xFF444444))) {
                                    item.assets.firstOrNull()?.let { GalleryThumbnail(it) }
                                }
                                Text(item.name, Modifier.weight(1f).padding(horizontal = 12.dp),
                                    fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("(${item.assets.size})", color = Color.LightGray, fontSize = 14.sp)
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    if (source.id == item.id) Icon(Icons.Default.Check, "当前来源", tint = GalleryGreen)
                                }
                            }
                            HorizontalDivider(color = Color(0xFF454545))
                        }
                    }
                    visible.isEmpty() -> Text(if (query.isEmpty()) "暂无照片和视频" else "没有匹配的照片或视频",
                        Modifier.align(Alignment.Center))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(4), state = grid,
                        modifier = Modifier.fillMaxSize().testTag("gallery_grid"),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visible, key = { it.uri.toString() }) { asset ->
                            val index = selection.indexOfFirst { it.uri == asset.uri }
                            GalleryTile(asset, index.takeIf { it >= 0 }?.plus(1), enabled) {
                                if (index >= 0) {
                                    onSelection(selection.filterIndexed { i, _ -> i != index })
                                    limitError = false
                                } else if (selection.size < MAX_GALLERY_SELECTION) {
                                    onSelection(selection + GallerySelection(asset.uri, asset.isVideo))
                                    limitError = false
                                } else limitError = true
                            }
                        }
                    }
                }
            }
            if (limitError) Text("一次最多选择 $MAX_GALLERY_SELECTION 项",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Color(0xFFFFB4AB), fontSize = 12.sp)
            GalleryFooter(selection.size, original, enabled && access && !loading && error == null && !expanded,
                onOriginal = { original = !original }, onSend = onSend)
        }
    }
}

@Composable
private fun GalleryFooter(count: Int, original: Boolean, enabled: Boolean, onOriginal: () -> Unit, onSend: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        val preview: @Composable () -> Unit = {
            TextButton(onClick = {}, enabled = false, modifier = Modifier.testTag("gallery_preview"),
                colors = ButtonDefaults.textButtonColors(disabledContentColor = Color.LightGray)) {
                Text(if (count == 0) "预览" else "预览($count)", fontSize = 16.sp)
            }
        }
        val originalOption: @Composable () -> Unit = {
            Row(Modifier.heightIn(min = 48.dp).toggleable(original, enabled = enabled,
                role = Role.Checkbox, onValueChange = { onOriginal() }).testTag("gallery_original")
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectionCircle(if (original) 0 else null)
                Text("原图", Modifier.padding(start = 6.dp), fontSize = 16.sp)
            }
        }
        val send: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = onSend, enabled = enabled && count > 0,
                modifier = modifier.heightIn(min = 48.dp).testTag("gallery_send"),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GalleryGreen, contentColor = Color.White,
                    disabledContainerColor = Color(0xFF454545), disabledContentColor = Color.Gray)) {
                Text(if (count == 0) "发送" else "发送($count)", fontSize = 16.sp)
            }
        }
        if (stacked) Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) { preview(); originalOption() }
            send(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            preview()
            originalOption()
            send(Modifier)
        }
    }
}

@Composable
private fun GalleryTile(asset: GalleryAsset, number: Int?, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF444444))
        .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
        .semantics { selected = number != null; contentDescription = asset.name }
        .testTag("gallery_asset_${asset.uri}")) {
        GalleryThumbnail(asset)
        if (asset.isVideo && asset.durationMillis > 0) {
            val seconds = asset.durationMillis / 1000
            Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(3.dp),
                color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) { SelectionCircle(number) }
    }
}

@Composable
private fun SelectionCircle(number: Int?) {
    Box(Modifier.size(26.dp).clip(CircleShape)
        .background(if (number != null) GalleryGreen else Color.Black.copy(alpha = 0.2f))
        .border(if (number == null) 2.dp else 0.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center) {
        if (number == 0) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
        else if (number != null) Text(number.toString(), color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun GalleryThumbnail(asset: GalleryAsset) {
    val context = LocalContext.current
    var bitmap by remember(asset.uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(asset.uri) { mutableStateOf(false) }
    LaunchedEffect(asset.uri) {
        try {
            bitmap = galleryThumbnail(context, asset)
            failed = bitmap == null
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            failed = true
        }
    }
    bitmap?.let {
        Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
    }
    if (failed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Warning, "缩略图不可用", tint = Color.LightGray)
    }
}
