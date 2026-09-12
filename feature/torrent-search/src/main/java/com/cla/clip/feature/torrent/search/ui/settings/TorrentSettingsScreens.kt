package com.cla.clip.feature.torrent.search.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.settings.TorrentSearchDnsMode
import com.cla.clip.feature.torrent.search.torznab.TorznabConfig
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId
import com.cla.clip.feature.torrent.search.ui.component.torrentCategoryLabel

/** TorrentSearch 设置纯渲染页面；只消费轻量状态并把写操作提升到 ViewModel。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorrentSettingsScreen(
    /** 当前不可变设置状态。 */
    state: TorrentSettingsUiState,
    /** 打开 Provider 管理页。 */
    onProviders: () -> Unit,
    /** 写入安全模式目标值。 */
    onSafeModeChange: (Boolean) -> Unit,
    /** 写入默认排序组合。 */
    onSortChange: (SortCriteria, SortOrder) -> Unit,
    /** 写入 DNS 解析模式。 */
    onDnsModeChange: (TorrentSearchDnsMode) -> Unit,
    /** 重置本机多来源披露确认。 */
    onResetDisclosure: () -> Unit,
    /** 清除固定错误提示。 */
    onClearError: () -> Unit,
    /** 返回上一页。 */
    onBack: () -> Unit,
) {
    /** showDisableConfirmation 仅控制本次 Composition 内的确认弹窗。 */
    var showDisableConfirmation by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.torrent_search_settings_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.torrent_search_action_back))
                    }
                },
            )
        },
    ) { /** contentPadding 是系统栏与顶部栏提供的安全边距。 */ contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "error") {
                SettingsErrorCard(state.error, onClearError)
            }
            item(key = "providers") {
                SettingsNavigationCard(
                    title = stringResource(R.string.torrent_search_settings_search_providers),
                    summary = stringResource(
                        R.string.torrent_search_settings_search_providers_summary_format,
                        state.enabledProviderCount,
                        state.providers.size,
                    ),
                    onClick = onProviders,
                )
            }
            item(key = "safe_mode") {
                SettingsSwitchCard(
                    title = stringResource(R.string.torrent_search_safe_mode),
                    summary = stringResource(R.string.torrent_search_settings_safe_mode_summary),
                    checked = state.safeModeEnabled,
                    enabled = state.settingsReady,
                    onCheckedChange = { /** enabled 是用户请求的安全模式目标值。 */ enabled ->
                        if (enabled) onSafeModeChange(true) else showDisableConfirmation = true
                    },
                )
            }
            item(key = "sort_criteria") {
                SettingsSectionTitle(stringResource(R.string.torrent_search_settings_section_sort_criteria))
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SortCriteria.entries, key = { /** criteria 提供稳定 code 作为 key。 */ criteria -> criteria.code }) { /** criteria 是当前候选排序标准。 */ criteria ->
                        FilterChip(
                            selected = state.sortCriteria == criteria,
                            onClick = { onSortChange(criteria, state.sortOrder) },
                            label = { Text(sortCriteriaLabel(criteria)) },
                            enabled = state.settingsReady,
                        )
                    }
                }
            }
            item(key = "sort_order") {
                SettingsSectionTitle(stringResource(R.string.torrent_search_settings_section_sort_order))
                ChoiceRow(
                    labels = SortOrder.entries.map { /** order 映射为当前语言标签。 */ order -> order to sortOrderLabel(order) },
                    selected = state.sortOrder,
                    enabled = state.settingsReady,
                    keyOf = { /** order 使用稳定持久 code 作为列表身份。 */ order -> order.code },
                    onSelected = { /** order 是用户选择的默认排序方向。 */ order -> onSortChange(state.sortCriteria, order) },
                )
            }
            item(key = "dns") {
                SettingsSectionTitle(stringResource(R.string.torrent_search_settings_dns_over_https))
                ChoiceRow(
                    labels = TorrentSearchDnsMode.entries.map { /** mode 映射为当前语言标签。 */ mode -> mode to dnsModeLabel(mode) },
                    selected = state.dnsMode,
                    enabled = state.settingsReady,
                    keyOf = { /** mode 使用稳定持久 code 作为列表身份。 */ mode -> mode.code },
                    onSelected = onDnsModeChange,
                )
                Text(
                    text = stringResource(R.string.torrent_search_settings_dns_over_https_summary),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "disclosure") {
                SettingsNavigationCard(
                    title = stringResource(R.string.torrent_search_settings_disclosure_title),
                    summary = if (state.multiSourceDisclosureAccepted) {
                        stringResource(R.string.torrent_search_settings_disclosure_accepted)
                    } else {
                        stringResource(R.string.torrent_search_settings_disclosure_pending)
                    },
                    onClick = onResetDisclosure,
                    actionLabel = stringResource(R.string.torrent_search_settings_disclosure_reset),
                    enabled = state.multiSourceDisclosureAccepted,
                )
            }
        }
    }
    if (showDisableConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisableConfirmation = false },
            title = { Text(stringResource(R.string.torrent_search_disable_safe_mode_title)) },
            text = { Text(stringResource(R.string.torrent_search_disable_safe_mode_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableConfirmation = false
                        onSafeModeChange(false)
                    },
                ) { Text(stringResource(R.string.torrent_search_disable_safe_mode_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirmation = false }) {
                    Text(stringResource(R.string.torrent_search_button_cancel))
                }
            },
        )
    }
}

