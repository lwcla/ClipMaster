状态：已完成

# Shizuku 剪贴板服务方案

## 当前状态

Shizuku 模块通过 `ClipboardShizukuService` 在 Shizuku 进程中注册 AppOps 隐藏 API 监听器，用于感知其他应用写入剪贴板后回调主进程。隐藏 API 壳类型、HiddenApiBypass 调用封装和系统剪贴板隐藏 API 读取器位于 `base/hidden-api`，其中 `AppOpsManagerHidden` 和 `AppOpsManagerHidden.OnOpNotedListener` 需要在 release/R8 构建中保持类名、方法签名和接口签名稳定，`HiddenApiExemptions` 统一处理 Android P/API 28 版本门控和第三方 `HiddenApiBypass` 调用，`SystemClipboardHiddenReader` 统一处理 `ServiceManager`、`IClipboard.Stub` 与 `IClipboard#getPrimaryClip` 的签名差异；实际监听实现 `ClipboardListener` 使用具名类并通过 `@Keep` 保留，避免 R8 将隐藏 API 回调改写成不兼容形态。

当前 Provider 通道已经从“主进程 overlay 读取剪贴板”升级为“Shizuku 进程直读剪贴板 + app Provider 提交入库”的正式链路：

- Shizuku 回调入口立即生成独立 `eventId`，记录 `capturedAtMillis`，并用 `ShizukuClipboardReader` 以 `com.android.shell` 身份委托 `SystemClipboardHiddenReader` 通过 `IClipboard.getPrimaryClip` 读取当前 `ClipData` 快照。
- 为处理部分系统无法通过 `content call` 冷启动 Provider 的差异，Shizuku 在读取剪贴板之后、提交 payload 和图标之前，会先通过无副作用 AIDL callback `pingAppProcess()` 探测 app 主进程；callback 不可达时先用 `am start-foreground-service` 拉起 `ClipboardService`，如果命令失败、超时或被 parser 判定为失败，再用 NoDisplay `ShizukuWakeActivity` 拉起主进程并等待 app 重新 `setCallback`，再调用 Provider `query_shizuku_process` 获取 app 当前期望的完整 Shizuku 进程名。
- 为处理覆盖安装后部分设备旧 Shizuku 进程不自动退出、同时新建 Shizuku 进程的问题，Shizuku 仍只把 Provider 返回的完整进程名作为身份凭据；只有当前进程名和期望进程名都非空且明确不一致时，旧进程才调用 `destroy()` 自杀，callback 可达或前台服务唤醒成功都不能替代身份校验。
- 剪贴内容链路把 v1 JSON payload 通过 `content write content://<authority>/clip/<eventId>` 写入 app 私有临时目录，再调用 Provider `commit_clip` 解析和入库。
- 图标链路继续沿用既有 `query_icon_state`、`content write /icon/<eventId>`、`commit_icon` 方案，不改变 hash、decode、保存、来源缓存或去重规则。
- 剪贴内容链路和图标链路使用同一个 `eventId` 串联日志，但分别走不同 Provider path、不同临时目录和不同协程，任一链路失败都不取消另一条链路。
- Shizuku 服务端正式链路不再写入 `SYSTEM_ALERT_WINDOW` AppOps，也不依赖主进程悬浮窗权限；悬浮窗读取只保留在旧 `read_clip` 兼容入口内，等待后续单独退役。
- 旧 `read_clip` overlay Provider 读取和 `ShizukuCallback.onOpNoted()` 只作为兼容入口保留，正式 Shizuku 服务端不再调用，避免 Provider payload 与旧 callback 双写。

## 目标

- 把已验证可行的 Shizuku 进程 `IClipboard` 直读能力升级为正式剪贴板读取链路，降低 MIUI 上普通 app overlay 读剪贴板返回空的风险。
- 保证已被 Shizuku 成功读取到的不同 payload 在 app 侧传输、提交、清理过程中不互相覆盖、不误删。
- 保持现有内容去重语义：上一条内容相同仍跳过保存，不因为 `eventId` 独立而改变业务去重。
- 让 `capturedAtMillis` 跟随读取快照进入入库时间，避免并发 `commit_clip` 完成顺序改变剪贴列表顺序。
- 继续让图标同步脱离剪贴内容关键路径，图标失败不影响文本入库，文本失败也不影响图标缓存预热。
- 使用完整 Shizuku 进程名作为唯一身份凭据，避免拆解版本号和安装 pid 后产生分支判断；身份不确定时宁可丢弃本次提交，也不误杀最新进程。
- 在 Provider 身份查询前先用 callback 探活、前台服务和 NoDisplay WakeActivity fallback 补偿唤醒 app 主进程，再刷新 app 侧期望完整进程名并 best-effort 异步请求 `ShizukuConnector.connect()`，让旧进程或 app 冷启动场景都能拉起新进程修复链路。

## 范围

