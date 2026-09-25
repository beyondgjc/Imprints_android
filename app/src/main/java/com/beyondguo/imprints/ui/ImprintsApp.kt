package com.beyondguo.imprints.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.beyondguo.imprints.backup.BackupPreview
import com.beyondguo.imprints.backup.BackupService
import com.beyondguo.imprints.data.ImprintDatabase
import com.beyondguo.imprints.data.EntryEntity
import com.beyondguo.imprints.data.ImprintRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.net.Uri

private sealed interface Page {
    data object Timeline : Page
    data object Settings : Page
    data object Recycle : Page
    data object Search : Page
    data class Detail(val id: String) : Page
    data class Editor(val id: String?) : Page
    data class LocationDetail(val place: com.beyondguo.imprints.data.PlaceEntity) : Page
    data class RecycleDetail(val id: String) : Page
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImprintsApp(
    repository: ImprintRepository,
    initialized: Boolean,
    locked: Boolean,
    revealEpoch: Int,
    unlockApp: suspend (String) -> Boolean,
    refreshLockState: suspend () -> Unit,
    requestBiometric: (String, () -> Unit, () -> Unit) -> Unit,
    onPrivacyFlagsChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by remember { mutableStateOf<Page>(Page.Timeline) }
    var pendingScrollToId by remember { mutableStateOf<String?>(null) }
    var pinAction by remember { mutableStateOf<String?>(null) }
    var pinTitle by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmDisableFeeling by remember { mutableStateOf(false) }
    var securityEpoch by remember { mutableStateOf(0) }
    val security = repository.security
    val context = LocalContext.current
    val backup = remember(repository) { BackupService(context, ImprintDatabase.get(context), repository) }
    var backupAction by remember { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var backupPreview by remember { mutableStateOf<BackupPreview?>(null) }
    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportPreview by remember { mutableStateOf<BackupPreview?>(null) }
    fun report(error: Throwable) { scope.launch { snackbar.showSnackbar(error.message ?: "操作失败") } }
    fun askPin(action: String, title: String) { pinAction = action; pinTitle = title }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) pendingExportUri = uri else backupPassword = ""
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; backupAction = "import" }
    }
    val active by repository.active.collectAsState(initial = emptyList())
    val recycled by repository.recycled.collectAsState(initial = emptyList())
    var feelingMode by remember { mutableStateOf("OFF") }
    var appLockEnabled by remember { mutableStateOf(false) }
    var appBioEnabled by remember { mutableStateOf(false) }
    var feelingBioEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(securityEpoch, initialized) {
        if (initialized) {
            val state = security.state()
            feelingMode = state.feelingMode
            appLockEnabled = state.appLockHeaderJson != null
            appBioEnabled = state.appLockBiometricEnabled
            feelingBioEnabled = state.feelingBiometricEnvelope != null
        }
    }

    fun tryBiometric(
        target: String,
        after: () -> Unit = {},
        fallback: () -> Unit = {
            scope.launch { snackbar.showSnackbar("请使用对应的 6 位口令") }
        },
        onSuccess: () -> Unit = {},
        onUnavailable: (() -> Unit)? = null,
    ) {
        requestBiometric(
            target,
            { securityEpoch++; after(); onSuccess() },
            onUnavailable ?: fallback,
        )
    }

    LaunchedEffect(pendingExportUri, pendingImportPreview, locked, securityEpoch, initialized) {
        if (!initialized || locked) return@LaunchedEffect
        val exportUri = pendingExportUri
        val importPreview = pendingImportPreview
        if (exportUri == null && importPreview == null) return@LaunchedEffect
        if (security.state().feelingMode == "ON" && !security.isFeelingUnlocked()) {
            askPin(if (exportUri != null) "feeling-export" else "feeling-import", "验证感受口令后继续备份")
            return@LaunchedEffect
        }
        if (exportUri != null) {
            pendingExportUri = null
            val password = backupPassword.toCharArray()
            scope.launch {
                runCatching { backup.export(exportUri, password) }
                    .onSuccess { snackbar.showSnackbar("加密备份已导出") }.onFailure(::report)
                password.fill('\u0000')
                backupPassword = ""
            }
        } else if (importPreview != null) {
            pendingImportPreview = null
            val uri = importUri ?: return@LaunchedEffect
            val password = backupPassword.toCharArray()
            scope.launch {
                runCatching { backup.import(uri, password, importPreview,
                    allowPlaintextConversion = importPreview.feelingMode == "ON" && feelingMode == "OFF") }
                    .onSuccess { snackbar.showSnackbar("已导入 $it 条印迹") }.onFailure(::report)
                password.fill('\u0000')
                backupPassword = ""
                importUri = null
            }
        }
    }

    if (!initialized) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("正在加载印迹…") }
        return
    }
    Box(Modifier.fillMaxSize()) {
    BackHandler(locked) { }
    BackHandler(page != Page.Timeline && !locked) {
        page = when (page) {
            is Page.RecycleDetail -> Page.Recycle
            else -> Page.Timeline
        }
    }
    CompositionLocalProvider(LocalImprintsLocked provides locked) {
    Scaffold(
        modifier = if (locked) Modifier.clearAndSetSemantics { } else Modifier,
        topBar = {
            if (page != Page.Timeline && page !is Page.Editor) CenterAlignedTopAppBar(
                title = { Text(when (page) {
                    Page.Timeline -> "我的印迹"; Page.Settings -> "设置"; Page.Recycle -> "回收站"
                    Page.Search -> "搜索正文"; is Page.Detail -> "印迹详情"
                    is Page.RecycleDetail -> "回收站详情"
                    is Page.Editor -> if ((page as Page.Editor).id == null) "新建印迹" else "编辑印迹"
                    is Page.LocationDetail -> "位置详情"
                }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = {
                    page = when (page) {
                        is Page.RecycleDetail -> Page.Recycle
                        else -> Page.Timeline
                    }
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Ink) } },
                actions = {
                    // 设计 41：详情顶栏右上角「编辑」
                    if (page is Page.Detail) {
                        TextButton(onClick = { page = Page.Editor((page as Page.Detail).id) }) {
                            Text("编辑", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (page == Page.Timeline) FloatingActionButton(
            onClick = { page = Page.Editor(null) }, shape = CircleShape, containerColor = Green,
            contentColor = Color.White) { Icon(Icons.Default.Add, "新建印迹") } },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when (val current = page) {
                Page.Timeline -> TimelinePage(active, repository, revealEpoch + securityEpoch,
                    onOpen = { page = Page.Detail(it) },
                    onSearch = { page = Page.Search }, onSettings = { page = Page.Settings },
                    onUnlock = { askPin("feeling-unlock", "解锁感受") },
                    onBiometric = {
                        tryBiometric(
                            "feeling-unlock",
                            onUnavailable = { askPin("feeling-unlock", "解锁感受") },
                        )
                    },
                    scrollToId = pendingScrollToId,
                    onScrollConsumed = { pendingScrollToId = null },
                    onOpenPlace = { place -> page = Page.LocationDetail(place) })
                Page.Settings -> SettingsPage(feelingMode, feelingBioEnabled, appLockEnabled, appBioEnabled,
                    onRecycle = { page = Page.Recycle },
                    onFeeling = {
                        if (feelingMode == "OFF") askPin("feeling-enable", "设置感受 6 位口令")
                        else confirmDisableFeeling = true
                    },
                    onFeelingBio = {
                        if (feelingBioEnabled) scope.launch {
                            runCatching { security.disableFeelingBiometric() }.onSuccess { securityEpoch++ }.onFailure(::report)
                        } else if (security.isFeelingUnlocked()) {
                            tryBiometric(
                                "feeling-enable",
                                onSuccess = { scope.launch { snackbar.showSnackbar("感受生物识别已开启") } },
                                onUnavailable = {
                                    scope.launch {
                                        snackbar.showSnackbar("指纹验证未弹出或未完成，感受生物识别未开启")
                                    }
                                },
                            )
                        } else {
                            askPin("feeling-bio", "先验证感受口令，再开启生物识别")
                        }
                    },
                    onFeelingPinChange = {
                        if (feelingBioEnabled) tryBiometric("feeling-unlock",
                            after = { askPin("feeling-change-new", "设置新的感受 6 位口令") },
                            fallback = { askPin("feeling-change-old", "验证当前感受口令") })
                        else askPin("feeling-change-old", "验证当前感受口令")
                    },
                    onAppLock = {
                        if (appLockEnabled) askPin("app-disable", "验证应用锁口令")
                        else askPin("app-enable", "设置应用锁 6 位口令")
                    },
                    onAppBio = {
                        if (appBioEnabled) scope.launch {
                            runCatching { security.setAppLockBiometricEnabled(false) }.onSuccess { securityEpoch++ }.onFailure(::report)
                        } else if (appLockEnabled) {
                            // 开启前先验证应用锁口令（与 PRD 一致）
                            askPin("app-bio", "验证应用锁口令后开启生物识别")
                        } else {
                            tryBiometric("app-enable",
                                onSuccess = { scope.launch { snackbar.showSnackbar("应用锁生物识别已开启") } })
                        }
                    },
                    onAppPinChange = {
                        if (appBioEnabled) tryBiometric("app-change",
                            after = { askPin("app-change-new", "设置新的应用锁 6 位口令") },
                            fallback = { askPin("app-change-old", "验证当前应用锁口令") })
                        else askPin("app-change-old", "验证当前应用锁口令")
                    },
                    onExport = { backupAction = "export" },
                    onImport = { openBackup.launch(arrayOf("*/*")) },
                    onLockNow = { scope.launch { refreshLockState() } },
                    onPrivacyFlagsChanged = onPrivacyFlagsChanged,
                )
                Page.Recycle -> RecyclePage(recycled,
                    onOpen = { page = Page.RecycleDetail(it) },
                    onRestore = { id ->
                        scope.launch {
                            runCatching { repository.restore(id) }
                                .onSuccess {
                                    snackbar.showSnackbar("已恢复到时间线")
                                    page = Page.Timeline
                                }
                                .onFailure(::report)
                        }
                    },
                    onDelete = { confirmDelete = it })
                Page.Search -> SearchPage(repository, onOpen = {
                    pendingScrollToId = it
                    page = Page.Timeline
                }, report = ::report)
                is Page.Detail -> DetailPage(repository, current.id, revealEpoch + securityEpoch,
                    onEdit = { page = Page.Editor(current.id) },
                    onDelete = {
                        scope.launch {
                            runCatching { repository.moveToRecycle(current.id) }
                                .onSuccess {
                                    snackbar.showSnackbar("已移入回收站 · 可在回收站恢复")
                                    page = Page.Timeline
                                }
                                .onFailure(::report)
                        }
                    },
                    onUnlock = { askPin("feeling-unlock", "解锁感受") },
                    onBiometric = {
                        tryBiometric(
                            "feeling-unlock",
                            onUnavailable = { askPin("feeling-unlock", "解锁感受") },
                        )
                    },
                    onOpenPlace = { place -> page = Page.LocationDetail(place) })
                is Page.RecycleDetail -> DetailPage(repository, current.id, revealEpoch + securityEpoch,
                    onEdit = {},
                    onDelete = {
                        scope.launch {
                            runCatching { repository.deletePermanently(current.id) }
                                .onSuccess { snackbar.showSnackbar("已彻底删除") }
                                .onFailure(::report)
                            page = Page.Recycle
                        }
                    },
                    onUnlock = { askPin("feeling-unlock", "解锁感受") },
                    onBiometric = {
                        tryBiometric(
                            "feeling-unlock",
                            onUnavailable = { askPin("feeling-unlock", "解锁感受") },
                        )
                    },
                    onOpenPlace = { place -> page = Page.LocationDetail(place) },
                    recycleActions = {
                        scope.launch {
                            runCatching { repository.restore(current.id) }
                                .onSuccess {
                                    snackbar.showSnackbar("已恢复到时间线")
                                    page = Page.Timeline
                                }
                                .onFailure(::report)
                        }
                    })
                is Page.LocationDetail -> {
                    val place = current.place
                    val related = active.filter { entry -> entry.placeId == place.id }
                    LocationDetailPage(
                        place = place,
                        relatedEntries = related,
                        onBack = { page = Page.Timeline },
                        onOpenEntry = { page = Page.Detail(it) },
                    )
                }
                is Page.Editor -> EditorPage(repository, current.id, revealEpoch + securityEpoch,
                    onSaved = { page = Page.Detail(it) },
                    onCancel = { page = Page.Timeline },
                    onUnlock = { askPin("feeling-unlock", "解锁感受") },
                    onBiometric = {
                        tryBiometric(
                            "feeling-unlock",
                            onUnavailable = { askPin("feeling-unlock", "解锁感受") },
                        )
                    },
                    report = ::report)
            }
        }
    }
    }

    if (!locked) pinAction?.let { action ->
        PinDialog(pinTitle, setup = action in setOf("feeling-enable", "app-enable", "feeling-change-new", "app-change-new"), onDismiss = {
            pinAction = null
            if (action == "feeling-export") { pendingExportUri = null; backupPassword = "" }
            if (action == "feeling-import") { pendingImportPreview = null; backupPassword = ""; importUri = null }
        }) { pin ->
            pinAction = null
            scope.launch {
                runCatching {
                    when (action) {
                        "feeling-enable" -> repository.exclusive { security.enableFeeling(pin) }
                        "feeling-unlock" -> check(security.unlockFeelingPin(pin)) { "感受口令错误或暂时被限制" }
                        "feeling-export", "feeling-import" -> check(security.unlockFeelingPin(pin)) { "感受口令错误或暂时被限制" }
                        "feeling-disable" -> { check(security.unlockFeelingPin(pin)) { "感受口令错误或暂时被限制" }; repository.exclusive { security.disableFeeling() } }
                        "feeling-bio" -> {
                            check(security.unlockFeelingPin(pin)) { "感受口令错误或暂时被限制" }
                            tryBiometric(
                                "feeling-enable",
                                onSuccess = { scope.launch { snackbar.showSnackbar("感受生物识别已开启") } },
                                onUnavailable = {
                                    scope.launch {
                                        snackbar.showSnackbar("指纹验证未弹出或未完成，感受生物识别未开启")
                                    }
                                },
                            )
                        }
                        "app-bio" -> {
                            check(security.verifyAppLock(pin)) { "应用锁口令错误" }
                            tryBiometric("app-enable",
                                onSuccess = { scope.launch { snackbar.showSnackbar("应用锁生物识别已开启") } })
                        }
                        "feeling-change-old" -> { check(security.unlockFeelingPin(pin)) { "感受口令错误或暂时被限制" }; askPin("feeling-change-new", "设置新的感受 6 位口令") }
                        "feeling-change-new" -> security.resetFeelingPin(pin)
                        "app-enable" -> { security.setAppLock(pin); refreshLockState() }
                        "app-disable" -> { check(security.verifyAppLock(pin)) { "应用锁口令错误" }; security.disableAppLock(); refreshLockState() }
                        "app-change-old" -> { check(security.verifyAppLock(pin)) { "应用锁口令错误" }; askPin("app-change-new", "设置新的应用锁 6 位口令") }
                        "app-change-new" -> { security.setAppLock(pin); refreshLockState() }
                    }
                }.onSuccess { securityEpoch++ }.onFailure { error ->
                    if (action == "feeling-export") { pendingExportUri = null; backupPassword = "" }
                    if (action == "feeling-import") { pendingImportPreview = null; backupPassword = ""; importUri = null }
                    report(error)
                }
            }
        }
    }
    if (!locked) backupAction?.let { action ->
        BackupPasswordDialog(if (action == "export") "设置备份口令" else "输入备份口令",
            onDismiss = { backupAction = null; backupPassword = "" }) { password ->
            backupAction = null
            backupPassword = password
            if (action == "export") {
                createBackup.launch("imprints-${java.time.LocalDate.now()}.imprints")
            }
            else importUri?.let { uri -> scope.launch {
                runCatching { backup.preview(uri, password.toCharArray()) }
                    .onSuccess { backupPreview = it }.onFailure { report(it); backupPassword = "" }
            } }
        }
    }
    if (!locked) backupPreview?.let { preview ->
        AlertDialog(onDismissRequest = { backupPreview = null; backupPassword = "" },
            title = { Text("导入预览") },
            text = { Text(buildString {
                append("印迹 ${preview.entries} 条，附件 ${preview.attachments} 个；重复 ${preview.duplicates} 条，过期回收站 ${preview.expired} 条。")
                append(" 待写入附件约 ${preview.attachmentBytes / (1024 * 1024)} MiB，导入期间需额外空间。")
                if (preview.feelingMode == "ON" && feelingMode == "OFF") append(" 备份中的感受原为加密状态，导入本机后将以明文保存。")
            }) },
            confirmButton = { TextButton(onClick = {
                backupPreview = null
                if (importUri != null) pendingImportPreview = preview
            }) { Text(if (preview.feelingMode == "ON" && feelingMode == "OFF") "明文导入" else "导入") } },
            dismissButton = { TextButton(onClick = { backupPreview = null; backupPassword = "" }) { Text("取消") } })
    }
    if (!locked) confirmDelete?.let { id ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("立即彻底删除？") },
            text = { Text("删除后无法恢复，正文、感受\n和全部附件都会被永久移除。") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                scope.launch { runCatching { repository.deletePermanently(id) }.onFailure(::report) }
            }) { Text("立即删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } })
    }
    if (!locked && confirmDisableFeeling) AlertDialog(
        onDismissRequest = { confirmDisableFeeling = false },
        title = { Text("关闭感受加密？") },
        text = { Text("关闭后，已有和新写入的感受将以明文保存在本机。关闭前需验证感受口令。") },
        confirmButton = { TextButton(onClick = {
            confirmDisableFeeling = false
            askPin("feeling-disable", "验证感受 6 位口令")
        }) { Text("确认关闭") } },
        dismissButton = { TextButton(onClick = { confirmDisableFeeling = false }) { Text("取消") } },
    )
    if (locked) {
        var lockError by remember(securityEpoch) { mutableStateOf<String?>(null) }
        var unlocking by remember(securityEpoch) { mutableStateOf(false) }

        // 冷启动/回锁且已开应用锁指纹时自动拉起（对齐 Penly LockScreen）：
        // 必须等本应用窗口真正拿到焦点——启动动画期间 Activity 已 RESUMED 但桌面仍在最上层，
        // 无焦点时弹认证会被系统当后台认证立刻取消。每次进锁屏只自动拉一次。
        var autoPrompted by remember(securityEpoch) { mutableStateOf(false) }
        val view = LocalView.current
        var windowFocused by remember { mutableStateOf(view.hasWindowFocus()) }
        DisposableEffect(view) {
            val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                windowFocused = hasFocus
            }
            view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }
        LaunchedEffect(locked, windowFocused, appBioEnabled, securityEpoch) {
            if (!locked || autoPrompted || !appBioEnabled || !windowFocused) return@LaunchedEffect
            autoPrompted = true
            delay(200) // 等窗口完成首帧渲染
            lockError = null
            tryBiometric(
                "app-unlock",
                onUnavailable = { /* 取消/不可用：留在锁屏，用户可改输口令 */ },
            )
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable { }) {
            LockPage(
                biometricAvailable = appBioEnabled,
                error = lockError,
                unlocking = unlocking,
                onPin = { pin ->
                    scope.launch {
                        if (unlocking) return@launch
                        unlocking = true
                        lockError = null
                        try {
                            if (!unlockApp(pin)) lockError = "口令错误或暂时被限制，请重试"
                        } catch (error: Exception) {
                            lockError = error.message ?: "解锁失败，请重试"
                        } finally {
                            unlocking = false
                        }
                    }
                },
                onBiometric = {
                    lockError = null
                    tryBiometric(
                        "app-unlock",
                        onUnavailable = { /* 保持锁屏 */ },
                    )
                },
            )
        }
    }
    }
}

@Composable
private fun LockPage(
    biometricAvailable: Boolean,
    error: String?,
    unlocking: Boolean,
    onPin: (String) -> Unit,
    onBiometric: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    // 底部 padding 大于顶部 → 静态整体偏上；imePadding → 键盘弹起时内容上移，解锁键不被遮挡
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text("印迹", color = Green, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(36.dp))
        PinField(pin) { pin = it; }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error, color = Color(0xFFD92D20), fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { val entered = pin; pin = ""; onPin(entered) },
            enabled = pin.length == 6 && !unlocking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (unlocking) "验证中…" else "解锁") }
        if (biometricAvailable) TextButton(onClick = onBiometric) { Text("使用生物识别") }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit) {
    // 仅允许 ASCII 数字：与 PinPolicy 的 [0-9]{6} 对齐，避免全角数字通过输入却过不了校验
    OutlinedTextField(value = value, onValueChange = { input ->
        val filtered = input.filter { it in '0'..'9' }.take(6)
        if (filtered != value) onChange(filtered)
    },
        label = { Text("6 位数字口令") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green))
}

