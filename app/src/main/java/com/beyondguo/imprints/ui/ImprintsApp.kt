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
import com.beyondguo.imprints.R
import androidx.compose.ui.res.stringResource

private sealed interface Page {
    data object Timeline : Page
    data object Settings : Page
    data object Recycle : Page
    data object Tags : Page
    data object Search : Page
    data object PrivacyPolicy : Page
    data object UserAgreement : Page
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
    onRevokeConsent: () -> Unit = {},
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
    // 时间线标签筛选：状态放在导航层，进出详情/编辑后仍保留筛选
    var selectedTagIds by remember { mutableStateOf(emptySet<String>()) }
    // 订阅标签表而不是启动时取一次快照：新建/改名/删除后详情页与筛选栏必须立刻见到新名称
    val tagList by repository.tags.collectAsState(initial = emptyList())
    val tagNames = remember(tagList) { tagList.associate { it.id to it.name } }
    val security = repository.security
    val context = LocalContext.current
    val backup = remember(repository) { BackupService(context, ImprintDatabase.get(context), repository) }
    var backupAction by remember { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var backupPreview by remember { mutableStateOf<BackupPreview?>(null) }
    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportPreview by remember { mutableStateOf<BackupPreview?>(null) }
    // 这些文案在 lambda / 非 Composable 上下文里被引用（report、scope.launch、askPin），
    // 不能在那里直接调 stringResource，所以在 Composable 入口先取成局部 val 闭包捕获。
    val errGeneric = stringResource(R.string.err_generic)
    val bioUsePinFallback = stringResource(R.string.bio_use_pin_fallback)
    val exportDone = stringResource(R.string.export_done)
    val recycleRestored = stringResource(R.string.recycle_restored)
    // 带数量的文案：运行时用 Resources 格式化（locale 感知）。
    // 用 context.resources 而非 context.getString，避免在 lambda 里查资源触发 lint 警告。
    val importDone: (Int) -> String = { n -> context.resources.getString(R.string.import_done, n) }
    val recycleMoved = stringResource(R.string.recycle_moved)
    val deleteDone = stringResource(R.string.delete_done)
    val bioFeelingEnabled = stringResource(R.string.bio_feeling_enabled)
    val bioFeelingFailed = stringResource(R.string.bio_feeling_failed)
    val bioAppEnabled = stringResource(R.string.bio_app_enabled)
    val pinUnlockFeeling = stringResource(R.string.pin_unlock_feeling)
    val pinSetFeeling = stringResource(R.string.pin_set_feeling)
    val pinSetFeelingNew = stringResource(R.string.pin_set_feeling_new)
    val pinVerifyFeelingCurrent = stringResource(R.string.pin_verify_feeling_current)
    val pinVerifyFeelingBackup = stringResource(R.string.pin_verify_feeling_backup)
    val pinVerifyBeforeBio = stringResource(R.string.pin_verify_before_bio)
    val errFeelingPin = stringResource(R.string.err_feeling_pin)
    val pinVerifyApp = stringResource(R.string.pin_verify_app)
    val pinSetApp = stringResource(R.string.pin_set_app)
    val pinSetAppNew = stringResource(R.string.pin_set_app_new)
    val pinVerifyAppCurrent = stringResource(R.string.pin_verify_app_current)
    val pinVerifyAppBeforeBio = stringResource(R.string.pin_verify_app_before_bio)
    val errAppPin = stringResource(R.string.err_app_pin)
    val backupSetPassword = stringResource(R.string.backup_set_password)
    val backupInputPassword = stringResource(R.string.backup_input_password)
    val pageSearch = stringResource(R.string.page_search)
    val pageDetail = stringResource(R.string.page_detail)
    val pageRecycleDetail = stringResource(R.string.page_recycle_detail)
    val pageLocationDetail = stringResource(R.string.page_location_detail)
    val editorNewTitle = stringResource(R.string.editor_new_title)
    val editorEditTitle = stringResource(R.string.editor_edit_title)

    val pinVerifyFeeling6 = stringResource(R.string.pin_verify_feeling_6)
    val errPinLocked = stringResource(R.string.err_pin_locked)
    val errUnlockFailed = stringResource(R.string.err_unlock_failed)
    fun report(error: Throwable) { scope.launch { snackbar.showSnackbar(error.message ?: errGeneric) } }
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
            scope.launch { snackbar.showSnackbar(bioUsePinFallback) }
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
            askPin(if (exportUri != null) "feeling-export" else "feeling-import", pinVerifyFeelingBackup)
            return@LaunchedEffect
        }
        if (exportUri != null) {
            pendingExportUri = null
            val password = backupPassword.toCharArray()
            scope.launch {
                runCatching { backup.export(exportUri, password) }
                    .onSuccess { snackbar.showSnackbar(exportDone) }.onFailure(::report)
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
                    .onSuccess { count -> scope.launch { snackbar.showSnackbar(importDone(count)) } }.onFailure(::report)
                password.fill('\u0000')
                backupPassword = ""
                importUri = null
            }
        }
    }

    if (!initialized) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(stringResource(R.string.state_loading_timeline)) }
        return
    }
    Box(Modifier.fillMaxSize()) {
    BackHandler(locked) { }
    BackHandler(page != Page.Timeline && !locked) {
        page = when (page) {
            is Page.RecycleDetail -> Page.Recycle
            Page.Tags -> Page.Settings
            is Page.PrivacyPolicy, is Page.UserAgreement -> Page.Settings
            else -> Page.Timeline
        }
    }
    CompositionLocalProvider(LocalImprintsLocked provides locked) {
    Scaffold(
        modifier = if (locked) Modifier.clearAndSetSemantics { } else Modifier,
        topBar = {
            if (page != Page.Timeline && page !is Page.Editor) CenterAlignedTopAppBar(
                title = { Text(when (page) {
                    Page.Timeline -> stringResource(R.string.timeline_title); Page.Settings -> "设置"; Page.Recycle -> stringResource(R.string.settings_recycle)
                    Page.Tags -> stringResource(R.string.settings_tags)
                    Page.Search -> pageSearch; is Page.Detail -> pageDetail
                    is Page.RecycleDetail -> pageRecycleDetail
                    is Page.Editor -> if ((page as Page.Editor).id == null) editorNewTitle else editorEditTitle
                    is Page.LocationDetail -> pageLocationDetail
                    Page.PrivacyPolicy -> stringResource(R.string.settings_privacy_policy)
                    Page.UserAgreement -> stringResource(R.string.settings_user_agreement)
                }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = {
                    page = when (page) {
                        is Page.RecycleDetail -> Page.Recycle
                        Page.Tags -> Page.Settings
                        is Page.PrivacyPolicy, is Page.UserAgreement -> Page.Settings
                        else -> Page.Timeline
                    }
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Ink) } },
                actions = {
                    // 设计 41：详情顶栏右上角「编辑」
                    if (page is Page.Detail) {
                        TextButton(onClick = { page = Page.Editor((page as Page.Detail).id) }) {
                            Text(stringResource(R.string.action_edit), color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (page == Page.Timeline) FloatingActionButton(
            onClick = { page = Page.Editor(null) }, shape = CircleShape, containerColor = Green,
            contentColor = Color.White) { Icon(Icons.Default.Add, editorNewTitle) } },
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
                    onUnlock = { askPin("feeling-unlock", pinUnlockFeeling) },
                    onBiometric = {
                        tryBiometric(
                            "feeling-unlock",
                            onUnavailable = { askPin("feeling-unlock", pinUnlockFeeling) },
                        )
                    },
                    scrollToId = pendingScrollToId,
                    onScrollConsumed = { pendingScrollToId = null },
                    onOpenPlace = { place -> page = Page.LocationDetail(place) },
                    selectedTagIds = selectedTagIds,
                    onToggleTag = { id ->
                        selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id
                    },
                    onCreateTag = { name ->
                        scope.launch {
                            // 标签表已由 tagList 订阅，这里只需把新标签选中
                            runCatching { repository.createTag(name) }
                                .onSuccess { newId -> selectedTagIds = selectedTagIds + newId }
                                .onFailure(::report)
                        }
                    },
                    onError = ::report)
                Page.Settings -> SettingsPage(feelingMode, feelingBioEnabled, appLockEnabled, appBioEnabled,
                    onRecycle = { page = Page.Recycle },
                    onTags = { page = Page.Tags },
                    onFeeling = {
                        if (feelingMode == "OFF") askPin("feeling-enable", pinSetFeeling)
                        else confirmDisableFeeling = true
                    },
                    onFeelingBio = {
                        if (feelingBioEnabled) scope.launch {
                            runCatching { security.disableFeelingBiometric() }.onSuccess { securityEpoch++ }.onFailure(::report)
                        } else if (security.isFeelingUnlocked()) {
                            tryBiometric(
                                "feeling-enable",
                                onSuccess = { scope.launch { snackbar.showSnackbar(bioFeelingEnabled) } },
                                onUnavailable = {
                                    scope.launch {
                                        snackbar.showSnackbar(bioFeelingFailed)
                                    }
                                },
                            )
                        } else {
                            askPin("feeling-bio", pinVerifyBeforeBio)
                        }
                    },
                    onFeelingPinChange = {
                        if (feelingBioEnabled) tryBiometric("feeling-unlock",
                            after = { askPin("feeling-change-new", pinSetFeelingNew) },
                            fallback = { askPin("feeling-change-old", pinVerifyFeelingCurrent) })
                        else askPin("feeling-change-old", pinVerifyFeelingCurrent)
                    },
                    onAppLock = {
                        if (appLockEnabled) askPin("app-disable", pinVerifyApp)
                        else askPin("app-enable", pinSetApp)
                    },
                    onAppBio = {
                        if (appBioEnabled) scope.launch {
                            runCatching { security.setAppLockBiometricEnabled(false) }.onSuccess { securityEpoch++ }.onFailure(::report)
                        } else if (appLockEnabled) {
                            // 开启前先验证应用锁口令（与 PRD 一致）
                            askPin("app-bio", pinVerifyAppBeforeBio)
                        } else {
                            tryBiometric("app-enable",
                                onSuccess = { scope.launch { snackbar.showSnackbar(bioAppEnabled) } })
                        }
                    },
                    onAppPinChange = {
                        if (appBioEnabled) tryBiometric("app-change",
                            after = { askPin("app-change-new", pinSetAppNew) },
                            fallback = { askPin("app-change-old", pinVerifyAppCurrent) })
                        else askPin("app-change-old", pinVerifyAppCurrent)
                    },
                    onExport = { backupAction = "export" },
                    onImport = { openBackup.launch(arrayOf("*/*")) },
                    onLockNow = { scope.launch { refreshLockState() } },
                    onPrivacyFlagsChanged = onPrivacyFlagsChanged,
                    onOpenPrivacyPolicy = { page = Page.PrivacyPolicy },
                    onOpenUserAgreement = { page = Page.UserAgreement },
                    onRevokeConsent = onRevokeConsent,
                )
                Page.PrivacyPolicy -> PolicyDocumentPage(stringResource(R.string.settings_privacy_policy), "privacy_policy.txt") { page = Page.Settings }
                Page.UserAgreement -> PolicyDocumentPage(stringResource(R.string.settings_user_agreement), "user_agreement.txt") { page = Page.Settings }
                Page.Recycle -> RecyclePage(recycled,
                    onOpen = { page = Page.RecycleDetail(it) },
                    onRestore = { id ->
                        scope.launch {
                            runCatching { repository.restore(id) }
                                .onSuccess {
                                    snackbar.showSnackbar(recycleRestored)
                                    page = Page.Timeline
                                }
                                .onFailure(::report)
                        }
                    },
                    onDelete = { confirmDelete = it })
                Page.Tags -> TagsPage(
                    repository = repository,
                    onRename = { id, name ->
                        scope.launch { runCatching { repository.renameTag(id, name) }.onFailure(::report) }
                    },
                    onDelete = { id ->
                        scope.launch {
                            runCatching { repository.deleteTag(id) }
                                .onSuccess { selectedTagIds = selectedTagIds - id }
                                .onFailure(::report)
                        }
                    },
                    onCreate = { name ->
                        scope.launch { runCatching { repository.createTag(name) }.onFailure(::report) }
                    },
                    onError = ::report,
                )
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
                                    snackbar.showSnackbar(recycleMoved)
                                    page = Page.Timeline
                                }
                                .onFailure(::report)
                        }
                    },
                    onUnlock = { askPin("feeling-unlock", pinUnlockFeeling) },
                    onBiometric = {
                        tryBiometric(
                            "feeling-unlock",
                            onUnavailable = { askPin("feeling-unlock", pinUnlockFeeling) },
                        )
                    },
                    onOpenPlace = { place -> page = Page.LocationDetail(place) },
                    tagNames = tagNames)
                is Page.RecycleDetail -> DetailPage(repository, current.id, revealEpoch + securityEpoch,
                    onEdit = {},
                    onDelete = {
                        scope.launch {
                            runCatching { repository.deletePermanently(current.id) }
                                .onSuccess { snackbar.showSnackbar(deleteDone) }
                                .onFailure(::report)
                            page = Page.Recycle
                        }
                    },
                    onUnlock = { askPin("feeling-unlock", pinUnlockFeeling) },
                    onBiometric = {
                        tryBiometric(
                            "feeling-unlock",
                            onUnavailable = { askPin("feeling-unlock", pinUnlockFeeling) },
                        )
                    },
                    onOpenPlace = { place -> page = Page.LocationDetail(place) },
                    tagNames = tagNames,
                    recycleActions = {
                        scope.launch {
                            runCatching { repository.restore(current.id) }
                                .onSuccess {
                                    snackbar.showSnackbar(recycleRestored)
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
                    onUnlock = { askPin("feeling-unlock", pinUnlockFeeling) },
                    onBiometric = {
                        tryBiometric(
                            "feeling-unlock",
                            onUnavailable = { askPin("feeling-unlock", pinUnlockFeeling) },
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
                        "feeling-unlock" -> check(security.unlockFeelingPin(pin)) { errFeelingPin }
                        "feeling-export", "feeling-import" -> check(security.unlockFeelingPin(pin)) { errFeelingPin }
                        "feeling-disable" -> { check(security.unlockFeelingPin(pin)) { errFeelingPin }; repository.exclusive { security.disableFeeling() } }
                        "feeling-bio" -> {
                            check(security.unlockFeelingPin(pin)) { errFeelingPin }
                            tryBiometric(
                                "feeling-enable",
                                onSuccess = { scope.launch { snackbar.showSnackbar(bioFeelingEnabled) } },
                                onUnavailable = {
                                    scope.launch {
                                        snackbar.showSnackbar(bioFeelingFailed)
                                    }
                                },
                            )
                        }
                        "app-bio" -> {
                            check(security.verifyAppLock(pin)) { errAppPin }
                            tryBiometric("app-enable",
                                onSuccess = { scope.launch { snackbar.showSnackbar(bioAppEnabled) } })
                        }
                        "feeling-change-old" -> { check(security.unlockFeelingPin(pin)) { errFeelingPin }; askPin("feeling-change-new", pinSetFeelingNew) }
                        "feeling-change-new" -> security.resetFeelingPin(pin)
                        "app-enable" -> { security.setAppLock(pin); refreshLockState() }
                        "app-disable" -> { check(security.verifyAppLock(pin)) { errAppPin }; security.disableAppLock(); refreshLockState() }
                        "app-change-old" -> { check(security.verifyAppLock(pin)) { errAppPin }; askPin("app-change-new", pinSetAppNew) }
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
        BackupPasswordDialog(if (action == "export") backupSetPassword else backupInputPassword,
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
            title = { Text(stringResource(R.string.import_preview_title)) },
            text = { Text(buildString {
                append(
                    stringResource(
                        R.string.import_preview_main,
                        preview.entries, preview.attachments, preview.duplicates, preview.expired,
                    )
                )
                append(" ")
                append(stringResource(R.string.import_preview_size, preview.attachmentBytes / (1024 * 1024)))
                if (preview.feelingMode == "ON" && feelingMode == "OFF") {
                    append(" ")
                    append(stringResource(R.string.import_preview_plaintext_warning))
                }
            }) },
            confirmButton = { TextButton(onClick = {
                backupPreview = null
                if (importUri != null) pendingImportPreview = preview
            }) { Text(if (preview.feelingMode == "ON" && feelingMode == "OFF") stringResource(R.string.import_plain_confirm) else stringResource(R.string.action_import)) } },
            dismissButton = { TextButton(onClick = { backupPreview = null; backupPassword = "" }) { Text(stringResource(R.string.action_cancel)) } })
    }
    if (!locked) confirmDelete?.let { id ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.delete_forever_title)) },
            text = { Text(stringResource(R.string.delete_forever_message)) },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                scope.launch { runCatching { repository.deletePermanently(id) }.onFailure(::report) }
            }) { Text(stringResource(R.string.action_delete_now)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    if (!locked && confirmDisableFeeling) AlertDialog(
        onDismissRequest = { confirmDisableFeeling = false },
        title = { Text(stringResource(R.string.disable_feeling_title)) },
        text = { Text(stringResource(R.string.disable_feeling_message)) },
        confirmButton = { TextButton(onClick = {
            confirmDisableFeeling = false
            askPin("feeling-disable", pinVerifyFeeling6)
        }) { Text(stringResource(R.string.disable_feeling_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmDisableFeeling = false }) { Text(stringResource(R.string.action_cancel)) } },
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
                            if (!unlockApp(pin)) lockError = errPinLocked
                        } catch (error: Exception) {
                            lockError = error.message ?: errUnlockFailed
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
        Text(stringResource(R.string.app_name), color = Green, fontSize = 30.sp, fontWeight = FontWeight.Bold)
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
        ) { Text(if (unlocking) stringResource(R.string.state_verifying) else stringResource(R.string.action_unlock)) }
        if (biometricAvailable) TextButton(onClick = onBiometric) { Text(stringResource(R.string.bio_use_biometric)) }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit) {
    // 仅允许 ASCII 数字：与 PinPolicy 的 [0-9]{6} 对齐，避免全角数字通过输入却过不了校验
    OutlinedTextField(value = value, onValueChange = { input ->
        val filtered = input.filter { it in '0'..'9' }.take(6)
        if (filtered != value) onChange(filtered)
    },
        label = { Text(stringResource(R.string.pin_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
            if (setup) { Spacer(Modifier.height(12.dp)); PinField(confirm) { confirm = it }; Text(stringResource(R.string.pin_confirm_label)) }
        } },
        confirmButton = { TextButton(onClick = { onConfirm(pin) }, enabled = pin.length == 6 && (!setup || pin == confirm)) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), containerColor = Color.White)
}

@Composable
private fun SettingsPage(
    feelingMode: String, feelingBio: Boolean, appLock: Boolean, appBio: Boolean,
    onRecycle: () -> Unit, onTags: () -> Unit, onFeeling: () -> Unit, onFeelingBio: () -> Unit, onFeelingPinChange: () -> Unit,
    onAppLock: () -> Unit, onAppBio: () -> Unit, onAppPinChange: () -> Unit,
    onLockNow: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit,
    onPrivacyFlagsChanged: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenUserAgreement: () -> Unit = {},
    onRevokeConsent: () -> Unit = {},
) {
    val context = LocalContext.current
    var secureWindow by remember {
        mutableStateOf(com.beyondguo.imprints.privacy.PrivacyPreferences.isEnabled(context))
    }
    var confirmRevoke by remember { mutableStateOf(false) }
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
        item { SettingsSectionLabel(stringResource(R.string.section_privacy_security)) }
        item {
            SettingsGroup {
                // 立即锁定（对齐 Penly 安全组首位）：仅在应用锁已开启时出现，
                // 否则锁上后没有口令可解，会把自己关在外面
                if (appLock) {
                    SettingsRow(
                        title = stringResource(R.string.settings_lock_now),
                        subtitle = stringResource(R.string.settings_lock_now_sub),
                        onClick = onLockNow,
                    )
                    FineDivider()
                }
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_feeling_encrypt),
                    subtitle = if (feelingMode == "ON") stringResource(R.string.settings_feeling_on_sub)
                    else stringResource(R.string.settings_feeling_off_sub),
                    checked = feelingMode == "ON",
                    onCheckedChange = { onFeeling() },
                )
                if (feelingMode == "ON") {
                    FineDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_change_feeling_pin),
                        subtitle = stringResource(R.string.settings_pin_digits),
                        onClick = onFeelingPinChange,
                        indent = true,
                    )
                    FineDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_feeling_biometric),
                        subtitle = if (feelingBio) stringResource(R.string.settings_feeling_bio_on_sub) else stringResource(R.string.settings_feeling_bio_off_sub),
                        checked = feelingBio,
                        onCheckedChange = { onFeelingBio() },
                        indent = true,
                    )
                }
                FineDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_app_lock),
                    subtitle = if (appLock) stringResource(R.string.settings_app_lock_on_sub)
                    else stringResource(R.string.settings_app_lock_off_sub),
                    checked = appLock,
                    onCheckedChange = { onAppLock() },
                )
                if (appLock) {
                    FineDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_change_app_pin),
                        subtitle = stringResource(R.string.settings_pin_digits),
                        onClick = onAppPinChange,
                        indent = true,
                    )
                    FineDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_app_biometric),
                        subtitle = if (appBio) stringResource(R.string.settings_app_bio_on_sub) else stringResource(R.string.settings_app_bio_off_sub),
                        checked = appBio,
                        onCheckedChange = { onAppBio() },
                        indent = true,
                    )
                }
                FineDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_secure_window),
                    subtitle = stringResource(R.string.settings_secure_window_sub),
                    checked = secureWindow,
                    onCheckedChange = { value ->
                        secureWindow = value
                        com.beyondguo.imprints.privacy.PrivacyPreferences.setEnabled(context, value)
                        onPrivacyFlagsChanged()
                    },
                )
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.section_data)) }
        item {
            SettingsGroup {
                SettingsRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_sub), onClick = onExport)
                FineDivider()
                SettingsRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_sub), onClick = onImport)
                FineDivider()
                SettingsRow(stringResource(R.string.settings_tags), stringResource(R.string.settings_tags_sub), onClick = onTags)
                FineDivider()
                SettingsRow(stringResource(R.string.settings_recycle), stringResource(R.string.settings_recycle_sub), onClick = onRecycle)
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.section_about)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_version),
                    trailing = { Text(stringResource(R.string.settings_version_value, versionName), color = Quiet, fontSize = 13.sp) },
                )
                FineDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_privacy_policy),
                    subtitle = stringResource(R.string.settings_privacy_policy_sub),
                    onClick = onOpenPrivacyPolicy,
                )
                FineDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_user_agreement),
                    subtitle = stringResource(R.string.settings_user_agreement_sub),
                    onClick = onOpenUserAgreement,
                )
                FineDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_revoke_consent),
                    subtitle = stringResource(R.string.settings_revoke_consent_sub),
                    danger = true,
                    onClick = { confirmRevoke = true },
                )
            }
        }

        item {
            Text(
                stringResource(R.string.settings_motto),
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 20.dp),
                color = Green,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(R.string.settings_revoke_title), color = Ink) },
            text = {
                Text(
                    stringResource(R.string.settings_revoke_message),
                    color = Ink, fontSize = 14.sp, lineHeight = 21.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRevoke = false; onRevokeConsent() }) {
                    Text(stringResource(R.string.settings_revoke_confirm), color = Danger, fontSize = 15.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) {
                    Text(stringResource(R.string.action_cancel), color = Quiet, fontSize = 15.sp)
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            containerColor = Color.White,
        )
    }
}