- `base/general/src/main/java/com/cla/clip/base/general/config/AppSetting.kt`
- `base/general/src/main/java/com/cla/clip/base/general/config/MmkvInitializer.kt`
- `base/general/src/main/java/com/cla/clip/base/general/config/NumericInstallIdGenerator.kt`
- `base/hidden-api/src/main/java/com/cla/clip/base/hidden/api/HiddenApiExemptions.kt`
- `base/hidden-api/src/main/java/com/cla/clip/base/hidden/api/SystemClipboardHiddenReader.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardShizukuService.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ShizukuAppProcessReadiness.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ShizukuClipboardReader.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardBridgeClipPayload.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardBridgeContract.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ShizukuProcessName.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ShizukuProcessIdentity.kt`
- `shizuku/src/main/aidl/com/cla/clip/shizuku/ShizukuCallback.aidl`
- `app/src/main/java/com/cla/clip/master/wake/ShizukuWakeActivity.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeProvider.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeClipPayloadStore.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeClipCommitCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeReadCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeShizukuProcessCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ShizukuConnectRequester.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconQueryCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconCommitter.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconSyncDecider.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconStore.kt`
- `app/src/main/java/com/cla/clip/master/utils/ShizukuConnector.kt`
- `app/src/main/java/com/cla/clip/master/utils/ClipHelper.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepositoryImpl.kt`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `docs/webdav_backup_plan.md`

不包含：

- 新增数据库字段或 Room schema 迁移。
- 保存原始 HTML、URI、Intent、图片或多 item 剪贴板内容。
- 改动来源图标 hash、decode、保存、缓存命中、坏文件清理或来源筛选语义。
- 退役旧 AIDL/`read_clip` 跨进程协议；本轮只停止 Shizuku 服务端调用，Provider method 与 AIDL 方法仍保留兼容。

## 用户体验

开启 Shizuku 能力后，用户复制普通文本或富文本时，Shizuku 服务会在 AppOps 回调后直接读取当前剪贴板，并把文本 payload 交给 app 入库。若 payload 是普通文本，直接保存文本；若普通文本为空但存在 HTML，则 app 侧把 HTML 转为纯文本后保存；若仍为空白，沿用“空内容或重复内容不保存”的表现。URI、Intent、图片和文件类剪贴板第一版明确返回不支持，不会尝试搬运或记录敏感 URI/Intent 内容。

来源图标仍可能稍晚补齐：文本入库和图标同步并行执行，剪贴记录可以先保存并显示已有缓存图标或占位图，随后由 `source_apps` Room 观察刷新真实图标。

## 数据流

1. 主进程连接 Shizuku 并实例化 `ClipboardShizukuService(Context)`。
2. `ClipboardShizukuService.start()` 通过 `HiddenApiExemptions.addIfNeeded("Landroid/app")` 添加 Android P/API 28 及以上的 `android.app` 豁免，并通过 `Refine.unsafeCast<AppOpsManagerHidden>` 注册 `ClipboardListener` 到 `startWatchingNoted(intArrayOf(30), listener)`；Android 8.1/API 27 及以下没有隐藏 API 限制，由 `base:hidden-api` 封装统一跳过 `HiddenApiBypass.addHiddenApiExemptions` 以兼容 minSdk 24。
3. `ClipboardListener` 收到字符串 op 或数字 code 回调后过滤自身包名，再调用 `owner.handleOpNoted(clipPackageName)`。
4. `handleOpNoted()` 每次回调立即生成独立 `eventId`，记录 `capturedAtMillis`，并在 Shizuku 进程内用 `ShizukuClipboardReader.readPrimaryClip()` 读取 `ClipData` 快照；该读取必须发生在身份查询之前，避免唤醒 app 和 bind 请求拖慢剪贴板快照捕获。
5. `ShizukuClipboardReader` 固定以 `com.android.shell` 作为 `IClipboard.getPrimaryClip` 的 calling package，并委托 `base:hidden-api` 的 `SystemClipboardHiddenReader` 读取系统剪贴板；`SystemClipboardHiddenReader` 通过 `HiddenApiExemptions.addIfNeeded(...)` 在 Android P/API 28 及以上先豁免 `ServiceManager` 和 `IClipboard` 隐藏 API，Android 8.1/API 27 及以下跳过豁免但保留同一反射读取流程。AppOps 回调里的来源包名只作为来源 App package，主包名只作为 host package，三者禁止混用。
6. Shizuku 只读取第一个 `ClipData.Item`，提取 `text`、`htmlText`、MIME、URI/Intent 是否存在等低敏摘要；日志只记录长度和布尔值，不输出正文。
7. `ShizukuAppProcessReadiness` 使用当前 `ShizukuCallback` 的 `pingAppProcess()` 做无副作用探活；callback 为空、ping 超时、旧 transaction 不支持或 Binder 异常时，compare-and-clear 旧 callback，并先通过 `am start-foreground-service -n <package>/com.cla.clip.master.service.ClipboardService` 唤醒 app 主进程，避免部分 ROM 对相对 service 类名解析不一致。
8. 前台服务唤醒命令最多等待 2000ms，输出由 `ClipboardBridgeCommandResultParser.isStartForegroundServiceSuccessful(...)` 集中判断；如果命令未被系统接受，Shizuku 改用 `am start --activity-no-animation -n <package>/com.cla.clip.master.wake.ShizukuWakeActivity` 启动 NoDisplay 唤醒页。`ShizukuWakeActivity` 只触发 `ShizukuConnector.requestConnect("wake_activity")` 并立即 `finish()`，不读取或提交剪贴内容，不等待 callback。
9. app 唤醒命令不带 `--user`，多用户策略留到后续单独设计；前台服务和 NoDisplay Activity 输出由 `ClipboardBridgeCommandResultParser.isAppWakeCommandSuccessful(...)` 统一判断。唤醒后最多等待 2500ms 等待 app 重新 `setCallback`，并再次 ping 确认不是旧坏 callback。
10. `ShizukuAppProcessReadiness` 使用进程内 `Mutex` 共享同一轮 `wake + wait callback`，并发剪贴事件必须先各自读取自己的剪贴板快照，再共享唤醒结果；唤醒失败后 3000ms cooldown 内不重复拉起前台服务或 NoDisplay Activity。
11. `ShizukuProcessIdentity` 读取 `/proc/self/cmdline` 获取当前完整进程名，并调用 Provider `query_shizuku_process`；Provider 合法 shell/root 调用后刷新 `AppSetting.shizukuSuffix` 为最新完整进程名，best-effort 异步触发 `ShizukuConnector.connect()`，并立即返回 `shizukuProcessName`、`connectRequested`、`connectSkipReason` 和 `reasonCode`。
12. 如果 ping 成功后 app 又被杀，导致 `query_shizuku_process` 输出 `Could not find provider` 或 `Error while accessing provider:<authority>`，且本事件还没有执行过唤醒，则 Shizuku 补做一次同样的唤醒流程后重试身份查询；如果本事件已经唤醒过，则不再二次拉起。
13. 身份规则只比较完整字符串：当前进程名和期望进程名都非空且完全一致时继续提交；都非空但不一致时确认旧进程，调用现有 `destroy()` 先注销监听和 callback 再杀进程；Provider 失败、resultCode 非 ok、当前进程名为空或期望进程名为空时只跳过本次提交，不自杀。
14. `ClipboardShizukuService` 只有在身份匹配后才通过 `ShizukuSourceAppResolver` 解析来源应用名、图标 bitmap 和 `Bitmap.toStableHash()`；来源包名失效、卸载竞态或 `PackageManager` 异常时回退为 `Unknown`/空图标，不阻断剪贴 payload 提交。
15. `submitProviderBridgeEvent()` 通过 `supervisorScope` 并行启动剪贴 payload 和图标链路；身份不匹配或不确定时不写入 payload，也不进入图标补全链路。
16. `ClipboardBridgeProviderCommandClient` 把 payload 序列化为 UTF-8 JSON，通过进程 stdin 写给 `content write --uri content://<authority>/clip/<eventId>`；只有 exitCode 为 0 后，才调用 `content call --method commit_clip`。
17. `commit_clip` 在 app 侧读取 `files/clipboard_bridge_clip_payloads/<eventId>.tmp`，校验版本、eventId、时间戳和 JSON 结构，提取文本或 HTML fallback 后委托 `ClipHelper.processClipText(...)` 入库。
18. `ClipHelper.processClipText(...)` 复用现有链接解析、通知、备份 dirty 标记和“上一条内容相同则跳过保存”规则；保存时优先使用 payload 的 `capturedAtMillis` 作为剪贴记录时间。
19. `ClipboardBridgeIconSyncCoordinator` 独立调用 `query_icon_state`，必要时继续执行 `content write --uri content://<authority>/icon/<eventId>` 和 `commit_icon`；图标逻辑完全沿用既有缓存命中与坏文件清理规则。
20. `commit_clip` 提交成功、失败或异常都会清理自己的 eventId 文件；过期清理只删除超时 `.tmp` 文件，不清空整个目录。

