package com.beyondguo.imprints.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.net.Uri
import android.Manifest
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beyondguo.imprints.data.AudioChoice
import com.beyondguo.imprints.data.EntryDraft
import com.beyondguo.imprints.data.EntryRules
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.MediaChoice
import com.beyondguo.imprints.data.PlaceEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class EditorSnapshot(
    val body: String, val feeling: String, val eventTime: String, val placeName: String,
    val place: PlaceEntity?, val visuals: List<String>, val audio: String?,
)

@Composable
fun EditorPage(
    repository: ImprintRepository, id: String?, epoch: Int,
    onSaved: (String) -> Unit, onCancel: () -> Unit, onUnlock: () -> Unit,
    report: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var activePicker by remember { mutableStateOf<Dialog?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { activePicker?.dismiss(); activePicker = null }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); activePicker?.dismiss() }
    }
    var body by remember(id) { mutableStateOf("") }
    var feeling by remember(id) { mutableStateOf("") }
    var eventTime by remember(id) { mutableStateOf(EntryDraft().eventLocalMinute) }
    var placeName by remember(id) { mutableStateOf("") }
    var selectedPlace by remember(id) { mutableStateOf<PlaceEntity?>(null) }
    val visuals = remember(id) { mutableStateListOf<MediaChoice>() }
    var audio by remember(id) { mutableStateOf<AudioChoice?>(null) }
    var loading by remember(id) { mutableStateOf(id != null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var blocked by remember(id) { mutableStateOf(false) }
    var feelingLocked by remember(id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var extraUris by remember { mutableStateOf<List<Uri>?>(null) }
    var initial by remember(id) { mutableStateOf<EditorSnapshot?>(null) }
    var exitRequested by remember(id) { mutableStateOf(false) }

    fun snapshot() = EditorSnapshot(body, feeling, eventTime, placeName, selectedPlace,
        visuals.map { when (it) { is MediaChoice.Existing -> it.value.id; is MediaChoice.New -> it.value.id } },
        when (val selected = audio) { is AudioChoice.Existing -> selected.value.id; is AudioChoice.New -> selected.value.id; null -> null })
    fun discardPending() {
        visuals.forEach { if (it is MediaChoice.New) repository.files.discard(it.value) }
        (audio as? AudioChoice.New)?.let { repository.files.discard(it.value) }
    }
    fun requestExit() {
        if (initial != null && initial != snapshot()) exitRequested = true
        else { discardPending(); onCancel() }
    }
    BackHandler { requestExit() }

    LaunchedEffect(id, epoch) {
        if (id == null) {
            feelingLocked = repository.security.state().feelingMode == "ON" && !repository.security.isFeelingUnlocked()
            if (initial == null) initial = snapshot()
        } else {
            val detail = repository.detail(id)
            if (detail == null) { loading = false; report(IllegalStateException("印迹已不存在")); return@LaunchedEffect }
            if (!loaded) {
                body = detail.entry.body
                eventTime = detail.entry.eventLocalMinute
                placeName = detail.place?.name.orEmpty()
                selectedPlace = detail.place
                visuals.clear()
                visuals.addAll(detail.visuals.map(MediaChoice::Existing))
                audio = detail.audio?.let(AudioChoice::Existing)
            }
            val decoded = runCatching { repository.security.decodeFeeling(detail.feeling) }
            val wasBlocked = blocked
            if (!loaded || blocked) feeling = decoded.getOrNull().orEmpty()
            blocked = decoded.isFailure
            feelingLocked = repository.security.state().feelingMode == "ON" && !repository.security.isFeelingUnlocked()
            if (!loaded || wasBlocked && decoded.isSuccess) initial = snapshot()
            loaded = true
        }
        loading = false
    }

    fun prepareUris(uris: List<Uri>) {
        scope.launch {
            uris.forEach { uri ->
                runCatching { repository.files.prepare(uri) }
                    .onSuccess { prepared ->
                        if (visuals.size < 9) visuals.add(MediaChoice.New(prepared))
                        else { repository.files.discard(prepared); report(IllegalStateException("图片、动图、视频最多 9 个")) }
                    }.onFailure(report)
            }
        }
    }

    fun receivedUris(uris: List<Uri>) {
        val remaining = 9 - visuals.size
        if (uris.size > remaining) extraUris = uris
        else prepareUris(uris)
    }

    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) {
        receivedUris(it)
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) receivedUris(listOf(uri))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { repository.files.prepare(uri, audio = true) }.onSuccess { prepared ->
                (audio as? AudioChoice.New)?.let { repository.files.discard(it.value) }
                audio = AudioChoice.New(prepared)
            }.onFailure(report)
        }
    }
    fun requestLocation() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> { report(IllegalStateException("系统定位不可用")); return }
        }
        fun accept(location: android.location.Location?) {
            if (location == null) { report(IllegalStateException("暂未获取到当前位置")); return }
            selectedPlace = PlaceEntity(UUID.randomUUID().toString(), "当前位置", "", location.latitude, location.longitude, "WGS84", "DEVICE", null)
            placeName = selectedPlace!!.name
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { accept(it) }
            } else {
                val listener = object : LocationListener { override fun onLocationChanged(location: android.location.Location) { accept(location) } }
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                scope.launch { delay(12_000); manager.removeUpdates(listener) }
            }
        } catch (error: SecurityException) { report(error) }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestLocation() else report(IllegalStateException("未获得定位权限，可继续保存印迹"))
    }

    if (loading) { Text("正在加载…", Modifier.padding(20.dp)); return }
    if (blocked) {
        Column(Modifier.padding(20.dp)) {
            Text("请先解锁感受，再编辑这条印迹。")
            Button(onClick = onUnlock) { Text("输入感受口令") }
            TextButton(onClick = ::requestExit) { Text("返回") }
        }
        if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前输入和新选的附件尚未保存。") },
            confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { exitRequested = false }) { Text("继续编辑") } })
        return
    }

    fun saveEntry() {
        saving = true
        scope.launch {
            val draft = EntryDraft(id = id, body = body, eventLocalMinute = eventTime,
                visuals = visuals.toList(), audio = audio, feeling = feeling,
                place = selectedPlace ?: placeName.trim().takeIf(String::isNotEmpty)?.let {
                    PlaceEntity(UUID.randomUUID().toString(), it, "", null, null, null, "MANUAL", null)
                })
            runCatching { repository.save(draft) }.onSuccess(onSaved).onFailure(report)
            saving = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            IconButton(onClick = ::requestExit, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Ink)
            }
            Text(if (id == null) "新建印迹" else "编辑印迹", Modifier.align(Alignment.Center),
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = ::saveEntry, enabled = !saving && EntryRules.canSave(body, visuals.size,
                feelingLocked && feeling.isNotBlank()), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(if (saving) "保存中" else "保存")
            }
        }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            OutlinePanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Default.DateRange, null, tint = Quiet, modifier = Modifier.size(20.dp))
                Text("事件时间", Modifier.weight(1f), color = Quiet, fontSize = 14.sp)
                val parsed = runCatching { LocalDateTime.parse(eventTime) }.getOrElse { LocalDateTime.now() }
                TextButton(onClick = {
                    DatePickerDialog(context, { _, year, month, day ->
                        eventTime = "%04d-%02d-%02dT%02d:%02d".format(year, month + 1, day, parsed.hour, parsed.minute)
                    }, parsed.year, parsed.monthValue - 1, parsed.dayOfMonth).also { dialog ->
                        activePicker = dialog
                        dialog.setOnDismissListener { if (activePicker === dialog) activePicker = null }
                        dialog.show()
                    }
                }) { Text(eventTime.take(10), color = Ink, fontSize = 13.sp) }
                TextButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        eventTime = "${eventTime.take(10)}T%02d:%02d".format(hour, minute)
                    }, parsed.hour, parsed.minute, true).also { dialog ->
                        activePicker = dialog
                        dialog.setOnDismissListener { if (activePicker === dialog) activePicker = null }
                        dialog.show()
                    }
                }) { Text(eventTime.takeLast(5), color = Ink, fontSize = 13.sp) }
            }
            }
        }
        item { TextField(body, { body = it }, placeholder = { Text("此刻，想记下什么？", color = Quiet) },
            modifier = Modifier.fillMaxWidth(), minLines = 5, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 29.sp, color = Ink),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent)) }
        item { FineDivider() }
        item { SectionTitle("图片 / 动图 / 视频") }
        item {
            Surface(Modifier.size(112.dp).clickable {
                when (9 - visuals.size) {
                    0 -> report(IllegalStateException("图片、动图、视频最多 9 个"))
                    1 -> singlePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    else -> multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
            }, shape = RoundedCornerShape(16.dp), color = Color(0xFFF8FCFA),
                border = BorderStroke(1.dp, Color(0xFFA8E7C4))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Add, null, tint = Green)
                    Text("添加 · ${visuals.size}/9", color = Quiet, fontSize = 12.sp)
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            visuals.toList().forEachIndexed { index, choice ->
            Column(Modifier.width(100.dp)) {
                val file = when (choice) {
                    is MediaChoice.Existing -> repository.files.resolve(choice.value.relativePath)
                    is MediaChoice.New -> choice.value.pendingFile
                }
                val kind = when (choice) {
                    is MediaChoice.Existing -> choice.value.kind
                    is MediaChoice.New -> choice.value.kind
                }
                MediaThumbnail(file, kind)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("←", Modifier.clickable(enabled = index > 0) {
                            val previous = visuals[index - 1]
                            visuals[index - 1] = visuals[index]
                            visuals[index] = previous
                        }.padding(5.dp), color = if (index > 0) Quiet else Line, fontSize = 17.sp)
                    Text("→", Modifier.clickable(enabled = index < visuals.lastIndex) {
                            val next = visuals[index + 1]
                            visuals[index + 1] = visuals[index]
                            visuals[index] = next
                        }.padding(5.dp), color = if (index < visuals.lastIndex) Quiet else Line, fontSize = 17.sp)
                    Text("×", Modifier.clickable {
                            val removed = visuals.removeAt(index)
                            if (removed is MediaChoice.New) repository.files.discard(removed.value)
                        }.padding(5.dp), color = Quiet, fontSize = 17.sp)
                }
            }
            }
            }
        }
        item {
            SectionTitle("音频")
            Spacer(Modifier.height(12.dp))
            OutlinePanel(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) {
                    Text(if (audio == null) "＋ 添加一段音频" else "更换音频", color = Green)
                }
                if (audio != null) TextButton(onClick = {
                    (audio as? AudioChoice.New)?.let { repository.files.discard(it.value) }
                    audio = null
                }) { Text("移除", color = Quiet) }
            } }
            when (val selected = audio) {
                is AudioChoice.Existing -> InlineAudio(repository.files.resolve(selected.value.relativePath))
                is AudioChoice.New -> InlineAudio(selected.value.pendingFile)
                null -> Unit
            }
        }
        item {
            SectionTitle("位置")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(placeName, { placeName = it; selectedPlace = null },
                placeholder = { Text("添加地点名称（可选）", color = Quiet) }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green))
            TextButton(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("使用当前位置") }
        }
        item {
            SectionTitle("感受")
            Spacer(Modifier.height(5.dp))
            Text("可选 · 默认本机保存 · 浏览时需刮开", color = Quiet, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            if (feelingLocked) {
                Button(onClick = onUnlock) { Text("解锁后填写感受") }
            } else OutlinedTextField(feeling, { feeling = it }, placeholder = { Text("写下这一刻的心情") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green))
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
    }

    extraUris?.takeUnless { LocalImprintsLocked.current }?.let { uris ->
        val remaining = (9 - visuals.size).coerceAtLeast(0)
        AlertDialog(onDismissRequest = { extraUris = null }, title = { Text("超过媒体上限") },
            text = { Text("本次选了 ${uris.size} 个，只能再添加 $remaining 个。是否加入前 $remaining 个？") },
            confirmButton = { TextButton(onClick = { extraUris = null; prepareUris(uris.take(remaining)) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { extraUris = null }) { Text("取消") } })
    }
    if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
        title = { Text("放弃未保存的修改？") },
        text = { Text("当前输入和新选的附件尚未保存。") },
        confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { exitRequested = false }) { Text("继续编辑") } })
}
