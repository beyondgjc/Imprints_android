package com.beyondguo.imprints.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.imprints.R
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.TagEntity
import com.beyondguo.imprints.data.TagRules

/** 底部面板里当前打开的对话框。 */
private sealed interface TagDialog {
    data object Create : TagDialog
    data object Manage : TagDialog
    data class Rename(val tag: TagEntity) : TagDialog
    data class Delete(val tag: TagEntity) : TagDialog
}

/**
 * 编辑页的标签条目行：与「声音 / 位置 / 事件时间」同一套 [EditorMetaRow] 布局。
 * 未选时灰字提示，已选时直接列出标签名（绿字，与位置一致），点按打开「选择标签」面板。
 */
@Composable
fun EditorTagRow(
    tags: List<TagEntity>,
    selectedIds: Set<String>,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val label = selectedIds
        .mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }
        .joinToString(separator = "，")
    val hasSelection = label.isNotEmpty()
    EditorMetaRow(
        leadingContent = {
            Icon(
                Icons.Default.Add,
                null,
                tint = if (hasSelection) Color.White else Quiet,
                modifier = Modifier.size(20.dp),
            )
        },
        leadingTint = if (hasSelection) Green else null,
        title = if (hasSelection) label else stringResource(R.string.editor_tag_add_label),
        subtitle = null,
        titleColor = if (hasSelection) Green else Quiet,
        onClick = onClick,
        trailing = if (hasSelection) EditorMetaTrailing.ClearThenChevron else EditorMetaTrailing.Chevron,
        onClear = onClear,
    )
}

/**
 * 「选择标签」底部面板：顶栏「取消 / 选择标签 / 完成」+ 搜索框 + 「常用标签」/「我的标签」两组 chip。
 * 选择是即时生效的，所以「完成」只是收起面板。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(
    repository: ImprintRepository,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    onError: (Throwable) -> Unit,
) {
    val tags = repository.selectableTags.collectAsState(initial = emptyList()).value
    var query by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<TagDialog?>(null) }
    var usage by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(tags.map { it.id }) {
        usage = repository.tagUsageCounts().toMap()
    }
    val filtered = remember(tags, query) {
        val keyword = TagRules.compareKey(query)
        if (keyword.isEmpty()) tags
        else tags.filter { TagRules.compareKey(it.name).contains(keyword) }
    }
    // 常用：用过且用得最多的前几个；搜索时不再单独分组，避免同一个标签出现两次
    val frequent = remember(tags, usage, query) {
        if (query.isNotBlank()) emptyList()
        else tags.filter { (usage[it.id] ?: 0) > 0 }
            .sortedWith(compareByDescending<TagEntity> { usage[it.id] ?: 0 }.then(TagRules.TAG_ORDER))
            .take(8)
    }
    val createDisabled = TagRules.hasReachedLimit(tags.size)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Ink) }
                Text(
                    stringResource(R.string.tag_sheet_title),
                    Modifier.weight(1f),
                    color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tag_sheet_done), color = Green) }
            }
            HorizontalDivider(color = Line)
            TagSearchField(query, { query = it })
            LazyColumn(Modifier.fillMaxWidth()) {
                if (frequent.isNotEmpty()) {
                    item(key = "frequent-title") { TagGroupLabel(stringResource(R.string.tag_group_frequent)) }
                    item(key = "frequent-chips") {
                        TagChipWrap(frequent, selectedIds, onToggle)
                    }
                }
                item(key = "mine-title") {
                    TagGroupLabel(
                        stringResource(R.string.tag_group_mine),
                        action = stringResource(R.string.timeline_tag_manage) to { dialog = TagDialog.Manage },
                    )
                }
                items(filtered, key = { it.id }) { tag ->
                    TagPickRow(
                        tag = tag,
                        selected = tag.id in selectedIds,
                        onClick = { onToggle(tag.id) },
                        onManage = { dialog = TagDialog.Rename(tag) },
                    )
                }
                item(key = "create") {
                    if (createDisabled) {
                        Text(
                            stringResource(R.string.tag_limit_reached, TagRules.MAX_TAG_COUNT),
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            color = Quiet, fontSize = 13.sp,
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable { dialog = TagDialog.Create }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("＋", color = Green, fontSize = 16.sp)
                            Text(stringResource(R.string.tag_create), color = Green, fontSize = 16.sp)
                        }
                    }
                }
                item(key = "tail") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        TagDialog.Create -> TagNameDialog(
            title = stringResource(R.string.tag_create_title),
            initial = "",
            confirmLabel = stringResource(R.string.action_confirm),
            onDismiss = { dialog = null },
            onConfirm = { name ->
                runCatching { onCreate(name) }.onFailure(onError)
                query = ""
                dialog = null
            },
        )
        TagDialog.Manage -> TagManageDialog(
            tags = tags,
            usage = usage,
            onRename = { tag -> dialog = TagDialog.Rename(tag) },
            onDelete = { tag -> dialog = TagDialog.Delete(tag) },
            onCreate = { dialog = TagDialog.Create },
            createDisabled = createDisabled,
            onDismiss = { dialog = null },
        )
        is TagDialog.Rename -> TagNameDialog(
            title = stringResource(R.string.tag_rename_title),
            initial = current.tag.name,
            confirmLabel = stringResource(R.string.action_confirm),
            onDismiss = { dialog = null },
            onConfirm = { name ->
                if (name != current.tag.name) runCatching { onRename(current.tag.id, name) }.onFailure(onError)
                dialog = null
            },
            trailingAction = {
                // 预置标签不可删除
                if (!current.tag.preset) {
                    TextButton(onClick = { dialog = TagDialog.Delete(current.tag) }) {
                        Text(stringResource(R.string.action_delete), color = Danger)
                    }
                }
            },
        )
        is TagDialog.Delete -> TagDeleteDialog(
            tag = current.tag,
            entryCount = { id -> repository.entryCountForTag(id) },
            onDismiss = { dialog = null },
            onConfirm = {
                runCatching { onDelete(current.tag.id) }.onFailure(onError)
                dialog = null
            },
        )
    }
}

/** 一行式选择器：已选显示绿勾，长按直接进改名。 */
@Composable
private fun TagPickRow(
    tag: TagEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onManage: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onManage)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            tag.name,
            Modifier.weight(1f),
            color = Ink, fontSize = 16.sp,
            maxLines = 1,
        )
        if (selected) Text("✓", color = Green, fontSize = 18.sp)
    }
}