## Payload 协议

`ClipboardBridgeContract.CLIP_PAYLOAD_VERSION = 1`，Shizuku 侧通过 `content write content://<authority>/clip/<eventId>` 写入 UTF-8 JSON：

```json
{
  "version": 1,
  "eventId": "event-id",
  "capturedAtMillis": 1710000000000,
  "mimeTypes": ["text/plain", "text/html"],
  "text": "plain text",
  "htmlText": "<b>plain text</b>"
}
```

协议约束：

- `eventId` 必须匹配 Provider path 中的 eventId。
- `capturedAtMillis` 必须大于 0，app 入库时优先作为剪贴记录时间。
- `text` 优先于 `htmlText`；`text` 为空白且 `htmlText` 非空时，app 侧用 HTML 转纯文本作为 fallback。
- 第一版不额外设置低于系统剪贴板能力的固定大小上限；失败由 `content write`、文件系统、JSON 解析和后续入库错误自然返回。
- Provider result bundle 只返回 `clipCommitted`、`clipStatus`、`resultCode`、`textLength`、`htmlLength`、`mimeTypes` 等脱敏字段，禁止返回正文、HTML 原文、URI 或 Intent 内容。

## Provider Methods 与返回契约

### `commit_clip`

- 输入：`eventId`、`packageName`、`appName`、`iconHash`
- 前置：`content write content://<authority>/clip/<eventId>` 已经成功写入 payload
- 作用：消费自己的 payload 临时文件，解析文本或 HTML fallback，并调用文本入库入口
- 返回：
  - `resultCode`
  - `clipCommitted`
  - `clipStatus`
  - `textLength`
  - `htmlLength`
  - `mimeTypes`
  - `iconStatus`

### `read_clip`

- 输入：`eventId`、`packageName`、`appName`、`iconHash`
- 作用：旧 overlay Provider 读取兼容入口，Shizuku 服务端正式链路不再调用
- 返回：
  - `resultCode`
  - `saved`
  - `readClip`
  - `overlayAdded`
  - `iconStatus`

### `query_icon_state`

- 输入：`eventId`、`packageName`、`appName`、`iconHash`
- 作用：独立判断当前来源图标是否需要继续同步
- 返回：
  - `resultCode`
  - `shouldSyncIcon`
  - `iconDecisionReason`
  - `iconStatus`

