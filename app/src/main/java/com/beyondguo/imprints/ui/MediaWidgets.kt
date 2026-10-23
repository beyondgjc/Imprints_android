package com.beyondguo.imprints.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import com.beyondguo.imprints.R
import androidx.compose.ui.res.stringResource
import kotlin.time.Duration.Companion.milliseconds

private object PlaybackFocus {
    private var active: ExoPlayer? = null
    fun started(player: ExoPlayer) {
        if (active !== player) {
            val previous = active
            active = player
            previous?.pause()
        }
    }
    fun stopped(player: ExoPlayer) { if (active === player) active = null }
}

/** 时间线/详情媒体单元；motionVideo 为动态照片附属短视频 */
data class MediaCell(
    val file: File,
    val kind: String,
    val motionVideo: File? = null,
)

@Composable
fun MediaThumbnail(
    file: File, kind: String, modifier: Modifier = Modifier,
    motionVideo: File? = null,
    viewerCells: List<MediaCell> = listOf(MediaCell(file, kind, motionVideo)),
    startIndex: Int = 0,
) {
    val appLocked = LocalImprintsLocked.current
    var open by remember(file.path) { mutableStateOf(false) }
    val hasMotion = motionVideo != null && motionVideo.isFile
    val bitmap by produceState<Bitmap?>(null, file.path, hasMotion) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (kind == "VIDEO") {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(file.path)
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512)
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFE7F4EE))
        .clickable { open = true }, contentAlignment = Alignment.Center) {
        if (bitmap == null) Text(if (kind == "VIDEO") stringResource(R.string.media_kind_video) else stringResource(R.string.media_kind_image), color = Quiet)
        else Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (kind == "VIDEO" || hasMotion) Surface(shape = CircleShape, color = Color(0xB3222830)) {
            Icon(Icons.Default.PlayArrow, null, Modifier.padding(5.dp), tint = Color.White)
        }
        if (kind == "GIF") Text("GIF", Modifier.align(Alignment.TopEnd).padding(5.dp)
            .background(Ink, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            color = Color.White, fontSize = 9.sp)
        if (hasMotion && kind == "IMAGE") Text(stringResource(R.string.media_motion), Modifier.align(Alignment.TopEnd).padding(5.dp)
            .background(Ink, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            color = Color.White, fontSize = 9.sp)
    }
    if (open && !appLocked) {
        FullscreenMediaViewer(cells = viewerCells, startIndex = startIndex, onClose = { open = false })
    }
}

/** 微信式全屏查看：黑底、Fit、左右滑动切换（设计 52–56）。 */
@Composable
fun FullscreenMediaViewer(
    cells: List<MediaCell>,
    startIndex: Int = 0,
    onClose: () -> Unit,
) {
    if (cells.isEmpty()) { onClose(); return }
    val initial = startIndex.coerceIn(0, cells.lastIndex)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pagerState = rememberPagerState(initialPage = initial) { cells.size }
        var page by remember { mutableStateOf(initial) }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { page = it }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF141A17))) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val cell = cells[index]
                when {
                    cell.kind == "VIDEO" -> VideoViewer(cell.file, Modifier.fillMaxSize())
                    cell.kind == "GIF" -> GifViewer(cell.file, Modifier.fillMaxSize())
                    cell.motionVideo != null && cell.motionVideo.isFile ->
                        VideoViewer(cell.motionVideo, Modifier.fillMaxSize())
                    else -> FullscreenImageView(cell.file)
                }
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close), tint = Color.White)
                }
                Text(
                    stringResource(R.string.media_page_counter, (page + 1).coerceAtLeast(1), cells.size),
                    Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(48.dp))
            }
            Text(
                stringResource(R.string.a11y_swipe_media),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
                color = Color(0xFFBBC5C0),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun FullscreenImageView(file: File) {
    val bitmap by produceState<Bitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 2048)
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } ?: Text(stringResource(R.string.media_image_loading), color = Color.White.copy(alpha = 0.7f))
    }
}

/**
 * 朋友圈式图片网格：1 张大图靠左；2/4 张 2 列；其余 3 列。
 * cells 为 MediaCell（可携带动态照片附属视频）。
 */
