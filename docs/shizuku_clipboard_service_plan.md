状态：已完成

# Shizuku 剪贴板服务方案

## 当前状态

剪贴读取的正式链路已经收敛为 Shizuku 进程直读剪贴板快照，再通过 app Provider 提交 payload 入库：

- `ClipboardShizukuService` 在 Shizuku 进程中注册 AppOps 隐藏 API 监听器；收到其他应用写入剪贴板事件后，生成 `eventId`、记录 `capturedAtMillis`，并通过 `ShizukuClipboardReader` 委托 `SystemClipboardHiddenReader` 使用 `IClipboard.getPrimaryClip` 读取当前 `ClipData` 快照。
- Shizuku 进程把剪贴 payload 写入 `content://<authority>/clip/<eventId>`，再调用 Provider `commit_clip`。app 侧只消费 payload 临时文件，不再通过主进程透明悬浮窗读取系统剪贴板。
- app 主进程不可达时，Shizuku 只通过 NoDisplay `ShizukuWakeActivity` 唤醒主进程；`ShizukuWakeActivity` 只提交 `ShizukuConnector.requestConnect("wake_activity")` 并立即结束，不读取、不保存、不展示业务 UI。
- AIDL callback 只保留无副作用 `pingAppProcess()` 探活；旧 `ShizukuCallback.onOpNoted(...)` 保存回调已删除，不保留旧版本兼容分支。
- Provider 对外只保留 `commit_clip`、`query_icon_state`、`commit_icon`、`query_shizuku_process`。旧 `read_clip` 和旧安装应用缓存同步 methods 均已删除，外部继续调用时进入默认不支持分支，返回 `invalid_args` 并只记录 method 名、callingUid 等低敏信息。
- 来源 App 过滤命中时，`ClipHelper.processClipText(...)` 返回 `FilteredBySourceApp`，Provider 对外返回 `clipStatus=source_app_blocked` 且 `clipCommitted=false`；该状态表示已按用户规则处理完成，不触发通知、链接解析或备份调度。
- Shizuku 不再为过滤页提供安装应用列表或图标；过滤页已改为主进程 `QUERY_ALL_PACKAGES` + `PackageManager` 直读，完整规则以 `docs/clip_source_app_filter_plan.md` 为准。
- `ShizukuConnector.VERSION = 28`，UserService tag 继续携带协议版本，用于覆盖安装后重建残留 Shizuku UserService，避免旧进程继续暴露已删除的安装列表缓存 AIDL 或旧 Provider 协议。

## 目标

- 使用 Shizuku 进程 `IClipboard` 直读规避普通 app overlay 焦点不稳定和部分系统上读空的风险。
- 删除主进程透明悬浮窗、剪贴读取前台服务、旧 Provider `read_clip` 和旧 AIDL 保存回调，避免维护双链路和双写风险。
- 保持剪贴去重语义可解释：空内容不新增记录；同内容同明确来源更新原记录；同内容不同明确来源新增记录；已有来源为空、`Unknown` 或“未知”时允许后续明确来源覆盖升级。
- 保持剪贴保存通知、下载通知、下载 Worker 前台服务、Shizuku 状态通知等通知能力。
- 保持来源图标异步补齐、`source_apps` 缓存、Room schema 和备份协议不变；图标 Drawable 读取统一为 Launcher Activity 优先。

## 范围

- `ClipboardShizukuService`、`ClipboardListener`、`ShizukuClipboardReader`、`SystemClipboardHiddenReader`。
- `ShizukuAppProcessReadiness`、`ShizukuAppWakeCommandRunner`、`ShizukuProcessIdentity`。
- `ClipboardBridgeContract`、`ClipboardBridgeProviderCommandClient`、`ClipboardBridgeIconSyncCoordinator`。
- app Provider 的 `commit_clip`、`query_icon_state`、`commit_icon`、`query_shizuku_process`。
- `ShizukuConnector` 的进程名刷新、带版本 tag、互斥 bind 和 callback 注册。

明确不包含：

- 修改 `source_apps` 结构或历史来源备份协议。
- 参与过滤页当前安装应用展示；该页面已改由主进程本地读取。
- 主动删除系统里已经存在的旧通知渠道。
- 主动支持 Android force-stop 后的后台唤醒；该场景受系统限制。

## 数据流

1. 主进程连接 Shizuku 并创建 `ClipboardShizukuService(Context)`。
2. `ClipboardShizukuService.start()` 添加隐藏 API 豁免并注册 `ClipboardListener` 到 `AppOpsManagerHidden.startWatchingNoted(...)`。
3. `ClipboardListener` 过滤自身包名后调用 `handleOpNoted(clipPackageName)`。
4. `handleOpNoted()` 每次事件生成独立 `eventId` 和 `capturedAtMillis`，立即读取 Shizuku 进程内的 `ClipData` 快照。
5. `ShizukuClipboardReader` 只提取第一个 `ClipData.Item` 的文本、HTML、MIME 与 URI/Intent 布尔摘要；日志只记录长度和类型，不输出正文。
6. `ShizukuAppProcessReadiness` 使用 `pingAppProcess()` 探活 app 主进程 callback。
7. callback 缺失、超时或异常时，`ShizukuAppWakeCommandRunner` 只执行 NoDisplay Activity 唤醒。
8. Shizuku 调用 Provider `query_shizuku_process`，刷新 app 当前期望的完整 Shizuku 进程名，并 best-effort 请求最新 UserService 绑定。
9. `ShizukuProcessIdentity` 只比较完整进程名字符串：明确匹配才继续提交，明确不匹配时旧进程 `destroy()`，不确定时跳过本次提交。
10. 身份匹配后，Shizuku 并行启动剪贴 payload 提交与来源图标同步。剪贴链路写入 `/clip/<eventId>` 后调用 `commit_clip`；图标链路继续使用 `query_icon_state`、`/icon/<eventId>` 和 `commit_icon`。
11. `ClipboardBridgeClipCommitCoordinator` 读取 payload、校验版本和 eventId、解析文本或 HTML fallback，再委托 `ClipHelper.processClipText(...)`。
12. `ClipHelper.processClipText(...)` 在文本 trim 并更新 `lastClipContent` 后读取来源过滤名单快照；命中时直接返回 `FilteredBySourceApp`，不进入数据库查询、链接解析、通知或自动备份调度。
13. `commit_clip` 无论成功、失败或异常都会清理自己的 payload 临时文件；过期清理只删除超时 `.tmp`。