### `commit_icon`

- 输入：`eventId`、`packageName`、`appName`、`iconHash`
- 作用：消费 `content write` 落盘的 PNG，正式保存图标文件并更新 `source_apps`
- 返回：
  - `resultCode`
  - `iconStatus`

### `query_shizuku_process`

- 输入：`eventId`
- 作用：在 Shizuku 已通过 callback 探活和必要 app 唤醒后，刷新 app 侧当前期望的完整 Shizuku 进程名，best-effort 异步触发最新 Shizuku bind，并把身份字段返回给 Shizuku 进程自检。
- 约束：
  - 仅允许 shell/root 调用，非法调用返回 `invalid_caller`。
  - Provider `onCreate()` 必须先通过 `MmkvInitializer.ensureInitialized()` 确认 MMKV 默认实例可用，再允许后续首次调用懒加载 `AppSetting`、Hilt EntryPoint 和业务协调器；原因是 ContentProvider 冷启动早于 `Application.onCreate()`，不能依赖 Application 先完成 `MMKV.initialize()`。
  - Provider 入口通过 lazy EntryPoint 获取 `ClipboardBridgeShizukuProcessCoordinator` 和 `ShizukuConnectRequester`，避免冷启动早期初始化完整剪贴链路。
  - 不等待 `bindUserService` 完成；连接请求由 `ShizukuConnector` 的 `connectMutex`、`boundProcessName` 和 `bindingProcessName` 控制频率。
  - `ShizukuConnector.connect()` 必须在 `delayTime`、`ShizukuUtils.isConnected()`、`isAlive`、early return 和 `bindUserService` 之前调用 `refreshExpectedShizukuProcessName()`，并且只有“当前 binder 活着且已绑定进程名等于最新 expectedProcessName”时才跳过 bind。
- 返回：
  - `resultCode`
  - `shizukuProcessName`
  - `connectRequested`
  - `connectSkipReason`
  - `reasonCode`

## Provider 失败码、剪贴状态与图标决策原因

### 结果码

- `ok`：当前阶段成功完成。
- `invalid_caller`：调用方不是 shell/root，Provider 拒绝处理。
- `invalid_args`：method 或 `eventId` 等参数非法。
- `no_clip`：Shizuku 读取到空剪贴板，或 payload 没有可提交内容。
- `unsupported_clip_type`：当前剪贴板是 URI、Intent、图片、文件或其他第一版不支持类型。
- `payload_missing`：`commit_clip` 未找到自己的 eventId payload 文件。
- `invalid_payload`：payload JSON 结构、版本、eventId 或时间戳非法。
- `commit_failed`：解析、HTML fallback、入库或临时文件处理出现非预期异常。
- `icon_missing`：`commit_icon` 阶段图标缺失、半文件、decode 失败或 hash 校验失败。
- `overlay_failed`：旧 `read_clip` 兼容路径添加悬浮窗失败。
- `read_failed`：旧 `read_clip` 兼容路径读取或入库过程出现异常。
- `timeout`：Provider 等待当前阶段完成超时。
- `shizuku_process_missing`：app 侧无法提供非空期望 Shizuku 完整进程名，Shizuku 侧必须按身份不确定处理。

### `clipStatus`

- `saved`：本次剪贴文本已保存。
- `duplicate_or_empty`：文本为空白，或沿用既有规则判断为上一条重复内容。
- `no_clip`：没有可读取的剪贴板快照。
- `unsupported_clip_type`：第一版不支持当前剪贴类型。
- `payload_missing`：提交阶段缺少 payload 文件。
- `invalid_payload`：payload 版本、字段或 JSON 格式非法。
- `commit_failed`：提交链路异常失败。

### `iconDecisionReason`

- `cache_hit`：数据库图标 hash 命中且文件仍存在。
- `stale_file_missing`：数据库图标 hash 命中但本地图标文件缺失。
- `no_cached_icon`：数据库没有任何图标缓存。
- `hash_changed`：数据库图标 hash 与当前请求 hash 不一致。
- `no_icon_available`：当前事件没有可用于同步的图标参数。

### `reasonCode`

- `identity_query`：Provider 正在响应 Shizuku 进程身份查询。
- `missing_expected_process_name`：Provider 无法返回可信期望完整进程名。
- `connect_request_failed`：Provider 提交 best-effort 连接请求失败。
- `missing_current_process_name`：Shizuku 进程无法读取自身完整进程名。
- `provider_query_failed`：Shizuku 侧 Provider 查询命令失败、超时或输出不可信。
- `process_matched`：当前完整进程名与 app 侧期望完整进程名一致。
- `process_mismatched`：当前完整进程名与 app 侧期望完整进程名明确不一致。
- `callback_missing` / `ping_timeout` / `wake_succeeded` / `wake_command_timeout` / `wake_command_failed` / `callback_wait_timeout` / `wake_activity_started_callback_timeout` / `wake_cooldown_skipped`：Shizuku 侧 app 主进程探活、前台服务唤醒和 NoDisplay Activity fallback 链路的低敏诊断原因码。

## 并发与边界

