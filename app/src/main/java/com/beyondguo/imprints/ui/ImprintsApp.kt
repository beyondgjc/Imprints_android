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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Star
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
import com.beyondguo.imprints.data.PeriodKind
import com.beyondguo.imprints.data.ReviewDraft
import com.beyondguo.imprints.review.ReviewPeriods
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.net.Uri
import com.beyondguo.imprints.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

private sealed interface Page {
    data object Timeline : Page
    /** 回顾一级页 */
    data object Review : Page
    /** 时光邮箱一级页 */
    data object Mail : Page
    data object Settings : Page
    data object Recycle : Page
    data object Tags : Page
    /** 设置 →「管理封存信件」：只有日期的低频维护入口。 */
    data object SealedLetters : Page
    data object Search : Page
    data object PrivacyPolicy : Page
    data object UserAgreement : Page
    data class Detail(val id: String) : Page
    data class Editor(val id: String?) : Page
    data class LocationDetail(val place: com.beyondguo.imprints.data.PlaceEntity) : Page
    data class RecycleDetail(val id: String) : Page
    data class LetterEnvelope(val id: String) : Page
    data class LetterBody(val id: String) : Page
    data class LetterEditor(val draftId: String?, val letterId: String?, val prefillBody: String = "") : Page
    data class ReviewWizard(val draftId: String) : Page
    data class ReviewDetail(val id: String) : Page
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImprintsApp(
    repository: ImprintRepository,
    letterRepository: com.beyondguo.imprints.data.LetterRepository,
    reviewRepository: com.beyondguo.imprints.data.ReviewRepository,
    initialized: Boolean,
    locked: Boolean,
    revealEpoch: Int,
    pendingLetterTarget: String? = null,
    onLetterTargetConsumed: () -> Unit = {},
    /** 聚合通知点击：没有具体信件，直接进时光邮箱列表 */
    pendingOpenMail: Boolean = false,
    onOpenMailConsumed: () -> Unit = {},
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
    var confirmDelete by remember { mutableStateOf<RecycleItem?>(null) }
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
    val recycleLetterHint = stringResource(R.string.recycle_letter_recover_first)
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
    val letterTargetMissing = stringResource(R.string.letter_target_missing)
    val letterSealed = stringResource(R.string.letter_sealed)

    val pinVerifyFeeling6 = stringResource(R.string.pin_verify_feeling_6)
    val errPinLocked = stringResource(R.string.err_pin_locked)
    val errUnlockFailed = stringResource(R.string.err_unlock_failed)
    fun report(error: Throwable) { scope.launch { snackbar.showSnackbar(error.message ?: errGeneric) } }
    fun askPin(action: String, title: String) { pinAction = action; pinTitle = title }
    // 回顾向导：进入前确定草稿身份（同一个草稿多次提交更新同一个总结 ID）
    var wizardDraft by remember { mutableStateOf<com.beyondguo.imprints.data.ReviewDraft?>(null) }
    var sameScopeExistingId by remember { mutableStateOf<String?>(null) }
    var addLetterToReviewFor by remember { mutableStateOf<String?>(null) }
    val reviewSaved = stringResource(R.string.review_saved)
    fun startWizard(kind: String, start: String, end: String, tagIds: List<String>) {
        val range = runCatching {
            com.beyondguo.imprints.review.DateRange(java.time.LocalDate.parse(start), java.time.LocalDate.parse(end))
        }.getOrNull() ?: return
        scope.launch {
            val existing = runCatching { reviewRepository.findSameScope(kind, range, tagIds.toSet()) }.getOrNull()
            val draftId = java.util.UUID.randomUUID().toString()
            wizardDraft = com.beyondguo.imprints.data.ReviewDraft(
                draftId = draftId,
                periodKind = kind,
                startLocalDate = start,
                endLocalDateInclusive = end,
                selectedTagIds = tagIds,
            )
            if (existing != null) sameScopeExistingId = existing.id else page = Page.ReviewWizard(draftId)
        }
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) pendingExportUri = uri else backupPassword = ""
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; backupAction = "import" }
    }
    val active by repository.active.collectAsState(initial = emptyList())
    val recycled by repository.recycled.collectAsState(initial = emptyList())
    // 时光邮箱 / 回顾：首页提醒区、底部徽标与回收站的三类对象都从这里取
    val sealedLetters by letterRepository.sealedLetters.collectAsState(initial = emptyList())
    val recycledLetters by letterRepository.recycledLetters.collectAsState(initial = emptyList())
    val recycledReviews by reviewRepository.recycledReviews.collectAsState(initial = emptyList())
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var reviewInvitation by remember { mutableStateOf<Pair<String, com.beyondguo.imprints.review.DateRange>?>(null) }
    val today = java.time.LocalDate.now()
    // 前台到点也要更新状态：睡到下一封送达瞬间，最迟每 30 秒醒一次刷新相对文案
    LaunchedEffect(sealedLetters) {
        while (true) {
            nowMs = System.currentTimeMillis()
            val next = letterRepository.nextDeliveryAtMs(nowMs)
            val sleepMs = next?.let { (it - nowMs).coerceAtLeast(1_000L) } ?: 30_000L
            delay(sleepMs.coerceAtMost(30_000L))
        }
    }
    // 到点对账 + 通用提醒（通知能力不可用也不影响到期置顶）
    LaunchedEffect(nowMs) {
        val newlyDue = runCatching { letterRepository.refreshDeliveryState(nowMs) }.getOrDefault(emptyList())
        if (newlyDue.isNotEmpty()) runCatching { com.beyondguo.imprints.mail.LetterNotifier.notifyDue(context) }
    }
    // 回顾邀请：最近一个已结束且有印迹的周期（优先年、月、周），已保存/已关闭则不出现
    LaunchedEffect(today, active.size, sealedLetters.size) {
        reviewInvitation = runCatching {
            reviewRepository.invitation(today)?.takeIf { (kind, range) ->
                !reviewRepository.hasFullScopeReview(kind, range)
            }
        }.getOrNull()
    }
    // 通知点击进入：通过隐私同意与应用锁后才消费目标，且只带 ID 不带内容
    LaunchedEffect(pendingOpenMail, locked, initialized) {
        if (!pendingOpenMail) return@LaunchedEffect
        if (!initialized || locked) return@LaunchedEffect
        onOpenMailConsumed()
        page = Page.Mail
    }
    LaunchedEffect(pendingLetterTarget, locked, initialized) {
        val target = pendingLetterTarget ?: return@LaunchedEffect
        if (!initialized || locked) return@LaunchedEffect
        // 先把所有 suspend 查询做完，最后才消费目标。
        // 原因：消费会改掉本 effect 的 key，协程随即重启/取消，
        // 任何在其后的挂起点都回不来，page 就设不上（「点通知只进首页」的根因）。
        val kind = repository.contentKind(target)
        val read = if (kind == com.beyondguo.imprints.data.ContentKind.LETTER) {
            letterRepository.letter(target)?.deliveryReadAtMs != null
        } else false
        onLetterTargetConsumed()
        when (kind) {
            com.beyondguo.imprints.data.ContentKind.LETTER -> {
                // V02-07：统一按 deliveryReadAtMs 分流，已读不再经过信封页
                page = if (read) Page.LetterBody(target) else Page.LetterEnvelope(target)
            }
            com.beyondguo.imprints.data.ContentKind.ENTRY -> page = Page.Detail(target)
            com.beyondguo.imprints.data.ContentKind.REVIEW -> page = Page.ReviewDetail(target)
            null -> {
                // 目标已不存在：落到时光邮箱，而不是停在首页什么也不说
                scope.launch { snackbar.showSnackbar(letterTargetMissing) }
                page = Page.Mail
            }
        }
    }
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
                    .onSuccess { count ->
                        // 导入的历史到期信只在应用内送达，不批量补发系统通知（ML-08 / SYS-02）
                        runCatching { com.beyondguo.imprints.mail.LetterScheduler.reconcile(context, notify = false) }
                        scope.launch { snackbar.showSnackbar(importDone(count)) }
                    }.onFailure(::report)
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
    // 返回层级：二级页 → 所属一级页；回顾/邮箱 → 印迹；印迹首页 → 系统默认退出
    BackHandler(page != Page.Timeline && !locked) {
        page = when (page) {
            is Page.RecycleDetail -> Page.Recycle
            Page.Tags -> Page.Settings
            Page.SealedLetters -> Page.Settings
            is Page.PrivacyPolicy, is Page.UserAgreement -> Page.Settings
            is Page.ReviewDetail, is Page.ReviewWizard -> Page.Review
            is Page.LetterEnvelope, is Page.LetterBody, is Page.LetterEditor -> Page.Mail
            else -> Page.Timeline
        }
    }
    val topLevelPage = page == Page.Timeline || page == Page.Review || page == Page.Mail
    val ownHeader = topLevelPage || page is Page.Editor || page is Page.LetterEditor || page is Page.ReviewWizard
    var showFabMenu by remember { mutableStateOf(false) }
    val unreadLetters = sealedLetters.count {
        it.deliveryReadAtMs == null && com.beyondguo.imprints.mail.LetterRules.isDue(it.toEntity(), nowMs)
    }
    CompositionLocalProvider(LocalImprintsLocked provides locked) {
    Scaffold(
        modifier = if (locked) Modifier.clearAndSetSemantics { } else Modifier,
        topBar = {
            if (!ownHeader && !locked) CenterAlignedTopAppBar(
                title = { Text(when (page) {
                    Page.Timeline -> stringResource(R.string.timeline_title); Page.Settings -> "设置"; Page.Recycle -> stringResource(R.string.settings_recycle)
                    Page.Tags -> stringResource(R.string.settings_tags)
                    Page.SealedLetters -> stringResource(R.string.mail_manage_sealed)
                    Page.Search -> pageSearch; is Page.Detail -> pageDetail
                    is Page.RecycleDetail -> pageRecycleDetail
                    is Page.Editor -> if ((page as Page.Editor).id == null) editorNewTitle else editorEditTitle
                    is Page.LocationDetail -> pageLocationDetail
                    Page.PrivacyPolicy -> stringResource(R.string.settings_privacy_policy)
                    Page.UserAgreement -> stringResource(R.string.settings_user_agreement)
                    Page.Review -> stringResource(R.string.review_title)
                    Page.Mail -> stringResource(R.string.mail_title)
                    is Page.LetterEnvelope -> stringResource(R.string.envelope_title)
                    is Page.LetterBody -> stringResource(R.string.letter_body_title)
                    is Page.LetterEditor -> stringResource(R.string.letter_new_title)
                    is Page.ReviewWizard -> stringResource(R.string.review_create)
                    is Page.ReviewDetail -> stringResource(R.string.review_detail_title)
                }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = {
                    page = when (page) {
                        is Page.RecycleDetail -> Page.Recycle
                        Page.Tags -> Page.Settings
                        Page.SealedLetters -> Page.Settings
                        is Page.PrivacyPolicy, is Page.UserAgreement -> Page.Settings
                        is Page.ReviewDetail, is Page.ReviewWizard -> Page.Review
                        is Page.LetterEnvelope, is Page.LetterBody, is Page.LetterEditor -> Page.Mail
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
        bottomBar = {
            // 底部顺序固定「印迹 · 回顾 · 时光邮箱」；二级页隐藏底部导航
            if (topLevelPage && !locked) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                // 选中态必须显式给色：默认走 Material3 的 onSecondaryContainer（深灰），
                // 看起来就是「填充变黑」。这里统一用应用内的 Green。
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Green,
                    selectedTextColor = Green,
                    unselectedIconColor = Quiet,
                    unselectedTextColor = Quiet,
                    // 微信 / QQ 的选中态只有「图标和文字变绿」，没有胶囊底色
                    indicatorColor = Color.Transparent,
                )
                NavigationBarItem(
                    selected = page == Page.Timeline,
                    onClick = { page = Page.Timeline },
                    colors = navColors,
                    icon = {
                        // 未选中线框中空、选中填充（对齐微信 / QQ 底栏的做法）
                        Icon(
                            if (page == Page.Timeline) Icons.Default.Home else Icons.Outlined.Home,
                            stringResource(R.string.nav_imprints),
                        )
                    },
                    label = { Text(stringResource(R.string.nav_imprints)) },
                )
                NavigationBarItem(
                    selected = page == Page.Review,
                    onClick = { page = Page.Review },
                    colors = navColors,
                    icon = {
                        // Icons.Outlined.Star 轮廓太粗、几乎看不出中空，改用自绘的空心星
                        if (page == Page.Review) {
                            Icon(Icons.Default.Star, stringResource(R.string.nav_review))
                        } else {
                            Icon(painterResource(R.drawable.ic_nav_star_border), stringResource(R.string.nav_review))
                        }
                    },
                    label = { Text(stringResource(R.string.nav_review)) },
                )
                NavigationBarItem(
                    selected = page == Page.Mail,
                    onClick = { page = Page.Mail },
                    colors = navColors,
                    icon = {
                        // 到期未读徽标：应用锁遮挡期间不显示内容和数量（locked 时整条底栏不渲染）。
                        // 用 BadgedBox 让徽标悬浮在图标右上角（对齐微信/QQ），不再压在图标上。
                        BadgedBox(
                            badge = {
                                if (unreadLetters > 0) {
                                    Badge(containerColor = Danger, contentColor = Color.White) {
                                        Text(
                                            if (unreadLetters > 9) "9+" else unreadLetters.toString(),
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(
                                if (page == Page.Mail) Icons.Default.Email else Icons.Outlined.Email,
                                stringResource(R.string.nav_mail),
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.nav_mail)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { when (page) {
            Page.Timeline -> FloatingActionButton(
                onClick = { showFabMenu = true }, shape = CircleShape, containerColor = Green,
                contentColor = Color.White) { Icon(Icons.Default.Add, editorNewTitle) }
            Page.Mail -> FloatingActionButton(
                onClick = { page = Page.LetterEditor(null, null) }, shape = CircleShape, containerColor = Green,
                contentColor = Color.White) { Icon(Icons.Default.Add, stringResource(R.string.mail_write)) }
            Page.Review -> FloatingActionButton(
                onClick = {
                    val month = ReviewPeriods.lastCompletedMonth(today)
                    startWizard(PeriodKind.MONTH.name, month.start.toString(), month.endInclusive.toString(), emptyList())
                }, shape = CircleShape, containerColor = Green,
                contentColor = Color.White) { Icon(Icons.Default.Add, stringResource(R.string.review_create)) }
            else -> {}
        } },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            // 各一级页切换时保留列表位置/筛选/周期选择
            val saveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            when (val current = page) {
                Page.Timeline -> saveableStateHolder.SaveableStateProvider("tab_imprints") {
                TimelinePage(active, repository, revealEpoch + securityEpoch,
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
                    onClearTags = { selectedTagIds = emptySet() },
                    onCreateTag = { name ->
                        scope.launch {
                            // 标签表已由 tagList 订阅，这里只需把新标签选中
                            runCatching { repository.createTag(name) }
                                .onSuccess { newId -> selectedTagIds = selectedTagIds + newId }
                                .onFailure(::report)
                        }
                    },
                    onRenameTag = { id, name ->
                        scope.launch { runCatching { repository.renameTag(id, name) }.onFailure(::report) }
                    },
                    onDeleteTag = { id ->
                        scope.launch {
                            runCatching { repository.deleteTag(id) }
                                .onSuccess { selectedTagIds = selectedTagIds - id }
                                .onFailure(::report)
                        }
                    },
                    onOpenEnvelope = { page = Page.LetterEnvelope(it) },
                    onOpenMail = { page = Page.Mail },
                    reminderLetters = sealedLetters.filter {
                        it.deliveryReadAtMs == null && com.beyondguo.imprints.mail.LetterRules.isDue(it.toEntity(), nowMs)
                    },
                    reviewInvitation = reviewInvitation,
                    onOpenReviewInvitation = { kind, range ->
                        startWizard(kind, range.start.toString(), range.endInclusive.toString(), selectedTagIds.toList())
                    },
                    onDismissReviewInvitation = { kind, range ->
                        scope.launch { runCatching { reviewRepository.closeInvitation(kind, range) } }
                    },
                    onReviewMonth = { month ->
                        val range = com.beyondguo.imprints.review.ReviewPeriods.monthOf(
                            java.time.LocalDate.parse(month + "-01"),
                        )
                        startWizard(PeriodKind.MONTH.name, range.start.toString(), range.endInclusive.toString(), selectedTagIds.toList())
                    },
                    onError = ::report)
                }
                Page.Review -> saveableStateHolder.SaveableStateProvider("tab_review") {
                    ReviewHomePage(
                        reviewRepository = reviewRepository,
                        coreRepository = repository,
                        nowDate = today,
                        onStartWizard = ::startWizard,
                        onOpenReview = { page = Page.ReviewDetail(it) },
                        onOpenDraft = { page = Page.ReviewWizard(it) },
                        onDeleteDraft = { id -> scope.launch { runCatching { reviewRepository.deleteDraft(id) }.onFailure(::report) } },
                        report = ::report,
                    )
                }
                Page.Mail -> saveableStateHolder.SaveableStateProvider("tab_mail") {
                    MailHomePage(
                        repository = letterRepository,
                        nowMs = nowMs,
                        onOpenLetter = { id, read ->
                            // 统一按 deliveryReadAtMs 分流：已读不再经过信封页与拆信动作
                            page = if (read) Page.LetterBody(id) else Page.LetterEnvelope(id)
                        },
                        onWrite = { page = Page.LetterEditor(null, null) },
                        onOpenDraft = { id -> page = Page.LetterEditor(id, null) },
                        onDeleteDraft = { id -> scope.launch { runCatching { letterRepository.deleteDraft(id) }.onFailure(::report) } },
                    )
                }
                Page.SealedLetters -> SealedLettersPage(
                    repository = letterRepository,
                    nowMs = nowMs,
                    onError = ::report,
                )
                Page.Settings -> SettingsPage(feelingMode, feelingBioEnabled, appLockEnabled, appBioEnabled,
                    onRecycle = { page = Page.Recycle },
                    onTags = { page = Page.Tags },
                    onManageSealed = { page = Page.SealedLetters },
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
                Page.Recycle -> {
                    // 回收站三类对象统一体现（ML-08 / RV-07）：信件与总结封面不露正文
                    val recycleItems = remember(recycled, recycledLetters, recycledReviews) {
                        recycled.map {
                            RecycleItem("ENTRY", it.id, it.body, it.deletedAtMs ?: 0L)
                        } + recycledLetters.map {
                            // V0.2：未到期的封存信在回收站也不暴露标题（规格 ML-08）
                            val sealedNotDue = it.deliveredAtMs == null && it.deliverAtMs > nowMs
                            RecycleItem("LETTER", it.entryId, if (sealedNotDue) "" else it.title, it.deletedAtMs ?: 0L)
                        } + recycledReviews.map {
                            RecycleItem("REVIEW", it.id, it.title, it.deletedAtMs ?: 0L)
                        }
                    }
                    RecyclePage(recycleItems,
                        onOpen = { item ->
                            when (item.kind) {
                                // V0.2：回收站里的信件不提供正文预览，须先恢复再走正常拆信流程
                                "LETTER" -> scope.launch { snackbar.showSnackbar(recycleLetterHint) }
                                "REVIEW" -> page = Page.ReviewDetail(item.id)
                                else -> page = Page.RecycleDetail(item.id)
                            }
                        },
                        onRestore = { item ->
                            scope.launch {
                                runCatching {
                                    when (item.kind) {
                                        "LETTER" -> letterRepository.restore(item.id)
                                        "REVIEW" -> reviewRepository.restore(item.id)
                                        else -> repository.restore(item.id)
                                    }
                                }
                                    .onSuccess {
                                        // 恢复未到期信要重新调度；本次恢复不补发系统通知
                                        runCatching { com.beyondguo.imprints.mail.LetterScheduler.reconcile(context) }
                                        snackbar.showSnackbar(recycleRestored)
                                        page = Page.Timeline
                                    }
                                    .onFailure(::report)
                            }
                        },
                        onDelete = { item -> confirmDelete = item })
                }
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
                is Page.LetterEnvelope -> LetterEnvelopePage(
                    repository = letterRepository,
                    letterId = current.id,
                    nowMs = nowMs,
                    onOpenBody = { page = Page.LetterBody(current.id) },
                )
                is Page.LetterBody -> LetterBodyPage(
                    repository = letterRepository,
                    letterId = current.id,
                    nowMs = nowMs,
                    // V0.2：拆开后的返回栈不保留可回退的信封页，返回直接回邮箱
                    onBack = { page = Page.Mail },
                    // 邮件只提供日期入口：预填「发出日 ~ 实际接收日」，不读正文、不建立关联
                    onReviewThisTime = { start, end ->
                        startWizard("CUSTOM", start, end, emptyList())
                    },
                    report = ::report,
                )
                is Page.LetterEditor -> LetterEditorPage(
                    repository = letterRepository,
                    draftId = current.draftId,
                    prefillBody = current.prefillBody,
                    onSealed = {
                        scope.launch { snackbar.showSnackbar(letterSealed) }
                        page = Page.Mail
                    },
                    onCancel = { page = Page.Mail },
                    report = ::report,
                )
                is Page.ReviewWizard -> ReviewEditorPage(
                    reviewRepository = reviewRepository,
                    initialDraft = wizardDraft,
                    draftId = current.draftId,
                    onSaved = { id ->
                        scope.launch { snackbar.showSnackbar(reviewSaved) }
                        page = Page.ReviewDetail(id)
                    },
                    onCancel = { page = Page.Review },
                    report = ::report,
                )
                is Page.ReviewDetail -> ReviewDetailPage(
                    reviewRepository = reviewRepository,
                    coreRepository = repository,
                    letterRepository = letterRepository,
                    reviewId = current.id,
                    onOpenEntry = { id ->
                        scope.launch {
                            page = if (repository.contentKind(id) == com.beyondguo.imprints.data.ContentKind.LETTER) {
                                Page.LetterBody(id)
                            } else {
                                Page.Detail(id)
                            }
                        }
                    },
                    onWriteLetter = { closing ->
                        page = Page.LetterEditor(null, null, prefillBody = closing)
                    },
                    onEdit = {
                        scope.launch {
                            val detail = reviewRepository.detail(current.id) ?: return@launch
                            val draftId = java.util.UUID.randomUUID().toString()
                            val sources = detail.sources
                            wizardDraft = ReviewDraft(
                                draftId = draftId,
                                reviewId = current.id,
                                periodKind = detail.review.periodKind,
                                startLocalDate = detail.review.startLocalDate,
                                endLocalDateInclusive = detail.review.endLocalDateInclusive,
                                selectedTagIds = detail.review.selectedTagIds.split(',').filter(String::isNotBlank),
                                title = detail.review.title,
                                userBody = detail.review.userBody,
                                closingText = detail.review.closingText,
                                coverSourceKey = detail.review.coverSourceKey,
                                includedKeys = sources.filter { it.included }.map { "${it.sourceKind}:${it.sourceId}" },
                                featuredKeys = sources.filter { it.featured }
                                    .sortedBy { it.featuredOrder }
                                    .map { "${it.sourceKind}:${it.sourceId}" },
                            )
                            page = Page.ReviewWizard(draftId)
                        }
                    },
                    onDelete = {
                        scope.launch { snackbar.showSnackbar(deleteDone) }
                        page = Page.Review
                    },
                    report = ::report,
                )
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
                append(stringResource(R.string.import_preview_types, preview.letters, preview.reviews, preview.drafts))
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
    if (!locked) confirmDelete?.let { item ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.delete_forever_title)) },
            text = { Text(stringResource(R.string.delete_forever_message)) },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                scope.launch {
                    runCatching {
                        when (item.kind) {
                            "LETTER" -> {
                                letterRepository.deletePermanently(item.id)
                                com.beyondguo.imprints.mail.LetterScheduler.cancel(context, item.id)
                            }
                            "REVIEW" -> reviewRepository.deletePermanently(item.id)
                            else -> repository.deletePermanently(item.id)
                        }
                    }.onFailure(::report)
                }
            }) { Text(stringResource(R.string.action_delete_now)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    // 同范围已有总结：打开已有 / 另建，不直接覆盖（RV-03）
    if (!locked) sameScopeExistingId?.let { existingId ->
        AlertDialog(
            onDismissRequest = { sameScopeExistingId = null },
            title = { Text(stringResource(R.string.review_existing_title)) },
            text = { Text(stringResource(R.string.review_existing_message)) },
            confirmButton = { TextButton(onClick = {
                sameScopeExistingId = null
                page = Page.ReviewDetail(existingId)
            }) { Text(stringResource(R.string.review_open_existing)) } },
            dismissButton = { TextButton(onClick = {
                sameScopeExistingId = null
                wizardDraft?.draftId?.let { page = Page.ReviewWizard(it) }
            }) { Text(stringResource(R.string.review_create_new)) } },
        )
    }
    // FAB「＋」：记录印迹 / 给未来写信（ML-01 入口）
    if (!locked && showFabMenu) AlertDialog(
        onDismissRequest = { showFabMenu = false },
        title = { Text(stringResource(R.string.editor_new_title)) },
        text = {
            Column {
                TextButton(onClick = { showFabMenu = false; page = Page.Editor(null) }) {
                    Text(stringResource(R.string.action_record_imprint), color = Ink, fontSize = 15.sp)
                }
                TextButton(onClick = { showFabMenu = false; page = Page.LetterEditor(null, null) }) {
                    Text(stringResource(R.string.mail_write), color = Green, fontSize = 15.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { showFabMenu = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
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
    onRecycle: () -> Unit, onTags: () -> Unit, onManageSealed: () -> Unit,
    onFeeling: () -> Unit, onFeelingBio: () -> Unit, onFeelingPinChange: () -> Unit,
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

        item { SettingsSectionLabel(stringResource(R.string.section_reminder)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_notification),
                    subtitle = stringResource(R.string.settings_notification_sub),
                    onClick = { com.beyondguo.imprints.mail.LetterBackgroundPolicy.openNotificationSettings(context) },
                )
                FineDivider()
                // 进程被杀后仍能送达：需要电池优化白名单（MIUI 未豁免会丢弃精确闹钟）。
                //
                // 这一行**永远可点**，按状态分流 —— 设置里的条目必须是有效操作：
                //   未豁免 → 弹系统「允许后台运行」请求框；
                //   已豁免 → 打开系统应用信息页（能关掉、也能开 MIUI 自启动）。
                // 之所以不是 Switch：电池优化豁免只有申请 API、没有撤销 API，
                // 开关状态完全由系统决定，做成 Switch 会出现"拨过去又弹回"的假象。
                // 不用 remember：从系统页返回时需要反映最新状态。
                val exempt = com.beyondguo.imprints.mail.LetterBackgroundPolicy.isExempt(context)
                SettingsRow(
                    title = stringResource(R.string.settings_background),
                    subtitle = stringResource(
                        if (exempt) R.string.settings_background_on else R.string.settings_background_off,
                    ),
                    onClick = {
                        if (exempt) {
                            com.beyondguo.imprints.mail.LetterBackgroundPolicy.openAppDetails(context)
                        } else {
                            com.beyondguo.imprints.mail.LetterBackgroundPolicy.requestExemption(context)
                        }
                    },
                )
                FineDivider()
                // 唯一的主动管理入口：低频、不附数量（产品规格 ML-02）
                SettingsRow(
                    title = stringResource(R.string.mail_manage_sealed),
                    subtitle = stringResource(R.string.mail_manage_sealed_sub),
                    onClick = onManageSealed,
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

/** 回收站条目：印迹 / 信件 / 总结 三类对象统一展示。 */
private data class RecycleItem(val kind: String, val id: String, val title: String, val deletedAtMs: Long)

@Composable
private fun RecyclePage(items: List<RecycleItem>, onOpen: (RecycleItem) -> Unit,
    onRestore: (RecycleItem) -> Unit, onDelete: (RecycleItem) -> Unit) {
    val sorted = remember(items) { items.sortedByDescending { it.deletedAtMs } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { OutlinePanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recycle_intro), Modifier.padding(18.dp),
                color = Quiet, fontSize = 14.sp, lineHeight = 22.sp)
        } }
        item { Text(stringResource(R.string.recycle_count, sorted.size), Modifier.padding(top = 10.dp), color = Quiet, fontSize = 14.sp) }
        if (sorted.isEmpty()) {
            item { Text(stringResource(R.string.recycle_empty), Modifier.fillMaxWidth().padding(top = 48.dp),
                color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            item { Text(stringResource(R.string.recycle_entry_sub), Modifier.fillMaxWidth(),
                color = Quiet, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        items(sorted, key = { it.kind + "-" + it.id }) { item ->
            OutlinePanel(Modifier.fillMaxWidth().clickable { onOpen(item) }) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    when (item.kind) {
                        "LETTER" -> stringResource(R.string.mail_type_letter)
                        "REVIEW" -> stringResource(R.string.recycle_type_review)
                        else -> stringResource(R.string.recycle_type_entry)
                    } + " · " + item.deletedAtMs.let { RecycleCountdown(it) },
                    color = Quiet, fontSize = 12.sp,
                )
                Text(
                    when {
                        // 封存未到期的信只给类型，不给标题
                        item.kind == "LETTER" && item.title.isBlank() -> stringResource(R.string.mail_type_sealed)
                        item.title.isBlank() -> stringResource(R.string.entry_media_only)
                        else -> item.title
                    },
                    color = Ink, fontSize = 16.sp, maxLines = 3,
                )
                Text(stringResource(R.string.recycle_expiry), color = Quiet, fontSize = 11.sp)
                FineDivider()
                Row { TextButton(onClick = { onRestore(item) }) { Text(stringResource(R.string.action_restore), color = Green) }
                    TextButton(onClick = { onDelete(item) }) { Text(stringResource(R.string.action_delete_forever), color = Quiet) } }
            } }
        }
        if (sorted.isNotEmpty()) item {
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
    // 仅 debug：支持用 adb 广播预填搜索词（中文无法通过 input text 注入），供截图与手工验收。
    // 以 revision 为 key，重复进出搜索页也能重新读取。
    val debugRevision = DebugSeedBridge.revision
    LaunchedEffect(debugRevision) {
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