## Provider 接口与返回契约

保留接口：

- `commit_clip`：消费 `/clip/<eventId>` payload，解析并入库。
- `query_icon_state`：判断来源图标是否需要继续同步。
- `commit_icon`：消费 `/icon/<eventId>` PNG，保存来源图标并更新 `source_apps`。
- `query_shizuku_process`：返回 app 当前期望的完整 Shizuku 进程名并触发 best-effort 连接请求。

已移除接口：

- `read_clip`：不再声明常量，不再有 Provider 分支；旧调用进入默认不支持 method 分支并返回 `invalid_args`。
- 过滤页安装应用同步相关 AIDL、Provider method 和临时文件 path；过滤页不再依赖 Shizuku 读取安装应用或同步图标，历史接口名称只在变更记录中保留。

## 日志与诊断计划

- Shizuku 侧记录 AppOps 回调、隐藏 API 注册/注销错误、`IClipboard` 读取摘要、Provider `content write/call` 的 exitCode、resultCode、clipStatus、iconStatus、reasonCode 和耗时。
- 剪贴链路只允许记录 `eventId`、`clipNull`、payload 是否为空、item 数量、MIME 类型、text/html 长度、是否含 URI/Intent、写入字节数、exitCode、resultCode、clipStatus、异常类型和耗时。
- 来源图标链路只记录 packageName、iconHash、图标尺寸、字节数、`iconDecisionReason`、缓存命中、是否上传、是否复用旧图标、timeout 和 resultCode；禁止记录完整安装列表、图标内容或剪贴内容。
- app 主进程探活链路记录 `appPingResult`、`appWakeRequested`、`appWakeMode`、`appWakeResult`、`callbackRebound`、`wakeCooldownSkipped`、`appWakeElapsedMs`、`readyForProviderQuery` 和 `reasonCode`。
- Provider 默认不支持 method 分支只记录 method 名、callingUid 和 resultCode；禁止记录 extras 中可能包含的正文、HTML、URI 或 Intent。
- 来源过滤命中日志只允许记录 `reasonCode=source_app_blocked`、来源是否可识别和文本长度摘要；禁止记录包名、剪贴正文、完整 URL、完整 App 列表或拦截次数。

## 测试验证

- `ClipboardBridgeCommandResultParserTest` 覆盖 `commit_clip`、`query_icon_state`、`commit_icon`、`query_shizuku_process`、NoDisplay 唤醒输出和 Provider 缺失解析。
- `ClipboardBridgeProviderCommandClientTest` 覆盖 content extra 转义、payload/icon stdin 写入、剪贴/图标命令超时分层。
- app Provider 测试覆盖 `source_app_blocked` 属于 Provider 已处理完成状态，且 `clipCommitted=false`。
- 编译验证：`:base:general:compileDebugKotlin`、`:shizuku:compileDebugKotlin`、`:app:compileDebugKotlin` 和 `git diff --check`。

## 已知取舍

- 不保留旧 `read_clip` / `onOpNoted(...)` 兼容逻辑，也不设计旧版本回退路径。
- Provider 模式不再 fallback 到 overlay 或 AIDL，原因是正式链路需要暴露 Shizuku 直读与 payload 提交的真实失败率。
- 第一版仍只处理第一个 `ClipData.Item`，多 item、URI、Intent、图片和文件搬运留到后续阶段。
- `AppSetting.shizukuSuffix` 属于本机运行态，不纳入备份。

## 变更记录

- 2026-06-29：删除未发布的过滤页安装应用缓存同步方案，移除 `refreshInstalledAppCache` AIDL、`installed_app_*` Provider 协议、Shizuku 安装缓存 syncer/contract/diagnostics，并将 `ShizukuConnector.VERSION` 从 `27` 升级到 `28`；原因是过滤页改为主进程 `QUERY_ALL_PACKAGES` 直读。
- 2026-06-18：废弃前方案曾新增批量安装应用图标状态查询与 no-backup 安装图标目录；本轮已删除可执行链路，仅作为历史取舍记录保留。
- 2026-06-04：清理 `ClipboardShizukuService` 中运行时不可达的旧 AIDL 保存分支和关闭的 overlay 调试回退，并拆出 Provider 命令客户端、Shizuku shell 命令执行器、图标同步协调器、app 唤醒命令执行器和来源应用解析器；原因是 Provider 直读链路已成为正式路径。