/** Provider 与 Torznab 配置管理纯渲染页面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorrentProviderManagementScreen(
    /** 当前不可变设置状态。 */
    state: TorrentSettingsUiState,
    /** 切换单个 Provider。 */
    onProviderEnabledChange: (ProviderId, Boolean) -> Unit,
    /** 启用全部已知来源。 */
    onEnableAll: () -> Unit,
    /** 关闭全部来源。 */
    onDisableAll: () -> Unit,
    /** 恢复内置默认来源。 */
    onReset: () -> Unit,
    /** 生成新 Torznab 稳定 ID。 */
    onNewTorznabConfigId: () -> TorznabConfigId,
    /** 保存 Torznab 非敏感配置与密钥草稿。 */
    onSaveTorznab: (TorznabConfigId, String, String, Category, ProviderSafetyLevel, String?, Boolean) -> Unit,
    /** 删除 Torznab 配置与密钥。 */
    onDeleteTorznab: (TorznabConfigId) -> Unit,
    /** 打开已知受保护 Provider 的 Cloudflare 挑战页。 */
    onOpenChallenge: (ProviderId) -> Unit,
    /** 清除固定错误提示。 */
    onClearError: () -> Unit,
    /** 返回上一页。 */
    onBack: () -> Unit,
) {
    /** editingConfig 为 null 时表示没有打开编辑弹窗。 */
    var editingConfig by remember { mutableStateOf<TorznabConfig?>(null) }
    /** addingConfig 控制新增弹窗，与 editingConfig 互斥。 */
    var addingConfig by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.torrent_search_search_providers_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.torrent_search_action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { addingConfig = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.torrent_search_search_providers_add_screen_title))
                    }
                },
            )
        },
    ) { /** contentPadding 是系统栏与顶部栏提供的安全边距。 */ contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "error") {
                SettingsErrorCard(state.error, onClearError)
            }
            item(key = "actions") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "enable_all") { OutlinedButton(onClick = onEnableAll) { Text(stringResource(R.string.torrent_search_search_providers_action_enable_all)) } }
                    item(key = "disable_all") { OutlinedButton(onClick = onDisableAll) { Text(stringResource(R.string.torrent_search_search_providers_action_disable_all)) } }
                    item(key = "reset") { OutlinedButton(onClick = onReset) { Text(stringResource(R.string.torrent_search_search_providers_action_reset)) } }
                }
            }
            items(
                items = state.providers,
                key = { /** provider 使用稳定 ProviderId 维持开关与编辑状态身份。 */ provider -> provider.descriptor.id.value },
            ) { /** provider 是当前列表展示来源。 */ provider ->
                ProviderCard(
                    provider = provider,
                    onEnabledChange = { /** enabled 是用户请求的开关目标值。 */ enabled ->
                        onProviderEnabledChange(provider.descriptor.id, enabled)
                    },
                    onEdit = provider.torznabConfig?.let { /** config 保留非敏感编辑快照。 */ config ->
                        { editingConfig = config }
                    },
                    onDelete = provider.torznabConfig?.let { /** config 提供稳定删除身份。 */ config ->
                        { onDeleteTorznab(config.id) }
                    },
                    onChallenge = if (provider.descriptor.isCloudflareProtected) {
                        { onOpenChallenge(provider.descriptor.id) }
                    } else {
                        null
                    },
                )
            }
        }
    }
    if (addingConfig || editingConfig != null) {
        /** existingConfig 区分新增与编辑，不包含 API Key 明文。 */
        val existingConfig = editingConfig
        /** configId 新增时只在弹窗首次进入 Composition 时生成一次。 */
        val configId = remember(existingConfig, addingConfig) { existingConfig?.id ?: onNewTorznabConfigId() }
        TorznabEditorDialog(
            configId = configId,
            existingConfig = existingConfig,
            onDismiss = {
                addingConfig = false
                editingConfig = null
            },
            onSave = { /** name 是已在输入框中的展示名。 */ name,
                /** feedUrl 是已在输入框中的服务地址。 */ feedUrl,
                /** category 是用户选择的固定分类。 */ category,
                /** safety 是用户选择的安全等级。 */ safety,
                /** apiKeyDraft 是仅存在于弹窗内存的密钥草稿。 */ apiKeyDraft,
                /** removeStoredKey 表示用户显式删除旧密钥。 */ removeStoredKey ->
                onSaveTorznab(configId, name, feedUrl, category, safety, apiKeyDraft, removeStoredKey)
                addingConfig = false
                editingConfig = null
            },
        )
    }
}