- 每次 AppOps 回调都生成独立 `eventId`，payload 文件名使用 `<eventId>.tmp`，避免连续回调互相覆盖。
- 身份查询发生在剪贴板快照读取之后、payload 和图标链路启动之前；旧进程可能已经读到剪贴板，但一旦完整进程名不匹配会丢弃本次 payload，由最新 Shizuku 进程负责下一次或后续回调提交，优先避免重复入库。
- app 主进程探活、前台服务唤醒和 NoDisplay Activity fallback 同样发生在剪贴板快照读取之后；等待 callback 重连不会导致本次 payload 被后续复制覆盖。
- 身份不确定时只跳过本次提交，不调用 `destroy()`；原因是 Provider 冷启动失败、输出缺字段或本地进程名读取失败都不足以证明当前进程就是旧进程。
- Provider 模式下唤醒 `ClipboardService` 只用于拉起 app 主进程，不传真实剪贴数据，不调用旧 AIDL `onOpNoted` 保存同一条剪贴内容，避免 Provider payload 与旧 callback 双写。
- app 唤醒失败后短时间内通过 cooldown 跳过重复拉起；并发事件通过 `Mutex` 共享同一轮唤醒结果，但每个事件都保留自己已经读取到的剪贴板快照。
- `clipPayloadJob` 不等待上一条 Provider 提交、图标预判或图标上传；只要 Shizuku 已读取到 payload，就尽快写入 `/clip/<eventId>`。
- `iconJob` 不等待剪贴内容提交结果，继续沿用已有图标同步流程。
- `supervisorScope` 隔离失败，剪贴内容失败不取消图标同步，图标失败也不影响剪贴内容入库。
- eventId 独立文件只能保护 app 侧传输和提交阶段；如果系统剪贴板在 Shizuku 读取前已经被下一次复制覆盖，中间值无法由 app 恢复。
- `query_shizuku_process` 会触发 `ShizukuConnector.connect()`，但连接器内部复用同一把 `connectMutex` 并记录 `reasonCode`、`boundProcessName`、`bindingProcessName` 和 `expectedProcessName`；后续人工回归需要重点观察是否因为旧进程回调造成高频 bind。
- `query_icon_state`、`commit_clip`、`commit_icon` 和 `query_shizuku_process` 均通过 `ShizukuShellCommandRunner` 执行并设置超时；`query_icon_state` 超时只让图标链路失败，不影响剪贴 payload。

## `source_apps` 与备份语义

- `source_apps` 继续承载两类职责：
  - 已保存剪贴记录的来源展示缓存；
  - 由图标链路独立预热的来源图标缓存。
- 因此 `source_apps` 允许先于真实剪贴记录出现，搜索页来源筛选继续直接读取这些数据，这是显式产品预期。
- 本次不新增字段、不改 Room schema、不改 JSONL/zip 备份协议结构。
- `files/clipboard_bridge_clip_payloads/` 只保存短期敏感 payload 临时文件，提交成功、失败和异常都清理自己的 eventId；系统 Auto Backup 和设备迁移规则必须排除该目录。
- `AppSetting.pid` 改为安装级固定长度纯数字字符串，只用于本机安装身份、Shizuku 进程名、Shizuku tag 和备份文件 device label 前缀；它不是用户数据，不纳入 WebDAV 备份，卸载重装后变化是预期行为。
- `AppSetting.shizukuSuffix` 现在保存当前期望完整进程名 `<applicationId>:shizuku_<VERSION>_<pid>`，只属于本机运行态，不纳入 WebDAV 备份、Room schema 或 Auto Backup 协议。
- `MmkvInitializer` 只负责当前进程内 MMKV native 初始化，不新增 MMKV key、不改变备份白名单，也不改变 `pid` 和 `shizukuSuffix` 的备份排除决策。

## R8 与隐藏 API 契约

- `AppOpsManagerHidden` 和 `OnOpNotedListener` 属于隐藏 API 编译壳，稳定边界是类名、接口名、成员方法签名和默认方法签名；通过 AndroidX `@Keep` 交给 annotation 自带 consumer rule 保留。
- `ClipboardListener` 属于隐藏 API 回调实现，稳定边界是实现类本身、接口关系和两个 `onOpNoted` 方法签名；当前类已使用 `@Keep`，因此不再需要宽泛 consumer rule。
- `ClipboardShizukuService(Context)` 仍由 Shizuku 反射实例化，稳定边界是服务类名和 `Context` 构造函数；当前构造函数已使用 `@Keep`。
- `ShizukuWakeActivity` 由 Shizuku 进程硬编码 component class name 通过 shell `am start` 调用，稳定边界是 Activity 完整类名；当前类已使用 `@Keep`，避免 release 混淆后 fallback component 失效。

## 日志与诊断计划

