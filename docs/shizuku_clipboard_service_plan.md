状态：已完成

# Shizuku 剪贴板服务方案

## 当前状态

剪贴读取的正式链路已经收敛为 Shizuku 进程直读剪贴板快照，再通过 app Provider 提交 payload 入库：

- `ClipboardShizukuService` 在 Shizuku 进程中注册 AppOps 隐藏 API 监听器；收到其他应用写入剪贴板事件后，立即生成 `eventId`、记录 `capturedAtMillis`，并通过 `ShizukuClipboardReader` 委托 `SystemClipboardHiddenReader` 使用 `IClipboard.getPrimaryClip` 读取当前 `ClipData` 快照。
- Shizuku 进程把剪贴 payload 写入 `content://<authority>/clip/<eventId>`，再调用 Provider `commit_clip`。app 侧只消费 payload 临时文件，不再通过主进程透明悬浮窗读取系统剪贴板。
- app 主进程不可达时，Shizuku 只通过 NoDisplay `ShizukuWakeActivity` 唤醒主进程；`ShizukuWakeActivity` 只提交 `ShizukuConnector.requestConnect("wake_activity")` 并立即结束，不读取、不保存、不展示业务 UI。
- AIDL callback 只保留无副作用 `pingAppProcess()` 探活；旧 `ShizukuCallback.onOpNoted(...)` 保存回调已删除，不保留旧版本兼容分支。
- Provider 对外只保留 `commit_clip`、`query_icon_state`、`commit_icon`、`query_shizuku_process`。旧 `read_clip` method 已删除，外部继续调用时进入默认不支持分支，返回 `invalid_args` 并只记录 method 名、callingUid 等低敏信息。
- app 侧 `ClipboardService`、`ClipboardBridgeReadCoordinator`、`SYSTEM_ALERT_WINDOW` 权限、悬浮窗权限 UI 和读取剪贴板前台服务通知已删除。
- 剪贴保存后的通知保留：只有 `ClipHelper.processClipText(...)` 返回 `ClipProcessResult.Saved` 时才调用 `NotificationHelper.notifyClipUpdate(...)`；空内容、连续重复或本次未知来源命中已有明确来源时返回 `duplicate_or_empty`，不发送误导通知。
- 下载 Worker 仍保留 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC` 和 WorkManager 前台服务声明，因为图片/视频下载进度与结果仍需要前台服务和通知系统。
- `ShizukuConnector.VERSION` 已升级到 `18`，目的不是兼容旧协议，而是强制重建残留 Shizuku UserService，避免覆盖安装后旧进程继续运行过时代码。

## 目标

- 使用 Shizuku 进程 `IClipboard` 直读规避普通 app overlay 焦点不稳定和 MIUI 等系统上读空的风险。
- 删除主进程透明悬浮窗、剪贴读取前台服务、旧 Provider `read_clip` 和旧 AIDL 保存回调，避免维护双链路和双写风险。
- 保持剪贴去重语义可解释：空内容不新增记录；同内容同明确来源更新原记录；同内容不同明确来源新增记录；已有来源为空、`Unknown` 或“未知”时允许后续明确来源覆盖升级。
- 保持剪贴保存通知、下载通知、下载 Worker 前台服务、Shizuku 状态通知等通知能力。
- 让通知权限只影响提醒展示，不再作为连接 Shizuku 或剪贴读取的前置条件。
- 保持来源图标异步补齐、`source_apps` 缓存、Room schema 和备份协议不变；本轮只调整剪贴入库的同内容来源判定规则。

## 范围

本方案覆盖以下核心文件和职责：

- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardShizukuService.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ShizukuAppProcessReadiness.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ShizukuAppWakeCommandRunner.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardBridgeContract.kt`
- `shizuku/src/main/aidl/com/cla/clip/shizuku/ShizukuCallback.aidl`
- `app/src/main/java/com/cla/clip/master/wake/ShizukuWakeActivity.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeProvider.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeClipCommitCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconQueryCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconCommitter.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconStore.kt`
- `app/src/main/java/com/cla/clip/master/utils/ShizukuConnector.kt`
- `app/src/main/java/com/cla/clip/master/utils/ClipHelper.kt`
- `app/src/main/java/com/cla/clip/master/utils/NotificationHelper.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/ShizukuServiceUnavailableTip.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/mine/MineVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/mine/MinePage.kt`
- `base/general/src/main/java/com/cla/clip/base/general/utils/PermissionUtils.kt`
- `app/src/main/AndroidManifest.xml`
- `base/general/src/main/res/values/strings.xml`
- `app/src/main/res/values/strings.xml`