/** chip 流式布局，用于「常用标签」分组。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipWrap(tags: List<TagEntity>, selectedIds: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            TagChip(text = tag.name, selected = tag.id in selectedIds, onClick = { onToggle(tag.id) })
        }
    }
}

@Composable
private fun TagGroupLabel(title: String, action: Pair<String, () -> Unit>? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = Quiet, fontSize = 13.sp)
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick) { Text(label, color = Quiet, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun TagSearchField(value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint_short), color = Quiet) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Quiet) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.a11y_clear_search), tint = Quiet)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SoftCard,
            unfocusedContainerColor = SoftCard,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/** 「管理」弹窗：列出全部标签与使用次数，可改名、删除、新建。 */
@Composable
private fun TagManageDialog(
    tags: List<TagEntity>,
    usage: Map<String, Int>,
    onRename: (TagEntity) -> Unit,
    onDelete: (TagEntity) -> Unit,
    onCreate: () -> Unit,
    createDisabled: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tags)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(tags, key = { it.id }) { tag ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onRename(tag) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tag.name, color = Ink, fontSize = 15.sp, maxLines = 1)
                            Text(
                                stringResource(R.string.tags_entry_count, usage[tag.id] ?: 0),
                                color = Quiet, fontSize = 12.sp,
                            )
                        }
                        if (tag.preset) {
                            Text(stringResource(R.string.tags_preset_badge), color = Quiet, fontSize = 12.sp)
                        } else {
                            TextButton(onClick = { onDelete(tag) }) {
                                Text(stringResource(R.string.action_delete), color = Danger, fontSize = 13.sp)
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                }
                item {
                    TextButton(onClick = onCreate, enabled = !createDisabled) {
                        Text(stringResource(R.string.tag_create), color = Green)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) }
        },
    )
}

/** 标签名输入对话框：本地先按 [TagRules] 校验，错误直接显示在输入框下方。 */
@Composable
fun TagNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    var value by remember { mutableStateOf(initial) }
    val normalized = TagRules.normalizeName(value)
    val lengthError = when {
        normalized.isEmpty() -> null
        !TagRules.isValidName(normalized) -> stringResource(R.string.tag_name_too_long, TagRules.MAX_NAME_LENGTH)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.tag_name_hint), color = Quiet) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                Text(
                    lengthError ?: stringResource(R.string.tag_name_counter, TagRules.lengthOf(normalized), TagRules.MAX_NAME_LENGTH),
                    color = if (lengthError != null) Danger else Quiet,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalized.isNotEmpty() && lengthError == null,
                onClick = { onConfirm(normalized) },
            ) { Text(confirmLabel, fontWeight = FontWeight.Medium) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                trailingAction?.invoke()
            }
        },
    )
}