@Composable
fun MomentsMediaGrid(cells: List<MediaCell>, modifier: Modifier = Modifier) {
    if (cells.isEmpty()) return
    val gap = 6.dp
    if (cells.size == 1) {
        val cell = cells.first()
        MediaThumbnail(
            cell.file, cell.kind,
            modifier
                .wrapContentWidth(Alignment.Start)
                .widthIn(max = 280.dp)
                .heightIn(max = 280.dp)
                .aspectRatio(1f, matchHeightConstraintsFirst = true),
            motionVideo = cell.motionVideo,
            viewerCells = cells,
            startIndex = 0,
        )
        return
    }
    val columns = when (cells.size) {
        2, 4 -> 2
        else -> 3
    }
    Column(modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        var baseIndex = 0
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { i, cell ->
                    MediaThumbnail(
                        cell.file, cell.kind,
                        Modifier.weight(1f, fill = true).aspectRatio(1f),
                        motionVideo = cell.motionVideo,
                        viewerCells = cells,
                        startIndex = baseIndex + i,
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f, fill = true).aspectRatio(1f))
                }
            }
            baseIndex += row.size
        }
    }
}

@Composable
private fun GifViewer(file: File, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawable = remember(file.path) {
        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }.getOrNull()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { ImageView(it) }, modifier = Modifier.fillMaxWidth(), update = { view ->
            view.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.start()
        })
    }
    DisposableEffect(drawable, lifecycleOwner) {
        val animated = drawable as? AnimatedImageDrawable
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) animated?.stop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); animated?.stop() }
    }
}

@Composable
private fun VideoViewer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(file.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) PlaybackFocus.started(player) else PlaybackFocus.stopped(player)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); PlaybackFocus.stopped(player); player.release() }
    }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; setBackgroundColor(android.graphics.Color.BLACK) } },
        modifier = modifier,
    )
}

@Composable
fun InlineAudio(
    file: File,
    onRemove: (() -> Unit)? = null,
    onReplace: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var player by remember(file.path) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(file.path) { mutableStateOf(false) }
    var position by remember(file.path) { mutableStateOf(0L) }
    DisposableEffect(player) {
        val current = player
        val listener = if (current != null) object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) PlaybackFocus.started(current) else PlaybackFocus.stopped(current)
            }
        } else null
        if (listener != null) current?.addListener(listener)
        onDispose {
            if (current != null) {
                if (listener != null) current.removeListener(listener)
                PlaybackFocus.stopped(current)
                current.release()
            }
        }
    }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { player?.pause(); playing = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, playing) {
        while (playing) { position = player?.currentPosition ?: 0; delay(300.milliseconds) }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, Line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(onClick = {
            val current = player ?: ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }.also { player = it }
            if (current.isPlaying) {
                current.pause()
                playing = false
            } else {
                // 播完停在 ENDED，直接 play 不会回到开头
                if (current.playbackState == Player.STATE_ENDED) current.seekTo(0)
                PlaybackFocus.started(current)
                current.play()
                playing = true
            }
        }, shape = CircleShape, color = PaleGreen) {
            if (playing) Text("Ⅱ", Modifier.padding(horizontal = 17.dp, vertical = 10.dp),
                color = Green, fontSize = 18.sp)
            else Icon(Icons.Default.PlayArrow, stringResource(R.string.a11y_play_audio), Modifier.padding(12.dp), tint = Green)
        }
        Column(
            Modifier.weight(1f)
                .then(if (onReplace != null) Modifier.clickable(onClick = onReplace) else Modifier),
        ) {
            Text(
                file.nameWithoutExtension.ifBlank { stringResource(R.string.audio_default_name) },
                color = Ink, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
            )
            val positionLabel = stringResource(
                R.string.media_audio_time,
                position / 1000,
                (player?.duration ?: 0).coerceAtLeast(0) / 1000,
            )
            Text(
                when {
                    playing -> stringResource(R.string.audio_playing_tap)
                    onReplace != null -> stringResource(R.string.audio_click_replace) + " · " + positionLabel
                    else -> stringResource(R.string.audio_click_play)
                },
                color = Quiet, fontSize = 12.sp, maxLines = 1,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Text("×", color = Quiet, fontSize = 22.sp)
            }
        } else {
            Text(
                stringResource(
                    R.string.media_audio_time,
                    position / 1000,
                    (player?.duration ?: 0).coerceAtLeast(0) / 1000,
                ),
                color = Quiet, fontSize = 11.sp,
            )
        }
        }
    }
}