明确不包含：

- 新增数据库字段、Room schema 迁移或备份协议变化。
- 改动来源图标 hash、decode、保存、缓存命中、坏文件清理或来源筛选语义。
- 主动删除系统里已经存在的旧 `read_clip_channel_id` 通知渠道；代码停止创建即可。
- 主动支持 Android force-stop 后的后台唤醒；该场景受系统限制，不作为本轮核心验收失败。

## 用户体验

- 用户复制普通文本或富文本时，Shizuku 服务在回调后直接读取剪贴板快照并提交给 Provider 入库。
- 保存成功后仍显示“剪贴更新通知/剪贴保存通知”，点击通知继续进入剪贴详情页。
- 通知权限关闭时，剪贴仍会保存，只是不展示剪贴保存、下载进度和 Shizuku 状态提醒。
- “我的/权限”作为页面最上方独立分组，固定展示 Shizuku 和通知两个权限项，不再展示悬浮窗项，也不再保留“权限说明”展开/收起动画；通知项只表达提醒展示能力。
- 列表页 Shizuku 提示不再自动请求通知权限，也不把通知权限作为连接 Shizuku 的前置条件。

## 数据流

1. 主进程连接 Shizuku 并创建 `ClipboardShizukuService(Context)`。
2. `ClipboardShizukuService.start()` 添加隐藏 API 豁免并注册 `ClipboardListener` 到 `AppOpsManagerHidden.startWatchingNoted(...)`。
3. `ClipboardListener` 过滤自身包名后调用 `handleOpNoted(clipPackageName)`。
4. `handleOpNoted()` 每次事件生成独立 `eventId` 和 `capturedAtMillis`，立即读取 Shizuku 进程内的 `ClipData` 快照。
5. `ShizukuClipboardReader` 只提取第一个 `ClipData.Item` 的文本、HTML、MIME 与 URI/Intent 布尔摘要；日志只记录长度和类型，不输出正文。
6. `ShizukuAppProcessReadiness` 使用 `pingAppProcess()` 探活 app 主进程 callback。
7. callback 缺失、超时或异常时，`ShizukuAppWakeCommandRunner` 只执行 `am start --activity-no-animation -n <package>/com.cla.clip.master.wake.ShizukuWakeActivity`。
8. `ClipboardBridgeCommandResultParser.isAppWakeCommandSuccessful(...)` 只接受 NoDisplay Activity 的 `Starting:` 输出，并拒绝旧前台服务输出和包含 `Error`/`Exception` 的输出。
9. `ShizukuWakeActivity` 提交 `ShizukuConnector.requestConnect("wake_activity")` 后立即 `finish()`；连接器内部复用互斥、绑定中状态和进程名判断，避免高频 bind。
10. `ShizukuAppProcessReadiness` 用 `Mutex` 共享同一轮 `wake + wait callback`，唤醒失败后进入短 cooldown；并发剪贴事件仍保留各自已经读取的剪贴快照。
11. Shizuku 调用 Provider `query_shizuku_process`，刷新 app 当前期望的完整 Shizuku 进程名，并 best-effort 请求最新 UserService 绑定。
12. `ShizukuProcessIdentity` 只比较完整进程名字符串：明确匹配才继续提交，明确不匹配时旧进程 `destroy()`，不确定时跳过本次提交。
13. 身份匹配后，Shizuku 并行启动剪贴 payload 提交与来源图标同步。剪贴链路写入 `/clip/<eventId>` 后调用 `commit_clip`；图标链路继续使用 `query_icon_state`、`/icon/<eventId>` 和 `commit_icon`。
14. `ClipboardBridgeClipCommitCoordinator` 读取 payload、校验版本和 eventId、解析文本或 HTML fallback，再委托 `ClipHelper.processClipText(...)`。
15. `ClipHelper.processClipText(...)` 复用链接解析、备份 dirty 标记和通知语义，并通过 `ClipSaveResult` 区分真实写库与重复跳过；只有保存或更新真实剪贴记录后才发送剪贴更新通知。
16. `commit_clip` 无论成功、失败或异常都会清理自己的 payload 临时文件；过期清理只删除超时 `.tmp`，不清空整个目录。