/** 单个 Provider 卡片；动态来源通过可空回调暴露编辑与删除动作。 */
@Composable
private fun ProviderCard(
    /** 当前来源展示状态。 */
    provider: TorrentProviderUiState,
    /** 开关目标值回调。 */
    onEnabledChange: (Boolean) -> Unit,
    /** 动态来源编辑回调。 */
    onEdit: (() -> Unit)?,
    /** 动态来源删除回调。 */
    onDelete: (() -> Unit)?,
    /** 受保护内置来源的挑战页回调。 */
    onChallenge: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(provider.descriptor.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = provider.descriptor.baseUrl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (provider.enabled && !provider.availableInCurrentSafeMode) {
                    Text(
                        text = stringResource(R.string.torrent_search_settings_provider_filtered_by_safe_mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.torrent_search_search_providers_list_action_edit))
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.torrent_search_search_providers_list_action_delete))
                }
            }
            if (onChallenge != null) {
                TextButton(onClick = onChallenge) {
                    Text(stringResource(R.string.torrent_search_cloudflare_open_action))
                }
            }
            Switch(checked = provider.enabled, onCheckedChange = onEnabledChange)
        }
    }
}

/** Torznab 编辑弹窗；API Key 草稿使用 remember，禁止进入 SavedState 或页面状态。 */
@Composable
private fun TorznabEditorDialog(
    /** 当前稳定配置身份。 */
    configId: TorznabConfigId,
    /** 编辑时的非敏感配置；新增时为 null。 */
    existingConfig: TorznabConfig?,
    /** 关闭弹窗且不保存。 */
    onDismiss: () -> Unit,
    /** 提交全部输入。 */
    onSave: (String, String, Category, ProviderSafetyLevel, String?, Boolean) -> Unit,
) {
    /** name 是只存在于弹窗 Composition 的展示名草稿。 */
    var name by remember(configId) { mutableStateOf(existingConfig?.name.orEmpty()) }
    /** feedUrl 是只存在于弹窗 Composition 的地址草稿。 */
    var feedUrl by remember(configId) { mutableStateOf(existingConfig?.feedUrl.orEmpty()) }
    /** category 是当前选择的稳定分类。 */
    var category by remember(configId) { mutableStateOf(existingConfig?.category ?: Category.ALL) }
    /** safety 是当前选择的安全等级。 */
    var safety by remember(configId) { mutableStateOf(existingConfig?.safetyLevel ?: ProviderSafetyLevel.SAFE) }
    /** apiKeyDraft 是敏感临时输入，不使用 rememberSaveable。 */
    var apiKeyDraft by remember(configId) { mutableStateOf("") }
    /** removeStoredKey 仅对已有密钥的编辑配置有意义。 */
    var removeStoredKey by remember(configId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existingConfig == null) R.string.torrent_search_search_providers_add_screen_title
                    else R.string.torrent_search_search_providers_edit_screen_title,
                ),
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "name") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { /** value 是最新名称草稿。 */ value -> name = value },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.torrent_search_search_providers_label_name)) },
                        singleLine = true,
                    )
                }
                item(key = "url") {
                    OutlinedTextField(
                        value = feedUrl,
                        onValueChange = { /** value 是最新 URL 草稿。 */ value -> feedUrl = value },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.torrent_search_search_providers_label_url)) },
                        singleLine = true,
                    )
                    if (feedUrl.trim().startsWith("http://", ignoreCase = true)) {
                        Text(
                            text = stringResource(R.string.torrent_search_settings_http_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item(key = "api_key") {
                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = { /** value 是最新密钥草稿，仅保留在内存。 */ value -> apiKeyDraft = value },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.torrent_search_search_providers_label_api_key)) },
                        supportingText = {
                            if (existingConfig?.hasApiKey == true) Text(stringResource(R.string.torrent_search_settings_api_key_preserved))
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (existingConfig?.hasApiKey == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.torrent_search_settings_remove_api_key), modifier = Modifier.weight(1f))
                            Switch(
                                checked = removeStoredKey,
                                onCheckedChange = { /** remove 是用户请求的密钥删除状态。 */ remove -> removeStoredKey = remove },
                            )
                        }
                    }
                }
                item(key = "category") {
                    Text(stringResource(R.string.torrent_search_settings_torznab_category), style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Category.entries, key = { /** item 使用稳定分类 code。 */ item -> item.code }) { /** item 是分类候选。 */ item ->
                            FilterChip(
                                selected = category == item,
                                onClick = { category = item },
                                label = { Text(torrentCategoryLabel(item)) },
                            )
                        }
                    }
                }
                item(key = "safety") {
                    Text(stringResource(R.string.torrent_search_settings_torznab_safety), style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ProviderSafetyLevel.entries, key = { /** item 使用稳定安全 code。 */ item -> item.code }) { /** item 是安全等级候选。 */ item ->
                            FilterChip(
                                selected = safety == item,
                                onClick = { safety = item },
                                label = { Text(providerSafetyLabel(item)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(name, feedUrl, category, safety, apiKeyDraft.ifBlank { null }, removeStoredKey)
                },
            ) { Text(stringResource(R.string.torrent_search_search_providers_button_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.torrent_search_button_cancel)) }
        },
    )
}