/**
 * 媒体九宫格（3 列）。**编辑印迹、写信、写回顾共用这一套布局**。
 *
 * 输入是「已就绪的一格」[MediaCellInput]（文件 + 类型），
 * 谁用谁把自己那边的选择类型转过来 —— 组件本身不需要知道
 * MediaChoice / MediaItem / VisualMediaEntity 的存在。
 *
 * @param onMove 传入则支持**长按拖拽排序**（拖过半格与目标交换）。
 *   交换由调用方改自己的列表，组件不持有数据。
 * @param addContent 「＋」格的内容；编辑印迹放图标 + 文案，回顾只放一个＋号。
 */
data class MediaCellInput(val file: java.io.File, val kind: String, val motionVideo: java.io.File? = null)

@Composable
internal fun MediaGrid(
    cells: List<MediaCellInput>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: ((from: Int, to: Int) -> Unit)? = null,
    addContent: @Composable () -> Unit = { Text("＋", color = Quiet, fontSize = 24.sp) },
    modifier: Modifier = Modifier,
) {
    val columns = 3
    val gap = 8.dp
    var dragIndex by remember(cells.size) { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val total = cells.size + if (canAdd) 1 else 0

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        repeat((total + columns - 1) / columns) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(columns) { col ->
                    val index = row * columns + col
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        when {
                            index < cells.size -> {
                                val cell = cells[index]
                                val dragging = dragIndex == index
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            if (dragging) {
                                                scaleX = 1.05f
                                                scaleY = 1.05f
                                                translationX = dragOffset.x
                                                translationY = dragOffset.y
                                                shadowElevation = 12f
                                                alpha = 0.92f
                                            }
                                        }
                                        .then(
                                            if (onMove == null) Modifier else Modifier.pointerInput(index, cells.size) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        dragIndex = index
                                                        dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                                    },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        dragOffset += amount
                                                        val from = dragIndex
                                                        if (from in cells.indices) {
                                                            val cellSize = size.width.toFloat()
                                                            val c = from % columns
                                                            val r = from / columns
                                                            var target = -1
                                                            when {
                                                                dragOffset.x > cellSize * 0.5f && c < columns - 1 &&
                                                                    from + 1 < cells.size -> target = from + 1
                                                                dragOffset.x < -cellSize * 0.5f && c > 0 -> target = from - 1
                                                                dragOffset.y > cellSize * 0.5f && indexInRange(from + columns, cells.size) -> target = from + columns
                                                                dragOffset.y < -cellSize * 0.5f && r > 0 -> target = from - columns
                                                            }
                                                            if (target in cells.indices && target != from) {
                                                                onMove(from, target)
                                                                dragIndex = target
                                                                dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                                            }
                                                        }
                                                    },
                                                    onDragEnd = { dragIndex = -1; dragOffset = androidx.compose.ui.geometry.Offset.Zero },
                                                    onDragCancel = { dragIndex = -1; dragOffset = androidx.compose.ui.geometry.Offset.Zero },
                                                )
                                            },
                                        ),
                                ) {
                                    MediaThumbnail(
                                        cell.file, cell.kind,
                                        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                        motionVideo = cell.motionVideo,
                                    )
                                    Text(
                                        "×",
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .background(Color(0x66000000), RoundedCornerShape(50))
                                            .clickable { onRemove(index) }
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                        color = Color.White, fontSize = 14.sp,
                                    )
                                }
                            }
                            canAdd && index == cells.size -> {
                                Surface(
                                    Modifier.fillMaxSize().clickable(onClick = onAdd),
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFF3F3F3),
                                    border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { addContent() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun indexInRange(index: Int, size: Int) = index in 0 until size