## Provider 接口与返回契约

保留接口：

- `commit_clip`：消费 `/clip/<eventId>` payload，解析并入库。
- `query_icon_state`：判断来源图标是否需要继续同步。
- `commit_icon`：消费 `/icon/<eventId>` PNG，保存来源图标并更新 `source_apps`。
- `query_shizuku_process`：返回 app 当前期望的完整 Shizuku 进程名并触发 best-effort 连接请求。

已移除接口：

- `read_clip`：不再声明常量，不再有 Provider 分支；旧调用进入默认不支持 method 分支并返回 `invalid_args`。

保留通知点击协议：

- `TARGET_DETAIL`
- `EXTRA_CLIP_ID`
- `EXTRA_TIMESTAMP`

## 结果码、状态与原因码

结果码：

- `ok`：当前阶段已按协议完成。
- `invalid_caller`：调用方不是 shell/root。
- `invalid_args`：method、`eventId` 或 extras 非法；旧 `read_clip` 调用也会走该结果。
- `payload_missing`：`commit_clip` 找不到对应 payload 临时文件。
- `invalid_payload`：payload JSON、版本、eventId 或时间戳非法。
- `unsupported_clip_type`：当前剪贴类型第一版不支持。
- `no_clip`：剪贴板为空或 payload 没有可提交内容。
- `commit_failed`：剪贴提交链路异常。
- `icon_missing`：图标文件缺失、半文件异常、decode 失败或 hash 校验失败。
- `icon_commit_failed`：图标预判或提交阶段出现非预期异常。
- `timeout`：Provider 等待当前阶段完成超时。
- `shizuku_process_missing`：app 侧无法提供可信期望 Shizuku 完整进程名。

剪贴状态：

- `saved`：本次剪贴文本已新增或更新到数据库。
- `duplicate_or_empty`：文本为空白、命中连续重复规则，或本次未知来源命中已有明确来源；不会发送剪贴更新通知。
- `no_clip`：没有可读取的剪贴快照。
- `unsupported_clip_type`：第一版不支持当前类型。
- `payload_missing`：payload 文件缺失。
- `invalid_payload`：payload 不合法。
- `commit_failed`：提交链路异常失败。

图标决策原因：

- `cache_hit`
- `stale_file_missing`
- `no_cached_icon`
- `hash_changed`
- `no_icon_available`

app 探活和身份原因码：

- `callback_missing`
- `ping_ok`
- `ping_timeout`
- `ping_false`
- `wake_succeeded`
- `wake_command_timeout`
- `wake_command_failed`
- `wake_activity_started_callback_timeout`
- `wake_cooldown_skipped`
- `identity_query`
- `missing_expected_process_name`
- `connect_request_failed`
- `missing_current_process_name`
- `provider_query_failed`
- `process_matched`
- `process_mismatched`

## 通知与权限