@Composable
private fun BackupPasswordDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember(title) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.backup_password_hint)) },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green)) },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.length >= 8) { Text(stringResource(R.string.action_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), containerColor = Color.White)
}

@Composable
private fun RecyclePage(entries: List<EntryEntity>, onOpen: (String) -> Unit,
    onRestore: (String) -> Unit, onDelete: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { OutlinePanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recycle_intro), Modifier.padding(18.dp),
                color = Quiet, fontSize = 14.sp, lineHeight = 22.sp)
        } }
        item { Text(stringResource(R.string.recycle_count, entries.size), Modifier.padding(top = 10.dp), color = Quiet, fontSize = 14.sp) }
        if (entries.isEmpty()) {
            item { Text(stringResource(R.string.recycle_empty), Modifier.fillMaxWidth().padding(top = 48.dp),
                color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            item { Text(stringResource(R.string.recycle_entry_sub), Modifier.fillMaxWidth(),
                color = Quiet, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        items(entries, key = { it.id }) { entry ->
            OutlinePanel(Modifier.fillMaxWidth().clickable { onOpen(entry.id) }) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(entry.eventLocalMinute.replace('T', ' '), color = Quiet, fontSize = 12.sp)
                Text(entry.body.ifBlank { stringResource(R.string.entry_media_only) }, color = Ink, fontSize = 16.sp, maxLines = 3)
                Text(entry.deletedAtMs?.let(::RecycleCountdown) ?: "", color = Quiet, fontSize = 12.sp)
                Text(stringResource(R.string.recycle_expiry), color = Quiet, fontSize = 11.sp)
                FineDivider()
                Row { TextButton(onClick = { onRestore(entry.id) }) { Text(stringResource(R.string.action_restore), color = Green) }
                    TextButton(onClick = { onDelete(entry.id) }) { Text(stringResource(R.string.action_delete_forever), color = Quiet) } }
            } }
        }
        if (entries.isNotEmpty()) item {
            Text(stringResource(R.string.recycle_hint),
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
    // 仅 debug：支持用 adb 广播预填搜索词（中文无法通过 input text 注入），供截图与手工验收
    LaunchedEffect(Unit) {
        DebugSeedBridge.prefillSearch()?.let { query = it }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) results = emptyList()
        else runCatching { repository.searchBody(query) }.onSuccess { results = it }.onFailure(report)
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search_hint)) },
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
            Text(stringResource(R.string.search_title), color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.search_sub), color = Quiet, fontSize = 13.sp)
        }
        if (query.isNotBlank() && results.isEmpty()) Column(
            Modifier.fillMaxWidth().padding(top = 44.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.search_empty), color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.search_empty_sub), color = Quiet, fontSize = 13.sp)
        }
        if (query.isNotBlank() && results.isNotEmpty()) {
            Text(stringResource(R.string.search_result_count, results.size), color = Quiet, fontSize = 13.sp)
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

/** 搜索结果里的日期标签。@Composable 以便读取本地化格式。 */
@Composable
private fun formatSearchDate(minute: String): String {
    val weekdayNames = weekdayNames()
    // 只包住解析：stringResource 在 runCatching 外，避免资源读取异常被 getOrElse
    // 吞掉后伪装成"格式化成功"（那会让缺资源表现为一条裸日期，很难排查）
    val date = runCatching { java.time.LocalDate.parse(minute.take(10)) }.getOrNull()
        ?: return minute.replace('T', ' ')
    return stringResource(
        R.string.format_date_md_weekday,
        date.monthValue, date.dayOfMonth, weekdayNames[date.dayOfWeek.value - 1],
    )
}
