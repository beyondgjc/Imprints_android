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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun MediaThumbnail(file: File, kind: String) {
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
    Box(Modifier.size(100.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE7F4EE))
        .clickable { open = true }, contentAlignment = Alignment.Center) {
        if (bitmap == null) Text(if (kind == "VIDEO") "视频" else "图片", color = Quiet)
        else Image(bitmap!!.asImageBitmap(), null, Modifier.size(100.dp), contentScale = ContentScale.Crop)
        if (kind == "VIDEO") Surface(shape = CircleShape, color = Color(0xB3222830)) {
            Icon(Icons.Default.PlayArrow, null, Modifier.padding(5.dp), tint = Color.White)
        }
        if (kind == "GIF") Text("GIF", Modifier.align(Alignment.TopEnd).padding(5.dp)
            .background(Ink, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            color = Color.White, fontSize = 9.sp)
    }
    if (open && !appLocked) {
        AlertDialog(onDismissRequest = { open = false }, title = { Text(if (kind == "VIDEO") "视频" else "查看媒体") },
            text = { when (kind) {
                "VIDEO" -> VideoViewer(file)
                "GIF" -> GifViewer(file)
                else -> bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
            } },
            confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
    }
}

@Composable
private fun GifViewer(file: File) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawable = remember(file.path) {
        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }.getOrNull()
    }
    AndroidView(factory = { ImageView(it) }, modifier = Modifier.fillMaxWidth().height(300.dp), update = { view ->
        view.setImageDrawable(drawable)
        (drawable as? AnimatedImageDrawable)?.start()
    })
    DisposableEffect(drawable, lifecycleOwner) {
        val animated = drawable as? AnimatedImageDrawable
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) animated?.stop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); animated?.stop() }
    }
}

@Composable
private fun VideoViewer(file: File) {
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
    AndroidView(factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().height(280.dp))
}

@Composable
fun InlineAudio(file: File) {
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
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
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
        Column(Modifier.weight(1f)) {
            Text("音频记录", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(if (playing) "播放中 · 点击暂停" else "点击播放", color = Quiet, fontSize = 12.sp)
        }
        Text("${position / 1000}s / ${(player?.duration ?: 0).coerceAtLeast(0) / 1000}s",
            color = Quiet, fontSize = 11.sp)
        }
    }
}