- 删除的只是“读取剪贴板前台服务通知”：`READ_CLIP_CHANNEL_ID`、`READ_CLIP_NOTIFICATION_ID`、`readClipForeground(...)` 和相关读取中提示。
- 保留“剪贴数据更新通知”：`CLIP_UPDATE_CHANNEL_ID`、`CLIP_UPDATE_NOTIFICATION_ID`、`notifyClipUpdate(...)` 和详情页点击 Intent extra。
- 剪贴更新通知 channel 的用户可见语义调整为“剪贴更新通知/剪贴保存通知”。
- `POST_NOTIFICATIONS` 保留，因为剪贴保存提醒、下载进度、下载结果和 Shizuku 状态提醒仍需要通知系统。
- `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_DATA_SYNC` 保留，因为图片/视频下载 Worker 仍会使用 WorkManager 前台服务。
- `SYSTEM_ALERT_WINDOW` 删除，悬浮窗权限检查、设置入口和“我的/权限”悬浮窗项同步删除。
- `HandleNotificationPermission` 自动弹窗逻辑删除；通知权限只保留为用户在“我的/权限”里的手动入口。

## 并发、边界与备份

- 每次 AppOps 回调都使用独立 `eventId`，payload 和 icon 临时文件互不覆盖。
- 身份查询发生在读取剪贴快照之后、提交 payload 之前；身份不确定时跳过本次提交，避免旧进程误写。
- 剪贴内容失败不取消图标同步，图标失败不影响剪贴内容入库。
- 系统剪贴板在 Shizuku 读取之前被下一次复制覆盖时，中间值无法由 app 恢复。
- 本次不改 `source_apps` 表、不改 Room schema、不改 WebDAV/本地备份协议；同内容来源判定只改变后续入库时更新、插入或跳过的行为。
- 来源未知判定统一为来源包名为空，或来源名称为空、`Unknown`、`未知`、关联缺失；已有未知来源记录遇到后续明确来源时会覆盖升级，同内容已有明确来源且本次也明确但包名不同则新增一条，本次未知来源遇到已有明确来源则跳过。
- `files/clipboard_bridge_clip_payloads/` 与图标临时目录仍是短期临时数据；提交完成、失败或异常后清理，且继续按现有备份排除策略处理。
- `AppSetting.shizukuSuffix` 属于本机运行态，不纳入备份。

## R8 与稳定契约

- `AppOpsManagerHidden`、`OnOpNotedListener`、`ClipboardListener` 仍依赖隐藏 API 回调签名稳定，继续使用 `@Keep` 和现有隐藏 API 封装。
- `ClipboardShizukuService(Context)` 仍由 Shizuku 反射实例化，构造函数稳定边界不变。
- `ShizukuWakeActivity` 由 Shizuku shell 命令硬编码完整类名启动，继续使用 `@Keep` 保护完整类名。
- `ShizukuConnector.VERSION = 18` 用于强制覆盖安装后重建残留 UserService，避免旧 AIDL/Provider 协议进程继续工作。

## 日志与诊断计划

- Shizuku 侧记录 AppOps 回调、隐藏 API 注册/注销错误、`IClipboard` 读取摘要、Provider `content write/call` 的 exitCode、resultCode、clipStatus、iconStatus、reasonCode 和耗时。
- 剪贴链路只允许记录 `eventId`、`clipNull`、payload 是否为空、item 数量、MIME 类型、text/html 长度、是否含 URI/Intent、写入字节数、exitCode、resultCode、clipStatus、异常类型和耗时。
- 图标链路只记录 packageName、iconHash、图标尺寸、字节数、`iconDecisionReason`、缓存命中、是否上传、是否复用旧图标、timeout 和 resultCode。
- app 主进程探活链路记录 `appPingResult`、`appWakeRequested`、`appWakeMode`、`appWakeResult`、`callbackRebound`、`wakeCooldownSkipped`、`appWakeElapsedMs`、`readyForProviderQuery` 和 `reasonCode`。
- NoDisplay 唤醒页只记录 `entryReason`、`requested`、`expectedProcessName` 和 `reasonCode`。
- Provider 默认不支持 method 分支只记录 method 名、callingUid 和 resultCode；禁止记录 extras 中可能包含的正文、HTML、URI 或 Intent。
- 剪贴保存通知允许展示剪贴正文给用户本人，但日志仍禁止输出正文、HTML 原文、完整 URL 查询串、Token、Cookie、本地授权 URI、Intent 内容或完整用户输入。
- 剪贴入库去重日志只允许记录 `textLength`、`packageName`、`clipStatus`、`clipId`、是否命中重复和低敏 reasonCode；禁止输出剪贴正文、链接标题、完整 URL 或 HTML。
- 本次不新增额外日志点的原因：删除的是旧前台服务/悬浮窗读取链路，权限区本轮只是静态 UI 形态收敛；正式 `commit_clip`、NoDisplay 唤醒、身份查询和通知保存路径已有低敏诊断覆盖。