- Shizuku 侧继续记录 AppOps 回调、隐藏 API 注册/注销错误、`IClipboard` 读取摘要、Provider `content call/write` 的 exitCode 和结果码。
- app 侧 MMKV 初始化只记录稳定 `reason` 和 MMKV 根目录，用于定位 Provider 冷启动是否早于 Application；不记录剪贴内容、设置值、pid、WebDAV 配置或任何用户输入。
- 剪贴内容链路和图标链路都带同一个 `eventId`，用于串联同一次来源事件。
- 剪贴内容链路只允许记录 `clipNull`、payload 是否为空、item 数量、MIME 类型、text/html 长度、是否含 URI/Intent、`content write` 字节数、exitCode、resultCode、clipStatus、耗时和异常类型。
- 图标链路继续记录 `iconSyncKey=packageName#iconHash`、`query_icon_state`、`content write`、`commit_icon`、`iconDecisionReason`、是否命中缓存、是否实际上传、是否复用旧图标；`query_icon_state` 超时或进程销毁只记录 eventId、packageName、exitCode、timeout 和 reasonCode，不记录命令参数中的敏感内容。
- app 主进程探活链路只记录 `eventId`、`appPingResult`、`appWakeRequested`、`appWakeMode`、`appWakeResult`、`callbackRebound`、`wakeCooldownSkipped`、`appWakeElapsedMs`、`readyForProviderQuery` 和 `reasonCode`；NoDisplay 唤醒页只记录 `entryReason`、`requested`、`expectedProcessName` 和 `reasonCode`；禁止记录 callback 对象地址、剪贴内容或唤醒命令以外的用户上下文。
- 身份查询链路只记录 `eventId`、`currentProcessName`、`expectedProcessName`、`matched`、`resultCode`、`reasonCode`、`connectRequested`、`connectSkipReason`、`boundProcessName`、`bindingProcessName`、`wakeFailedProviderFallback`、`providerIdentityAfterWake` 和 `providerQueryElapsedMs`，用于判断旧进程是否退出、Provider 是否频繁触发 bind 以及已知设备是否命中唤醒补偿。
- app 侧 payload store 只记录 eventId、文件大小、解析结果、过期清理数量和异常类型；禁止输出 payload 正文、HTML 原文、URI 或 Intent 内容。
- HTML fallback 只记录 HTML 长度和转换后文本长度；转换结果禁止输出。
- 日志禁止输出剪贴板正文、HTML 原文、完整用户输入、Token、Cookie、完整 URL 查询串、本地授权 URI、URI 原文或 Intent 内容；允许保留包名、应用名、图标尺寸、图标字节数、hash、eventId、iconSyncKey、进程名、reasonCode 和耗时等低敏诊断信息。

## 测试验证

- 编译验证：
  - `./gradlew :base:general:compileDebugKotlin`
  - `./gradlew :shizuku:compileDebugKotlin`
  - `./gradlew :app:compileDebugKotlin`
- 单元测试：
  - `./gradlew :base:general:testDebugUnitTest`
  - `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.*"`
	  - `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.*ClipboardBridge*"`
	  - `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.ShizukuShellCommandRunnerTest"`
	  - `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.ClipboardBridgeProviderCommandClientTest"`
	  - `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.ShizukuSourceAppResolverTest"`
	  - `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.ShizukuAppProcessReadinessTest"`
  - `./gradlew :app:testDebugUnitTest --tests "com.cla.clip.master.provider.*ClipboardBridge*"`
- 静态检查：
  - `git diff --check`
- 人工回归建议：
  - 覆盖安装后保留旧 Shizuku 进程，复制普通文本，确认 Provider 被旧进程唤醒后触发最新 bind，旧进程完整进程名不匹配并退出；
  - 手动验证 NoDisplay Activity 入口：`adb shell am start --user 0 --activity-no-animation -n com.cla.clip.master/com.cla.clip.master.wake.ShizukuWakeActivity`，确认命令不返回 `Error`，日志出现 `ShizukuWakeActivity`、`reasonCode=wake_activity` 和 `appWakeMode=ACTIVITY_NO_DISPLAY`；
  - 额外记录 force-stop 场景：明确区分“进程被杀”和“应用被强行停止”，如果 force-stop 后显式 Activity 或 Provider 受系统限制不可用，应记录为系统限制或非目标支持场景；
  - 强杀主进程后不手动打开 App，直接复制普通文本，确认先出现 `appPingResult=callback_missing` 或 `ping_timeout`；若前台服务返回 `Not found`，随后应出现 `wake-activity` 命令、callback 回流、Provider 身份 matched 并提交成功；
  - 三星 S10 回归时备注 app 是否处于深度休眠、受限电池、后台限制或类似系统管控状态，避免把系统策略差异误判为代码回归；
  - 观察 `query_shizuku_process`、`reasonCode`、`connectRequested`、`connectSkipReason`、`boundProcessName`、`bindingProcessName` 和 `expectedProcessName` 日志，确认不会高频重复 bind；
  - 主进程存活和被杀后分别复制普通文本、富文本、长文本、连续不同文本和连续相同文本；
  - 复制 URI、Intent、图片和文件类剪贴板，确认返回不支持或无内容，不产生误入库；
  - 模拟 `content write /clip/<eventId>` 失败，确认不会调用 `commit_clip`；
	  - 模拟图标失败，确认文本仍可入库；
	  - 确认 Shizuku 服务端不再调用旧 `read_clip` 和旧 AIDL `onOpNoted()` 保存路径。

## 已知取舍

