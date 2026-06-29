状态：已完成

# 按 App 过滤剪贴保存方案

## 当前状态

应用通过 Shizuku 进程直读剪贴板，并把来源包名、来源名称、来源图标 hash 和剪贴 payload 提交给主进程 Provider。主进程由 `ClipHelper.processClipText(...)` 统一执行去重、链接解析、入库、通知和自动备份 dirty 标记。历史来源 App 继续缓存在 `source_apps` 表，搜索页来源筛选只读取 `source_apps`。

来源过滤能力已经落地：过滤名单只保存完整包名，按完整包名精确匹配，只影响未来剪贴保存，不删除、不隐藏历史记录。过滤页按自用/私有分发取舍声明 `android.permission.QUERY_ALL_PACKAGES`，在页面可见或用户刷新时由主进程 `PackageManager` 直接读取当前安装应用名称、包名、系统/可启动标记和图标；不再使用 Shizuku 安装应用缓存、Provider 同步、Room `installed_app_cache` 或 no-backup 安装图标目录。本轮验证前置条件是卸载旧 App 后重新安装，不做旧安装缓存生产迁移或清理代码。

## 目标

- 用户可以从“我的/设置”管理剪贴来源 App 过滤名单，入口展示未设置或已过滤数量。
- 用户可以在详情页对当前明确来源 App 快捷屏蔽或取消屏蔽后续剪贴。
- 过滤页以 App 图标和 App 名作为主要识别信息，保存时只写入包名。
- 保存链路在文本 trim 并更新 `lastClipContent` 后读取一次名单快照，命中后直接返回 `FilteredBySourceApp`。
- 过滤命中不查库、不入库、不解析链接、不通知、不 toast、不调度自动备份。
- 名单进入 `data/settings.json`，恢复时与本机名单取并集，不覆盖本机新增规则。

## 范围

- `AppSetting.blockedClipSourcePackages`、原子 add/remove/replace 方法和纯规则 `ClipSourceBlockRules`。
- `ClipHelper.processClipText(...)` 的来源过滤分支和 `ClipProcessResult.FilteredBySourceApp`。
- Provider `commit_clip` 返回 `source_app_blocked` 且 `clipCommitted=false`。
- app 层 `InstalledAppReader`、`PackageManagerInstalledAppReader`、`InstalledAppIconLoader` 和过滤页候选合并。
- “我的”页过滤名单入口、独立二级选择页、显示系统应用、手动添加、清空确认和重新读取应用列表。
- 详情页来源快捷屏蔽/取消屏蔽动作与确认弹窗。
- 备份导出和恢复的 `blocked_clip_source_packages` 字段。

## 非目标

- 不删除、不隐藏、不迁移历史剪贴记录。
- 不把 App 名称、图标、系统安装列表或拦截次数统计保存进过滤名单或 WebDAV/本地备份。
- 不持久化当前安装应用列表，不写 Room，不写 MMKV，不写 no-backup 图标目录。
- 不提供“测试当前剪贴来源”按钮；只承诺之后由 Shizuku 识别到这些包名时不保存。
- 不做 App 名匹配、前缀匹配、包含匹配或大小写归一。
- 不区分主空间与工作资料空间；当前按包名过滤，同包名在不同空间会一起命中。

## 用户体验

- “我的/设置”新增“剪贴保存过滤”入口，描述为“未设置”或“已过滤 N 个 App”。
- 点击入口进入独立二级选择页，候选行以图标和 App 名作为主识别信息，包名作为副标题；已卸载或暂不可识别的已保存包名标题回退包名，副标题说明“未安装或暂不可识别”。
- 候选合并优先级固定为：已过滤包名、当前安装应用直读结果、历史来源；同包名名称和图标冲突时优先使用当前安装应用名称和图标，历史来源只兜底未安装或暂不可识别包名。
- 默认展示可启动非系统 App、历史来源 App、已过滤包名；系统 App 默认隐藏，但搜索可以命中，用户也可打开“显示系统应用”。
- 未读取到应用或读取失败时仍展示已保存包名和历史来源，允许手动添加、清空草稿和重新读取应用列表。
- 手动添加拒绝空白、当前 App、超长、明显危险字符和超过 500 个的名单；不过度限制厂商特殊包名。
- 清空全部需要二次确认，文案说明清空后这些 App 后续剪贴会重新允许保存。
- 详情页只有来源包名明确时展示“屏蔽此应用后续剪贴”或“取消屏蔽此应用”；确认弹窗必须说明只影响以后保存，不删除当前或历史记录。

## 数据和规则

- 名单持久化在 MMKV key `blocked_clip_source_packages`，内部为换行分隔包名；`AppSetting` 对外只暴露规范化后的 `Set<String>` 和 `StateFlow`。
- 规范化规则：trim、去空、拒绝空白/控制字符/少数危险分隔符、单个包名最多 200 字符、去重、排序、总数最多 500。
- 匹配规则：`null` 或空来源不匹配；只按完整包名精确匹配；大小写保持原样，不做归一。
- 保存链路读取本次处理开始时的名单快照，后续用户修改名单不会回溯影响已经开始的处理。
- 链接预览异步补齐复用首次保存已通过过滤的语义；一条剪贴已完成首次保存后，后续元数据补齐不再因为用户刚屏蔽来源而中断。
- 备份只导出规范化包名；恢复旧备份缺字段时按空名单兼容；恢复新字段时与本机名单取并集并按同一规则裁剪。

## 安装应用读取

