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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
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

@Composable
fun MediaThumbnail(
    file: File, kind: String, modifier: Modifier = Modifier,
    viewerCells: List<Pair<File, String>> = listOf(file to kind),
    startIndex: Int = 0,
) {
    val appLocked = LocalImprintsLocked.current
    var open by remember(file.path) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, file.path) {
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
        if (bitmap == null) Text(if (kind == "VIDEO") "视频" else "图片", color = Quiet)
        else Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (kind == "VIDEO") Surface(shape = CircleShape, color = Color(0xB3222830)) {
            Icon(Icons.Default.PlayArrow, null, Modifier.padding(5.dp), tint = Color.White)
        }
        if (kind == "GIF") Text("GIF", Modifier.align(Alignment.TopEnd).padding(5.dp)
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
    cells: List<Pair<File, String>>,
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
                val (file, kind) = cells[index]
                when (kind) {
                    "VIDEO" -> VideoViewer(file, Modifier.fillMaxSize())
                    "GIF" -> GifViewer(file, Modifier.fillMaxSize())
                    else -> FullscreenImageView(file)
                }
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭", tint = Color.White)
                }
                Text(
                    "${(page + 1).coerceAtLeast(1)} / ${cells.size}",
                    Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(48.dp))
            }
            Text(
                "左右切换",
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
        } ?: Text("图片加载中…", color = Color.White.copy(alpha = 0.7f))
    }
}

/**
 * 朋友圈式图片网格：1 张大图；4 张 2×2；其余 3 列方格，全部展示不折叠。
 * cells 为 (file, kind) 列表。
 */
@Composable
fun MomentsMediaGrid(cells: List<Pair<File, String>>, modifier: Modifier = Modifier) {
    if (cells.isEmpty()) return
    val gap = 6.dp
    if (cells.size == 1) {
        val (file, kind) = cells.first()
        // 单图：靠左，不居中
        MediaThumbnail(
            file, kind,
            modifier
                .wrapContentWidth(Alignment.Start)
                .widthIn(max = 280.dp)
                .heightIn(max = 280.dp)
                .aspectRatio(1f, matchHeightConstraintsFirst = true),
            viewerCells = cells,
            startIndex = 0,
        )
        return
    }
    // 1 已处理；2 与 4 用 2 列（单元格与 4 宫格同大）；其余 3 列
    val columns = when (cells.size) {
        2, 4 -> 2
        else -> 3
    }
    // 固定高度约束，避免 LazyColumn 子项测量溢出盖住下方内容
    Column(modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        var baseIndex = 0
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { i, (file, kind) ->
                    MediaThumbnail(
                        file, kind,
                        Modifier.weight(1f, fill = true).aspectRatio(1f),
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
        while (playing) { position = player?.currentPosition ?: 0; delay(300) }
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
            if (current.isPlaying) { current.pause(); playing = false }
            else { PlaybackFocus.started(current); current.play(); playing = true }
        }, shape = CircleShape, color = PaleGreen) {
            if (playing) Text("Ⅱ", Modifier.padding(horizontal = 17.dp, vertical = 10.dp),
                color = Green, fontSize = 18.sp)
            else Icon(Icons.Default.PlayArrow, "播放音频", Modifier.padding(12.dp), tint = Green)
        }
        Column(
            Modifier.weight(1f)
                .then(if (onReplace != null) Modifier.clickable(onClick = onReplace) else Modifier),
        ) {
            Text(file.nameWithoutExtension.ifBlank { "音频记录" }, color = Ink, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                when {
                    playing -> "播放中 · 点击暂停"
                    onReplace != null -> "点击更换 · 播放 " + "${position / 1000}s / ${(player?.duration ?: 0).coerceAtLeast(0) / 1000}s"
                    else -> "点击播放"
                },
                color = Quiet, fontSize = 12.sp, maxLines = 1,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Text("×", color = Quiet, fontSize = 22.sp)
            }
        } else {
            Text("${position / 1000}s / ${(player?.duration ?: 0).coerceAtLeast(0) / 1000}s",
                color = Quiet, fontSize = 11.sp)
        }
        }
    }
}