## 实现步骤

1. 删除 Manifest 中 `SYSTEM_ALERT_WINDOW` 与 `.service.ClipboardService`，保留通知权限、下载 Worker 前台服务权限和 WorkManager 前台服务声明。
2. 删除 `ClipboardService`、`ClipboardBridgeReadCoordinator`、Provider `METHOD_READ_CLIP` 分支、旧 result 字段和旧结果码。
3. 删除 AIDL `ShizukuCallback.onOpNoted(...)`，保留 `pingAppProcess()`；升级 `ShizukuConnector.VERSION`。
4. 将 `ShizukuAppWakeCommandRunner` 改为只启动 `ShizukuWakeActivity`，并收紧 parser/测试为只接受 Activity 输出。
5. 删除读取剪贴板前台服务通知 channel 和通知构建方法，保留剪贴保存通知。
6. 调整列表页和“我的/权限”权限行为，移除通知自动弹窗、悬浮窗项和权限说明展开动画，并让权限分组置顶。
7. 更新 Shizuku、Provider、权限、通知相关测试和文案。

## 测试验证

计划运行：

- `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.*"`
- `./gradlew :app:testDebugUnitTest --tests "com.cla.clip.master.provider.*ClipboardBridge*"`
- `./gradlew :base:general:compileDebugKotlin`
- `./gradlew :shizuku:compileDebugKotlin`
- `./gradlew :app:compileDebugKotlin`
- 评估并尽量运行 `./gradlew :app:minifyReleaseWithR8`
- `git diff --check`

人工回归建议：

- 拒绝悬浮窗权限后复制普通文本，确认仍可通过 Shizuku 直读与 `commit_clip` 入库。
- 剪贴保存成功后确认仍出现剪贴更新通知，并能点击进入详情。
- 重复内容或空内容确认不新增记录、不发送剪贴更新通知。
- 强杀 app 主进程后复制普通文本，确认 NoDisplay Activity 唤醒、callback 回流、`query_shizuku_process` 匹配后提交成功。
- 下载图片/视频时确认下载进度、下载结果和 DATA_SYNC 前台服务仍正常。
- Shizuku 状态提醒仍按通知权限展示；通知关闭时仅不展示提醒，不阻断入库。

## 已知取舍

- 不保留旧 `read_clip` / `onOpNoted(...)` 兼容逻辑，也不设计旧版本回退路径。
- Provider 模式不再 fallback 到 overlay 或 AIDL，原因是正式链路需要暴露 Shizuku 直读与 payload 提交的真实失败率。
- 第一版仍只处理第一个 `ClipData.Item`，多 item、URI、Intent、图片和文件搬运留到后续阶段。
- 第一版不保存原始 HTML，只在普通文本为空时用 HTML 生成纯文本 fallback。
- NoDisplay Activity 唤醒不主动追加 `--user`，后续如发现多用户或工作资料空间差异，再单独设计 userId 检测。
- Android force-stop 后系统通常不保证第三方组件可被后台唤醒，该场景记录为系统限制。
- 旧系统通知渠道已经创建后不能由代码可靠重命名或删除；本轮只停止创建读取剪贴板前台服务 channel。