@Composable
private fun PinDialog(title: String, setup: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember(title) { mutableStateOf("") }
    var confirm by remember(title) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column {
            PinField(pin) { pin = it }
            if (setup) { Spacer(Modifier.height(12.dp)); PinField(confirm) { confirm = it }; Text("再次输入确认口令") }
        } },
        confirmButton = { TextButton(onClick = { onConfirm(pin) }, enabled = pin.length == 6 && (!setup || pin == confirm)) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), containerColor = Color.White)
}

@Composable
private fun SettingsPage(
    feelingMode: String, feelingBio: Boolean, appLock: Boolean, appBio: Boolean,
    onRecycle: () -> Unit, onFeeling: () -> Unit, onFeelingBio: () -> Unit, onFeelingPinChange: () -> Unit,
    onAppLock: () -> Unit, onAppBio: () -> Unit, onAppPinChange: () -> Unit,
    onLockNow: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit,
    onPrivacyFlagsChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    var secureWindow by remember {
        mutableStateOf(com.beyondguo.imprints.privacy.PrivacyPreferences.isEnabled(context))
    }
    // 关于：仅展示当前版本（不做检查更新——仓库私有，应用内拉不到版本信息）
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }
    // 对齐 Penly：小节标签 + 浅灰分组卡 + 紧凑行，去掉一卡一项与大段说明
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SettingsSectionLabel("隐私与安全") }
        item {
            SettingsGroup {
                // 立即锁定（对齐 Penly 安全组首位）：仅在应用锁已开启时出现，
                // 否则锁上后没有口令可解，会把自己关在外面
                if (appLock) {
                    SettingsRow(
                        title = "立即锁定",
                        subtitle = "清除会话，回到锁屏",
                        onClick = onLockNow,
                    )
                    FineDivider()
                }
                SettingsSwitchRow(
                    title = "感受单独加密",
                    subtitle = if (feelingMode == "ON") "已开启 · 查看前先验证，再刮开涂层"
                    else "保存后绿涂层遮挡，浏览时刮开",
                    checked = feelingMode == "ON",
                    onCheckedChange = { onFeeling() },
                )
                if (feelingMode == "ON") {
                    FineDivider()
                    SettingsRow(
                        title = "修改感受口令",
                        subtitle = "6 位数字",
                        onClick = onFeelingPinChange,
                        indent = true,
                    )
                    FineDivider()
                    SettingsSwitchRow(
                        title = "感受生物识别",
                        subtitle = if (feelingBio) "已开启 · 查看时优先指纹" else "用于查看加密感受",
                        checked = feelingBio,
                        onCheckedChange = { onFeelingBio() },
                        indent = true,
                    )
                }
                FineDivider()
                SettingsSwitchRow(
                    title = "应用锁",
                    subtitle = if (appLock) "已开启 · 每次重新进入应用都需验证"
                    else "打开应用时验证 6 位口令",
                    checked = appLock,
                    onCheckedChange = { onAppLock() },
                )
                if (appLock) {
                    FineDivider()
                    SettingsRow(
                        title = "修改解锁密码",
                        subtitle = "6 位数字",
                        onClick = onAppPinChange,
                        indent = true,
                    )
                    FineDivider()
                    SettingsSwitchRow(
                        title = "应用锁生物识别",
                        subtitle = if (appBio) "已开启 · 冷启动自动拉起指纹" else "用于解锁应用",
                        checked = appBio,
                        onCheckedChange = { onAppBio() },
                        indent = true,
                    )
                }
                FineDivider()
                SettingsSwitchRow(
                    title = "防截屏与多任务保护",
                    subtitle = "无法截屏/录屏，多任务不显示页面详情",
                    checked = secureWindow,
                    onCheckedChange = { value ->
                        secureWindow = value
                        com.beyondguo.imprints.privacy.PrivacyPreferences.setEnabled(context, value)
                        onPrivacyFlagsChanged()
                    },
                )
            }
        }

        item { SettingsSectionLabel("数据") }
        item {
            SettingsGroup {
                SettingsRow("导出备份", "包含本机全部印迹和附件 · 需独立口令", onClick = onExport)
                FineDivider()
                SettingsRow("导入备份", "先校验和预览，不覆盖现有数据", onClick = onImport)
                FineDivider()
                SettingsRow("回收站", "删除的印迹保留 7 天", onClick = onRecycle)
            }
        }

        item { SettingsSectionLabel("关于") }
        item {
            SettingsGroup {
                SettingsRow(
                    title = "版本",
                    trailing = { Text("v$versionName", color = Quiet, fontSize = 13.sp) },
                )
            }
        }

        item {
            Text(
                "不有佳咏，何伸雅怀？",
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 20.dp),
                color = Green,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BackupPasswordDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember(title) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(password, { password = it }, label = { Text("至少 8 位") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green)) },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.length >= 8) { Text("继续") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), containerColor = Color.White)
}