- 第一版只处理第一个 `ClipData.Item`，多 item、URI、Intent、图片和文件搬运作为后续阶段。
- 第一版不保存原始 HTML，只在普通文本为空时用 HTML 生成纯文本 fallback。
- Provider 模式下默认不自动 fallback 到 overlay 或 AIDL，原因是正式链路需要暴露 Shizuku 直读与 payload 提交的真实失败率。
- Shizuku 服务端不再自动授予主包名悬浮窗 AppOps，原因是正式链路已经改为 Shizuku 进程直读剪贴板；app 侧悬浮窗权限、旧 `read_clip` Provider method 和相关 UI 提示暂不在本轮删除，避免把兼容入口退役混入主链路清理。
- `read_clip` Provider method、`ShizukuCallback.onOpNoted()` 和 app 侧旧 callback 实现暂不删除；原因是它们属于跨进程兼容入口，彻底退役需要单独评估 ABI 和旧版本进程交互。
- Provider 身份查询只做 best-effort 异步修复，不等待新 Shizuku bind 完成；原因是旧进程自检不能阻塞剪贴板回调，且最终提交仍以完整进程名比较结果为准。
- app 唤醒只作为 app 主进程不可达时的补偿路径，当前优先复用 `ClipboardService`，并在前台服务失败后使用 NoDisplay `ShizukuWakeActivity` fallback；是否拆出专用 `ShizukuWakeService` 留到链路稳定后单独评估。
- 初版 `am start-foreground-service` 不带 `--user`；如果后续发现多用户或工作资料空间问题，再单独设计 current user 探测和 fallback 策略。
- 当前处于开发阶段，旧 UUID 格式 `AppSetting.pid` 会被直接重建为固定长度纯数字字符串，不做线上兼容迁移。
- 图标同步状态只保存在 Shizuku 进程内存，不落数据库；进程死亡后允许下次事件自然重试。
- `eventId` 只用于日志串联和临时 payload/icon 文件，不参与来源缓存身份判断；数据库来源更新仍以 `packageName` 和 `Bitmap.toStableHash()` 为核心。

## 开放问题

- 如果后续支持 URI、Intent、图片或文件，需要重新设计权限、文件搬运、大小上限、MIME 白名单和日志脱敏边界。
- 如果后续支持多 item，需要定义多 item 的排序、去重、失败部分提交和 UI 展示规则。
- 如果前台服务预算、通知闪烁、NoDisplay Activity 生命周期、component 解析差异或 no-payload 生命周期继续变复杂，应评估拆出专用 `ShizukuWakeService`，或补充 `BroadcastReceiver + goAsync()` / 透明短停留 Activity 作为后续兜底方案。
- 如果多用户、工作资料空间或厂商 ROM 对不带 `--user` 的 `am start-foreground-service` 行为不一致，需要单独补充 userId 检测、显式 user 调用和无 user fallback 策略。
- 如果后续接入更多 AppOps 隐藏 API 回调实现，需要统一检查新增实现类是否已显式 `@Keep`。
- 如果后续调整 Shizuku 服务构造函数签名或新增其他反射实例化入口，需要同步补充 `@Keep` 或精确 consumer rule。
- 后续可单独评估旧 AIDL `onOpNoted()` 和 Provider `read_clip` 的正式退役时机；评估前不得在 Shizuku 服务端重新启用双写路径。

## 变更记录