@Composable
fun TagDeleteDialog(
    tag: TagEntity,
    entryCount: suspend (String) -> Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var count by remember(tag.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(tag.id) { count = entryCount(tag.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_delete_title, tag.name)) },
        text = {
            Text(
                if (count == null) stringResource(R.string.state_loading)
                else stringResource(R.string.tag_delete_message, count ?: 0),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete), color = Danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 设置页的「标签管理」：列出全部标签、各自被多少条印迹使用，并提供改名/删除/新建。 */
@Composable
fun TagsPage(
    repository: ImprintRepository,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val tags = repository.selectableTags.collectAsState(initial = emptyList()).value
    var dialog by remember { mutableStateOf<TagDialog?>(null) }
    var usage by remember { mutableStateOf(emptyMap<String, Int>()) }
    // 使用计数随标签集合变化重算：删除/新建标签后自动刷新
    LaunchedEffect(tags.map { it.id }) {
        usage = repository.tagUsageCounts().toMap()
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SettingsSectionLabel(stringResource(R.string.settings_tags)) }
        item {
            Text(
                stringResource(R.string.tags_page_intro),
                Modifier.padding(start = 4.dp, bottom = 8.dp),
                color = Quiet, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
        item {
            SettingsGroup {
                if (tags.isEmpty()) {
                    SettingsRow(stringResource(R.string.tags_page_empty), subtitle = null)
                } else {
                    tags.forEachIndexed { index, tag ->
                        if (index > 0) FineDivider()
                        SettingsRow(
                            title = tag.name,
                            subtitle = stringResource(R.string.tags_entry_count, usage[tag.id] ?: 0),
                            onClick = { dialog = TagDialog.Rename(tag) },
                            trailing = if (tag.preset) {
                                { Text(stringResource(R.string.tags_preset_badge), color = Quiet, fontSize = 12.sp) }
                            } else null,
                        )
                    }
                }
            }
        }
        item { SettingsSectionLabel(stringResource(R.string.editor_tag_label)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.tag_create),
                    subtitle = if (TagRules.hasReachedLimit(tags.size)) {
                        stringResource(R.string.tag_limit_reached, TagRules.MAX_TAG_COUNT)
                    } else null,
                    onClick = if (TagRules.hasReachedLimit(tags.size)) null else ({ dialog = TagDialog.Create }),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    when (val current = dialog) {
        null -> Unit
        TagDialog.Create -> TagNameDialog(
            title = stringResource(R.string.tag_create_title),
            initial = "",
            confirmLabel = stringResource(R.string.action_confirm),
            onDismiss = { dialog = null },
            onConfirm = { name ->
                runCatching { onCreate(name) }.onFailure(onError)
                dialog = null
            },
        )
        TagDialog.Manage -> Unit
        is TagDialog.Rename -> TagNameDialog(
            title = stringResource(R.string.tag_rename_title),
            initial = current.tag.name,
            confirmLabel = stringResource(R.string.action_confirm),
            onDismiss = { dialog = null },
            onConfirm = { name ->
                if (name != current.tag.name) runCatching { onRename(current.tag.id, name) }.onFailure(onError)
                dialog = null
            },
            trailingAction = {
                if (!current.tag.preset) {
                    TextButton(onClick = { dialog = TagDialog.Delete(current.tag) }) {
                        Text(stringResource(R.string.action_delete), color = Danger)
                    }
                }
            },
        )
        is TagDialog.Delete -> TagDeleteDialog(
            tag = current.tag,
            entryCount = { id -> repository.entryCountForTag(id) },
            onDismiss = { dialog = null },
            onConfirm = {
                runCatching { onDelete(current.tag.id) }.onFailure(onError)
                dialog = null
            },
        )
    }
}

/** 时间线顶部的筛选 chip 行：多选叠加（OR），全部未选 = 显示全部。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFilterRow(
    tags: List<TagEntity>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onCreate: () -> Unit,
    createDisabled: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            TagChip(text = tag.name, selected = tag.id in selectedIds, onClick = { onToggle(tag.id) })
        }
        if (!createDisabled) {
            TagChip(text = stringResource(R.string.tag_create), selected = false, onClick = onCreate)
        }
    }
}