/** 通用设置导航卡；只承载标题、摘要与单一动作。 */
@Composable
private fun SettingsNavigationCard(
    /** 行标题。 */
    title: String,
    /** 行摘要。 */
    summary: String,
    /** 点击动作。 */
    onClick: () -> Unit,
    /** 可选动作文案；为空时整卡仍可点击。 */
    actionLabel: String? = null,
    /** 当前动作是否可用。 */
    enabled: Boolean = true,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionLabel != null) Text(actionLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 通用设置开关卡；业务确认仍由页面编排。 */
@Composable
private fun SettingsSwitchCard(
    /** 行标题。 */
    title: String,
    /** 行摘要。 */
    summary: String,
    /** 当前开关值。 */
    checked: Boolean,
    /** 当前开关是否可交互。 */
    enabled: Boolean,
    /** 开关目标值回调。 */
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

/** 枚举型设置的横向单选行。 */
@Composable
private fun <T> ChoiceRow(
    /** 候选值与展示标签。 */
    labels: List<Pair<T, String>>,
    /** 当前选中值。 */
    selected: T,
    /** 是否允许修改。 */
    enabled: Boolean,
    /** 从枚举值生成稳定列表身份。 */
    keyOf: (T) -> String,
    /** 用户选择回调。 */
    onSelected: (T) -> Unit,
) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = labels,
            key = { /** item 使用值侧的稳定协议生成列表身份。 */ item -> keyOf(item.first) },
        ) { /** item 包含稳定值和本地化标签。 */ item ->
            FilterChip(
                selected = selected == item.first,
                onClick = { onSelected(item.first) },
                label = { Text(item.second) },
                enabled = enabled,
            )
        }
    }
}