- 2026-06-04：删除 `ClipboardShizukuService` 中对主包名 `SYSTEM_ALERT_WINDOW` AppOps 的自动授权补偿，并将 `ShizukuConnector.VERSION` 升到 17 以触发新版 Shizuku 进程重建；原因是正式 Provider 直读链路不再依赖主进程悬浮窗权限，旧 `read_clip` overlay 读取仅作为兼容入口保留，后续退役时再统一清理 app 权限项和旧 Provider method。
- 2026-06-04：清理 `ClipboardShizukuService` 中运行时不可达的旧 AIDL 保存分支和关闭的 overlay 调试回退，并拆出 Provider 命令客户端、Shizuku shell 命令执行器、图标同步协调器、app 唤醒命令执行器和来源应用解析器；原因是 Provider 直读链路已成为正式路径，入口服务类需要回到 AppOps/Binder 生命周期和主流程编排职责，同时为 `query_icon_state` 补齐超时保护。
- 2026-05-30：将 `ShizukuWakeActivity` 收敛为 `Theme.NoDisplay` 即退入口，并新增 `appWakeMode` 与 `wake_activity_started_callback_timeout` 诊断；原因是唤醒页只需要拉起主进程并提交长生命周期 `ShizukuConnector.requestConnect("wake_activity")`，不应获取焦点或保留短暂透明窗口。
- 2026-05-30：新增 `ShizukuWakeActivity` 作为 app 主进程冷启动 fallback，并让 Shizuku 在前台服务唤醒失败后执行 `am start --activity-no-animation`；原因是三星 S10 上 `adb shell am start-foreground-service -n com.cla.clip.master/com.cla.clip.master.service.ClipboardService` 仍返回 `Error: Not found; no service started.`，需要先验证 Activity 冷启动入口是否能让 callback 回流。
- 2026-05-30：将 Shizuku 唤醒 app 主进程的 `am start-foreground-service -n` component 从相对类名调整为完整服务类名；原因是三星 S10 回归日志显示相对 component 返回 `Error: Not found; no service started.`，说明命令阶段未解析到服务而不是前台服务后台启动限制。
- 2026-05-30：新增 `ShizukuCallback.pingAppProcess()`、`ShizukuAppProcessReadiness` 和 Provider 身份查询前的 app 主进程探活/前台服务唤醒流程；原因是三星 S10 在 app 主进程未启动时 `content call` 无法冷启动 `clipboard-bridge` Provider，需要先用 callback 判断 app 是否存活，失活时通过唤醒命令拉起主进程，再继续完整进程名身份查询和 payload 提交。
- 2026-05-29：新增 `MmkvInitializer` 并让 `ClipboardBridgeProvider.onCreate()` 与 `BaseApplication.onCreate()` 复用同一个幂等 MMKV 初始化入口；原因是 `query_shizuku_process` 可在 ContentProvider 冷启动阶段早于 Application 访问 `AppSetting`，需要避免 Provider 因 MMKV 未初始化而让 Shizuku 身份查询降级为 `provider_query_failed`。
- 2026-05-29：新增 Shizuku 完整进程名身份校验方案；原因是覆盖安装后部分设备旧 Shizuku 进程不会自动退出且会同时创建新进程，改为 Shizuku 回调先读取剪贴板，再通过 Provider 唤醒 app、触发最新 bind、查询完整期望进程名，并只在完整进程名明确不匹配时 `destroy()` 旧进程。
- 2026-05-29：将 `AppSetting.pid` 调整为固定长度纯数字字符串，并新增 `ShizukuProcessName` 作为 suffix/fullProcessName 唯一构造入口；原因是进程名身份比较不再拆解版本号和 pid，固定数字串可避免 UUID 横线影响进程名和日志对比。
- 2026-05-29：将 `ServiceManager`、`IClipboard.Stub` 和 `IClipboard#getPrimaryClip` 反射读取逻辑下沉到 `base:hidden-api` 的 `SystemClipboardHiddenReader`，`ShizukuClipboardReader` 只保留 shell calling package 选择和 payload 映射；原因是系统剪贴板隐藏 API 签名适配属于底层隐藏 API 能力，Shizuku 模块不应直接承载 Binder/反射细节。
- 2026-05-29：将 `HiddenApiBypass.addHiddenApiExemptions` 直接调用收敛到 `base:hidden-api` 的 `HiddenApiExemptions`，并把第三方依赖从 `shizuku` 迁移到底层隐藏 API 模块；原因是 API 28 版本门控属于隐藏 API 基础设施能力，Shizuku 只应表达需要豁免的业务签名。
- 2026-05-29：为 `ShizukuClipboardReader.readPrimaryClip()` 的 `HiddenApiBypass.addHiddenApiExemptions` 增加 Android P/API 28 版本门控；原因是当前 minSdk 为 24，隐藏 API 豁免方法本身要求 API 28，低版本无需豁免且直接调用会触发编译/静态检查错误。
- 2026-05-29：将 Shizuku 进程 `IClipboard` 直读从探针升级为正式 payload 主链路；原因是实测 Shizuku 进程可读到剪贴板，而普通 app overlay 即使取得窗口焦点仍可能读到空，正式链路需要绕开 overlay 焦点不稳定性，并通过 `/clip/<eventId>`、`commit_clip`、`capturedAtMillis` 和脱敏 result bundle 固化协议。
- 2026-05-28：新增 Shizuku 进程内 `IClipboard` 直读探针；原因是实测主进程 Provider + overlay 即使取得 View 焦点也可能读到空剪贴板，需要先验证 shell/Shizuku 进程是否能直接读取系统剪贴板，再决定是否重构正式读取链路。
- 2026-05-27：将 Provider 通道改为“双协程彻底解耦”方案；原因是 `read_clip` 不应再承担图标同步判断，图标链路独立后可以更早进入当前剪贴板读取，并让 `query_icon_state` 与 `commit_icon` 在 app 侧闭环判断来源图标是否需要同步。
- 2026-05-27：Provider 图标传输改为异步补全方案；原因是 `content write`、decode、hash 和保存图标会拉长 Shizuku 回调链路，拆成 `read_clip` 快速入库与 `commit_icon` 后置补图后，可缩短冷启动剪贴保存耗时，并通过 `iconPath + primaryColor + iconHash` 更新触发 UI 图标刷新。
- 2026-05-27：收敛 Shizuku 侧异步图标补全实现；原因是实测日志显示图标先 `content write` 成功但没有继续 `commit_icon` 时，数据库不会更新图标字段，改为 `read_clip` 确认成功后单独协程执行 `content write` 与 `commit_icon`，让验证链路更直观。
- 2026-05-26：新增 Shizuku Provider 回调通道验证方案；原因是旧 `am startservice`/`start-foreground-service` 唤醒主进程会撞后台服务和前台服务限制，Provider 冷启动可能更适合验证主进程被杀后的剪贴板读取。旧 AIDL callback 保留，`USE_PROVIDER_BRIDGE` 开关用于快速回退。
- 2026-05-22：调整悬浮窗权限兜底策略，Shizuku 回调时不再先用 `Settings.canDrawOverlays()` 判断，而是每次直接写入主包名 AppOps；主进程 `ClipboardService` 改为直接尝试添加透明 View，并只在真实失败时提示无悬浮窗权限。原因是 Shizuku 进程与主进程的 uid/package 检查口径不一致时，预检查可能产生假阴性。
- 2026-05-22：将 `ClipboardShizukuService(Context)` 显式 consumer rule 收敛为构造函数 `@Keep` 标注；原因是 Shizuku 反射实例化入口可以由 AndroidX annotation consumer rule 精确保留，`shizuku/consumer-rules.pro` 不再需要维护实际 keep 规则。
- 2026-05-22：新增 Shizuku 剪贴板服务方案文档，并记录 AppOps 隐藏 API consumer rule 收敛为 `@Keep` 注解保留；原因是 `AppOpsManagerHidden`、`OnOpNotedListener` 和 `ClipboardListener` 已分别显式标注 `@Keep`，可以删除重复且更宽泛的三条 ProGuard/R8 规则。