@Composable
private fun RecyclePage(entries: List<EntryEntity>, onOpen: (String) -> Unit,
    onRestore: (String) -> Unit, onDelete: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { OutlinePanel(Modifier.fillMaxWidth()) {
            Text("删除的印迹会在这里保留 7 天，之后自动清除。", Modifier.padding(18.dp),
                color = Quiet, fontSize = 14.sp, lineHeight = 22.sp)
        } }
        item { Text("${entries.size} 条印迹", Modifier.padding(top = 10.dp), color = Quiet, fontSize = 14.sp) }
        if (entries.isEmpty()) {
            item { Text("回收站是空的", Modifier.fillMaxWidth().padding(top = 48.dp),
                color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            item { Text("移入的印迹会在这里保留 7 天。", Modifier.fillMaxWidth(),
                color = Quiet, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        items(entries, key = { it.id }) { entry ->
            OutlinePanel(Modifier.fillMaxWidth().clickable { onOpen(entry.id) }) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(entry.eventLocalMinute.replace('T', ' '), color = Quiet, fontSize = 12.sp)
                Text(entry.body.ifBlank { "媒体印迹" }, color = Ink, fontSize = 16.sp, maxLines = 3)
                Text(entry.deletedAtMs?.let(::RecycleCountdown) ?: "", color = Quiet, fontSize = 12.sp)
                Text("到期后自动彻底删除", color = Quiet, fontSize = 11.sp)
                FineDivider()
                Row { TextButton(onClick = { onRestore(entry.id) }) { Text("恢复", color = Green) }
                    TextButton(onClick = { onDelete(entry.id) }) { Text("彻底删除", color = Quiet) } }
            } }
        }
        if (entries.isNotEmpty()) item {
            Text("按移入时间排列；点按查看详情，可恢复或彻底删除。",
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                color = Quiet, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun SearchPage(repository: ImprintRepository, onOpen: (String) -> Unit,
    report: (Throwable) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<EntryEntity>()) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(query) {
        if (query.isBlank()) results = emptyList()
        else runCatching { repository.searchBody(query) }.onSuccess { results = it }.onFailure(report)
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索印迹正文") },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green))
        Spacer(Modifier.height(20.dp))
        if (query.isBlank()) Column(
            Modifier.fillMaxWidth().padding(top = 44.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("想找回哪一刻？", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("搜索你写过的事情。", color = Quiet, fontSize = 13.sp)
        }
        if (query.isNotBlank() && results.isEmpty()) Column(
            Modifier.fillMaxWidth().padding(top = 44.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("没有找到相关印迹", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("换一个词，试试事情的写法。", color = Quiet, fontSize = 13.sp)
        }
        if (query.isNotBlank() && results.isNotEmpty()) {
            Text("找到 ${results.size} 条印迹", color = Quiet, fontSize = 13.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(results, key = { it.id }) { entry ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(entry.id) }.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatSearchDate(entry.eventLocalMinute), color = Quiet, fontSize = 12.sp)
                Text(entry.body, color = Ink, fontSize = 16.sp, lineHeight = 24.sp, maxLines = 3)
                FineDivider()
            }
        } }
    }
}

private fun formatSearchDate(minute: String): String = runCatching {
    val date = java.time.LocalDate.parse(minute.take(10))
    val weekday = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
    "${date.monthValue}月${date.dayOfMonth}日 · $weekday"
}.getOrElse { minute.replace('T', ' ') }