## 开放问题

- 后续支持 URI、Intent、图片或文件时，需要重新设计权限、文件搬运、大小上限、MIME 白名单和日志脱敏边界。
- 后续支持多 item 时，需要定义排序、去重、部分失败提交和 UI 展示规则。
- 如果厂商 ROM 对 NoDisplay Activity 后台启动仍有限制，需要评估 BroadcastReceiver、用户显式入口或其他系统允许的唤醒方式。
- 如果后续调整 Shizuku 服务构造函数、唤醒 Activity 类名或隐藏 API 回调实现，需要同步复核 `@Keep` 和 release/R8 验证。

## 变更记录

- 2026-06-05：调整剪贴入库同内容来源判定规则；原因是同内容来自不同明确 App 时应保留为多条记录，但历史未知来源记录应被后续明确来源覆盖升级，且前台未知来源读取不能制造重复记录。
- 2026-06-05：我的页权限分组独立并置顶，通知权限手动入口仍保留在“我的/权限”；原因是权限状态需要优先展示，且通知权限只影响提醒展示。
- 2026-06-05：我的页权限入口改为固定展示 Shizuku 和通知两个权限项，移除“权限说明”标题行、展开箭头、展开/收起动画和 `permission_expanded` UI 偏好；原因是剪贴读取不再依赖旧悬浮窗/前台服务链路，通知权限也只作为提醒展示能力。
- 2026-06-05：彻底移除剪贴读取的前台服务与悬浮窗旧链路，删除 `read_clip` Provider method、`ClipboardService`、`ClipboardBridgeReadCoordinator`、`ShizukuCallback.onOpNoted(...)`、`SYSTEM_ALERT_WINDOW`、读取剪贴板前台服务通知和通知自动弹窗；原因是正式链路已经是 Shizuku 进程直读 + `commit_clip` 入库，不再需要旧兼容入口。
- 2026-06-05：保留并明确剪贴保存后的通知语义，只有 `ClipProcessResult.Saved` 后发送剪贴更新通知，`duplicate_or_empty` 不发送；原因是用户仍需要保存成功提醒，但不能让重复或空内容产生误导通知。
- 2026-06-05：将 app 唤醒收敛为 NoDisplay `ShizukuWakeActivity`，删除前台服务唤醒 parser 分支，并将 `ShizukuConnector.VERSION` 升到 18；原因是 `ClipboardService` 已删除，覆盖安装后的旧 UserService 必须被强制重建。
- 2026-06-04：删除 `ClipboardShizukuService` 中对主包名 `SYSTEM_ALERT_WINDOW` AppOps 的自动授权补偿，并将 `ShizukuConnector.VERSION` 升到 17；原因是 Provider 直读链路不再依赖主进程悬浮窗权限。
- 2026-06-04：清理 `ClipboardShizukuService` 中运行时不可达的旧 AIDL 保存分支和关闭的 overlay 调试回退，并拆出 Provider 命令客户端、Shizuku shell 命令执行器、图标同步协调器、app 唤醒命令执行器和来源应用解析器；原因是 Provider 直读链路已成为正式路径。
- 2026-05-30：新增并收敛 `ShizukuWakeActivity` 为 `Theme.NoDisplay` 即退入口；原因是唤醒页只需要拉起主进程并提交长生命周期连接请求。
- 2026-05-29：将 Shizuku 进程 `IClipboard` 直读从探针升级为正式 payload 主链路；原因是实测 Shizuku 进程可读到剪贴板，而普通 app overlay 即使取得窗口焦点仍可能读到空。
- 2026-05-22：新增 Shizuku 剪贴板服务方案文档，并记录 AppOps 隐藏 API consumer rule 收敛为 `@Keep` 注解保留。