/** 设置分组标题。 */
@Composable
private fun SettingsSectionTitle(
    /** 本地化标题。 */
    text: String,
) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 固定错误提示卡；错误为空时不占据布局。 */
@Composable
private fun SettingsErrorCard(
    /** 不含敏感内容的固定错误分类。 */
    error: TorrentSettingsError?,
    /** 清除错误回调。 */
    onClearError: () -> Unit,
) {
    if (error == null) return
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClearError)) {
        Text(
            text = settingsErrorLabel(error),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 排序标准到本地化文案的纯展示映射。 */
@Composable
private fun sortCriteriaLabel(
    /** 待展示排序标准。 */
    criteria: SortCriteria,
): String = stringResource(
    when (criteria) {
        SortCriteria.NAME -> R.string.torrent_search_action_sort_criteria_name
        SortCriteria.SEEDERS -> R.string.torrent_search_action_sort_criteria_seeders
        SortCriteria.PEERS -> R.string.torrent_search_action_sort_criteria_peers
        SortCriteria.FILE_SIZE -> R.string.torrent_search_action_sort_criteria_file_size
        SortCriteria.DATE -> R.string.torrent_search_action_sort_criteria_date
    },
)

/** 排序方向到本地化文案的纯展示映射。 */
@Composable
private fun sortOrderLabel(
    /** 待展示排序方向。 */
    order: SortOrder,
): String = stringResource(
    when (order) {
        SortOrder.ASCENDING -> R.string.torrent_search_action_sort_order_ascending
        SortOrder.DESCENDING -> R.string.torrent_search_action_sort_order_descending
    },
)

/** DNS 模式到本地化文案的纯展示映射。 */
@Composable
private fun dnsModeLabel(
    /** 待展示 DNS 模式。 */
    mode: TorrentSearchDnsMode,
): String = stringResource(
    when (mode) {
        TorrentSearchDnsMode.SYSTEM -> R.string.torrent_search_settings_dns_system
        TorrentSearchDnsMode.CLOUDFLARE -> R.string.torrent_search_settings_dns_cloudflare
    },
)

/** Provider 安全等级到本地化文案的纯展示映射。 */
@Composable
private fun providerSafetyLabel(
    /** 待展示安全等级。 */
    level: ProviderSafetyLevel,
): String = stringResource(
    when (level) {
        ProviderSafetyLevel.SAFE -> R.string.torrent_search_settings_safety_safe
        ProviderSafetyLevel.UNSAFE -> R.string.torrent_search_safety_status_unsafe
        ProviderSafetyLevel.NSFW -> R.string.torrent_search_nsfw
    },
)

/** 设置错误到固定本地化文案的纯展示映射。 */
@Composable
private fun settingsErrorLabel(
    /** 不含敏感数据的错误分类。 */
    error: TorrentSettingsError,
): String = stringResource(
    when (error) {
        TorrentSettingsError.INVALID_CONFIG -> R.string.torrent_search_settings_error_invalid_config
        TorrentSettingsError.SECRET_FAILED -> R.string.torrent_search_settings_error_secret_failed
        TorrentSettingsError.WRITE_FAILED -> R.string.torrent_search_settings_error_write_failed
    },
)