- 主 App 声明 `android.permission.QUERY_ALL_PACKAGES`，旁边保留私有分发取舍注释和 `tools:ignore="QueryAllPackagesPermission"`；未来如果要上架 Google Play，必须重新评估。
- `PackageManagerInstalledAppReader` 在 `Dispatchers.IO` 读取安装应用，Android 13+ 使用 `ApplicationInfoFlags.of(0)`，低版本使用旧 flags API，不使用 `MATCH_DISABLED_COMPONENTS` 强行展示禁用/冻结 App。
- 可启动标记通过一次性 `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` 构建包名集合，不逐个调用 `getLaunchIntentForPackage`。
- 读取规则：排除当前 App，App 名裁剪到 80 字符，空标签回退包名，按 App 名和包名稳定排序；当前 App 包名拒绝规则同时用于安装列表和手动添加。
- ViewModel 只保存当前页面会话内存列表、loading、失败 reason、lastLoadedAtMillis 和摘要；不在 ViewModel 初始化时读取，只在页面进入或用户刷新时读取。
- 连续刷新会取消旧读取任务，并用请求序号避免旧结果晚返回覆盖新结果。
- UI 使用明确图标模型区分“当前安装应用图标 / 历史文件图标 / 无图标”。当前安装应用图标不进入 ViewModel，不携带 Drawable，不写文件。
- `InstalledAppIconLoader` 只在可见行按包名通过 Coil fetcher 读取图标：Launcher Activity 图标优先，Application 图标兜底，失败显示通用 App 图标；历史来源继续用 `SourceAppIconModel` 加载旧文件路径。

## 日志与诊断计划

- 过滤命中日志只记录 `reasonCode=source_app_blocked`、来源是否可识别和文本长度摘要，不记录包名、剪贴正文、HTML、完整 URL、链接标题或拦截次数统计。
- 安装应用读取日志只记录阶段、数量、耗时和 reasonCode，例如 `package_visibility_denied`、`package_scan_failed`、`package_scan_empty`；禁止记录完整 App 列表、图标内容或用户剪贴内容。
- 当前安装应用图标加载失败日志限频，只按包名 hash 首次记录 `installed_app_icon_failed`、reasonCode 和异常类型；不输出完整包名、完整安装列表或图标字节。
- 手动添加、拒绝当前 App、详情页加入/移除和备份裁剪使用低敏 reasonCode：`manual_package_added`、`self_package_rejected`、`detail_block_source_added`、`detail_block_source_removed`、`backup_blocked_packages_trimmed`。
- 后台过滤命中不 toast；只有详情页主动加入或移除名单后展示轻提示。

## 测试验证

- 纯 JVM 单测覆盖包名规范化、空来源不拦截、精确命中、大小写不误合并、重复去重、当前 App 拒绝、长度和总数限制、危险字符拒绝。
- 安装应用 reader 单测覆盖排除当前 App、名称裁剪、空标签回退、系统/可启动标记、稳定排序、一次性 Launcher 集合策略和失败 reason。
- 设置页候选合并测试覆盖已过滤包名置顶、当前安装应用优先、历史来源兜底、未安装包名可移除、系统应用默认隐藏但搜索/开关可见。
- Provider/映射测试覆盖 `FilteredBySourceApp` 转为 `source_app_blocked`，且 `clipCommitted=false`。
- 备份测试覆盖 `blocked_clip_source_packages` 导出、旧备份缺字段兼容、恢复时与本机名单取并集、异常超量备份裁剪。
- 编译验证：`:base:general:compileDebugKotlin`、`:shizuku:compileDebugKotlin`、`:app:compileDebugKotlin`、`:app:lintDebug` 和 `git diff --check`。
- 真机验证前需要卸载旧 App 再重新安装，进入来源过滤页确认可看到 App 名和图标；屏蔽一个 App 后从该 App 复制不入库，取消屏蔽后恢复保存。

## 已知取舍

- v1 不统计拦截次数，避免诱导记录高敏来源行为，也避免新增数据库字段和备份协议。
- `QUERY_ALL_PACKAGES` 是自用/私有分发取舍，换取简单可靠的安装应用展示；如果未来要公开上架，需要重新设计包可见性策略。
- 工作资料空间暂不区分 userId；后续若要区分，需要重新设计 UI 文案与匹配规则。
- v1 不做后台安装/卸载监听；进入页面读取一次，用户刷新时重新读取一次。

## 开放问题

- 未来若要上架 Google Play，需要评估是否回到 Shizuku 查询、用户手动添加、系统 picker 或更窄的 `<queries>` 声明。
- 若当前安装图标在个别 ROM 上仍显示默认图标，再补 ROM 维度的低敏诊断，但不恢复旧 Provider 安装缓存方案。

## 变更记录

- 2026-06-29：来源过滤页改为主进程 `QUERY_ALL_PACKAGES` + `PackageManager` 直读安装应用和可见行懒加载图标，直接删除未发布的 `installed_app_cache`、Provider 安装缓存同步、Shizuku 安装列表 AIDL、旧测试和旧备份排除目录，并将 `ShizukuConnector.VERSION` 从 `27` 升级到 `28`；原因是旧同步方案复杂且真机图标补齐慢，自用/私有分发可以接受包可见性权限。
- 2026-06-18：废弃前方案曾将安装应用图标状态从逐个图标查询改为批量 `query_installed_app_icon_states_batch`，并引入 no-backup 安装图标目录；本轮已删除该可执行链路，仅作为历史取舍记录保留。
- 2026-06-17：新增“剪贴保存过滤”设置入口和独立二级选择页接入说明，并补充 App 图标 + App 名展示和页面草稿保存边界；原因是用户需要从我的页集中管理按来源 App 屏蔽后续剪贴保存，且只看包名难以识别 App。
