状态：实现中

# Shizuku 剪贴板服务方案

## 当前状态

Shizuku 模块通过 `ClipboardShizukuService` 在 Shizuku 进程中注册 AppOps 隐藏 API 监听器，用于感知其他应用写入剪贴板后回调主进程。隐藏 API 壳类型位于 `base/hidden-api`，其中 `AppOpsManagerHidden` 和 `AppOpsManagerHidden.OnOpNotedListener` 需要在 release/R8 构建中保持类名、方法签名和接口签名稳定；实际监听实现 `ClipboardListener` 使用具名类并通过 `@Keep` 保留，避免 R8 将隐藏 API 回调改写成不兼容形态。

当前 Provider 通道已经从“主进程 overlay 读取剪贴板”升级为“Shizuku 进程直读剪贴板 + app Provider 提交入库”的正式链路：

- Shizuku 回调入口立即生成独立 `eventId`，记录 `capturedAtMillis`，并用 `ShizukuClipboardReader` 通过 `IClipboard.getPrimaryClip` 读取当前 `ClipData` 快照。
- 剪贴内容链路把 v1 JSON payload 通过 `content write content://<authority>/clip/<eventId>` 写入 app 私有临时目录，再调用 Provider `commit_clip` 解析和入库。
- 图标链路继续沿用既有 `query_icon_state`、`content write /icon/<eventId>`、`commit_icon` 方案，不改变 hash、decode、保存、来源缓存或去重规则。
- 剪贴内容链路和图标链路使用同一个 `eventId` 串联日志，但分别走不同 Provider path、不同临时目录和不同协程，任一链路失败都不取消另一条链路。
- 旧 `read_clip` overlay Provider 读取只保留为默认关闭的调试回退开关，用于诊断 ROM 行为，不作为默认成功率兜底。

## 目标

- 把已验证可行的 Shizuku 进程 `IClipboard` 直读能力升级为正式剪贴板读取链路，降低 MIUI 上普通 app overlay 读剪贴板返回空的风险。
- 保证已被 Shizuku 成功读取到的不同 payload 在 app 侧传输、提交、清理过程中不互相覆盖、不误删。
- 保持现有内容去重语义：上一条内容相同仍跳过保存，不因为 `eventId` 独立而改变业务去重。
- 让 `capturedAtMillis` 跟随读取快照进入入库时间，避免并发 `commit_clip` 完成顺序改变剪贴列表顺序。
- 继续让图标同步脱离剪贴内容关键路径，图标失败不影响文本入库，文本失败也不影响图标缓存预热。

## 范围

- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardShizukuService.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ShizukuClipboardReader.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardBridgeClipPayload.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardBridgeContract.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeProvider.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeClipPayloadStore.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeClipCommitCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeReadCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconQueryCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconCommitter.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconSyncDecider.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconStore.kt`
- `app/src/main/java/com/cla/clip/master/utils/ClipHelper.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepositoryImpl.kt`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `docs/webdav_backup_plan.md`

不包含：

- 新增数据库字段或 Room schema 迁移。
- 保存原始 HTML、URI、Intent、图片或多 item 剪贴板内容。
- 改动来源图标 hash、decode、保存、缓存命中、坏文件清理或来源筛选语义。
- 将旧 overlay Provider 读取作为默认兜底；该路径只用于显式开启的调试回退。

## 用户体验

开启 Shizuku 能力后，用户复制普通文本或富文本时，Shizuku 服务会在 AppOps 回调后直接读取当前剪贴板，并把文本 payload 交给 app 入库。若 payload 是普通文本，直接保存文本；若普通文本为空但存在 HTML，则 app 侧把 HTML 转为纯文本后保存；若仍为空白，沿用“空内容或重复内容不保存”的表现。URI、Intent、图片和文件类剪贴板第一版明确返回不支持，不会尝试搬运或记录敏感 URI/Intent 内容。

来源图标仍可能稍晚补齐：文本入库和图标同步并行执行，剪贴记录可以先保存并显示已有缓存图标或占位图，随后由 `source_apps` Room 观察刷新真实图标。

## 数据流

1. 主进程连接 Shizuku 并实例化 `ClipboardShizukuService(Context)`。
2. `ClipboardShizukuService.start()` 通过 `HiddenApiBypass` 和 `Refine.unsafeCast<AppOpsManagerHidden>` 注册 `ClipboardListener` 到 `startWatchingNoted(intArrayOf(30), listener)`。
3. `ClipboardListener` 收到字符串 op 或数字 code 回调后过滤自身包名，再调用 `owner.handleOpNoted(clipPackageName)`。
4. `handleOpNoted()` 每次回调立即生成独立 `eventId`，记录 `capturedAtMillis`，并在 Shizuku 进程内用 `ShizukuClipboardReader.readPrimaryClip()` 读取 `ClipData` 快照。
5. `ShizukuClipboardReader` 固定以 `com.android.shell` 作为 `IClipboard.getPrimaryClip` 的 calling package，AppOps 回调里的来源包名只作为来源 App package，主包名只作为 host package，三者禁止混用。
6. Shizuku 只读取第一个 `ClipData.Item`，提取 `text`、`htmlText`、MIME、URI/Intent 是否存在等低敏摘要；日志只记录长度和布尔值，不输出正文。
7. `ClipboardShizukuService` 解析来源应用名、图标 bitmap 和 `Bitmap.toStableHash()`，然后通过 `supervisorScope` 并行启动两条协程。
8. `clipPayloadJob` 把 payload 序列化为 UTF-8 JSON，通过进程 stdin 写给 `content write --uri content://<authority>/clip/<eventId>`；只有 exitCode 为 0 后，才调用 `content call --method commit_clip`。
9. `commit_clip` 在 app 侧读取 `files/clipboard_bridge_clip_payloads/<eventId>.tmp`，校验版本、eventId、时间戳和 JSON 结构，提取文本或 HTML fallback 后委托 `ClipHelper.processClipText(...)` 入库。
10. `ClipHelper.processClipText(...)` 复用现有链接解析、通知、备份 dirty 标记和“上一条内容相同则跳过保存”规则；保存时优先使用 payload 的 `capturedAtMillis` 作为剪贴记录时间。
11. `iconJob` 独立调用 `query_icon_state`，必要时继续执行 `content write --uri content://<authority>/icon/<eventId>` 和 `commit_icon`；图标逻辑完全沿用既有缓存命中与坏文件清理规则。
12. `commit_clip` 提交成功、失败或异常都会清理自己的 eventId 文件；过期清理只删除超时 `.tmp` 文件，不清空整个目录。
13. 当旧调试回退开关显式开启且 payload 不可用时，Shizuku 才会调用旧 `read_clip` overlay 路径；默认正式链路不会自动 fallback。

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
- 作用：旧 overlay Provider 读取调试回退，默认关闭
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
- `overlay_failed`：旧调试 overlay 路径添加悬浮窗失败。
- `read_failed`：旧调试 overlay 路径读取或入库过程出现异常。
- `timeout`：Provider 等待当前阶段完成超时。

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

## 并发与边界

- 每次 AppOps 回调都生成独立 `eventId`，payload 文件名使用 `<eventId>.tmp`，避免连续回调互相覆盖。
- `clipPayloadJob` 不等待上一条 Provider 提交、图标预判或图标上传；只要 Shizuku 已读取到 payload，就尽快写入 `/clip/<eventId>`。
- `iconJob` 不等待剪贴内容提交结果，继续沿用已有图标同步流程。
- `supervisorScope` 隔离失败，剪贴内容失败不取消图标同步，图标失败也不影响剪贴内容入库。
- eventId 独立文件只能保护 app 侧传输和提交阶段；如果系统剪贴板在 Shizuku 读取前已经被下一次复制覆盖，中间值无法由 app 恢复。

## `source_apps` 与备份语义

- `source_apps` 继续承载两类职责：
  - 已保存剪贴记录的来源展示缓存；
  - 由图标链路独立预热的来源图标缓存。
- 因此 `source_apps` 允许先于真实剪贴记录出现，搜索页来源筛选继续直接读取这些数据，这是显式产品预期。
- 本次不新增字段、不改 Room schema、不改 JSONL/zip 备份协议结构。
- `files/clipboard_bridge_clip_payloads/` 只保存短期敏感 payload 临时文件，提交成功、失败和异常都清理自己的 eventId；系统 Auto Backup 和设备迁移规则必须排除该目录。

## R8 与隐藏 API 契约

- `AppOpsManagerHidden` 和 `OnOpNotedListener` 属于隐藏 API 编译壳，稳定边界是类名、接口名、成员方法签名和默认方法签名；通过 AndroidX `@Keep` 交给 annotation 自带 consumer rule 保留。
- `ClipboardListener` 属于隐藏 API 回调实现，稳定边界是实现类本身、接口关系和两个 `onOpNoted` 方法签名；当前类已使用 `@Keep`，因此不再需要宽泛 consumer rule。
- `ClipboardShizukuService(Context)` 仍由 Shizuku 反射实例化，稳定边界是服务类名和 `Context` 构造函数；当前构造函数已使用 `@Keep`。

## 日志与诊断计划

- Shizuku 侧继续记录 AppOps 回调、隐藏 API 注册/注销错误、`IClipboard` 读取摘要、Provider `content call/write` 的 exitCode 和结果码。
- 剪贴内容链路和图标链路都带同一个 `eventId`，用于串联同一次来源事件。
- 剪贴内容链路只允许记录 `clipNull`、payload 是否为空、item 数量、MIME 类型、text/html 长度、是否含 URI/Intent、`content write` 字节数、exitCode、resultCode、clipStatus、耗时和异常类型。
- 图标链路继续记录 `iconSyncKey=packageName#iconHash`、`query_icon_state`、`content write`、`commit_icon`、`iconDecisionReason`、是否命中缓存、是否实际上传、是否复用旧图标。
- app 侧 payload store 只记录 eventId、文件大小、解析结果、过期清理数量和异常类型；禁止输出 payload 正文、HTML 原文、URI 或 Intent 内容。
- HTML fallback 只记录 HTML 长度和转换后文本长度；转换结果禁止输出。
- 日志禁止输出剪贴板正文、完整用户输入、Token、Cookie、完整 URL 查询串、本地授权 URI、URI 原文或 Intent 内容；允许保留包名、应用名、图标尺寸、图标字节数、hash、eventId、iconSyncKey、reasonCode 和耗时等低敏信息。

## 测试验证

- 编译验证：
  - `./gradlew :shizuku:compileDebugKotlin`
  - `./gradlew :app:compileDebugKotlin`
- 单元测试：
  - `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.*ClipboardBridge*"`
  - `./gradlew :app:testDebugUnitTest --tests "com.cla.clip.master.provider.*ClipboardBridge*"`
  - `./gradlew :base:general:testDebugUnitTest --tests "com.cla.clip.base.general.repository.ClipRepositorySourceAppMergeTest"`
- 静态检查：
  - `git diff --check -- <本次修改文件>`
- 人工回归建议：
  - 主进程存活和被杀后分别复制普通文本、富文本、长文本、连续不同文本和连续相同文本；
  - 复制 URI、Intent、图片和文件类剪贴板，确认返回不支持或无内容，不产生误入库；
  - 模拟 `content write /clip/<eventId>` 失败，确认不会调用 `commit_clip`；
  - 模拟图标失败，确认文本仍可入库；
  - 显式开启旧 overlay 调试回退，确认只有调试开关下才触发 `read_clip`。

## 已知取舍

- 第一版只处理第一个 `ClipData.Item`，多 item、URI、Intent、图片和文件搬运作为后续阶段。
- 第一版不保存原始 HTML，只在普通文本为空时用 HTML 生成纯文本 fallback。
- Provider 模式下默认不自动 fallback 到 overlay 或 AIDL，原因是正式链路需要暴露 Shizuku 直读与 payload 提交的真实失败率。
- 图标同步状态只保存在 Shizuku 进程内存，不落数据库；进程死亡后允许下次事件自然重试。
- `eventId` 只用于日志串联和临时 payload/icon 文件，不参与来源缓存身份判断；数据库来源更新仍以 `packageName` 和 `Bitmap.toStableHash()` 为核心。

## 开放问题

- 如果后续支持 URI、Intent、图片或文件，需要重新设计权限、文件搬运、大小上限、MIME 白名单和日志脱敏边界。
- 如果后续支持多 item，需要定义多 item 的排序、去重、失败部分提交和 UI 展示规则。
- 如果后续接入更多 AppOps 隐藏 API 回调实现，需要统一检查新增实现类是否已显式 `@Keep`。
- 如果后续调整 Shizuku 服务构造函数签名或新增其他反射实例化入口，需要同步补充 `@Keep` 或精确 consumer rule。

## 变更记录

- 2026-05-29：将 Shizuku 进程 `IClipboard` 直读从探针升级为正式 payload 主链路；原因是实测 Shizuku 进程可读到剪贴板，而普通 app overlay 即使取得窗口焦点仍可能读到空，正式链路需要绕开 overlay 焦点不稳定性，并通过 `/clip/<eventId>`、`commit_clip`、`capturedAtMillis` 和脱敏 result bundle 固化协议。
- 2026-05-28：新增 Shizuku 进程内 `IClipboard` 直读探针；原因是实测主进程 Provider + overlay 即使取得 View 焦点也可能读到空剪贴板，需要先验证 shell/Shizuku 进程是否能直接读取系统剪贴板，再决定是否重构正式读取链路。
- 2026-05-27：将 Provider 通道改为“双协程彻底解耦”方案；原因是 `read_clip` 不应再承担图标同步判断，图标链路独立后可以更早进入当前剪贴板读取，并让 `query_icon_state` 与 `commit_icon` 在 app 侧闭环判断来源图标是否需要同步。
- 2026-05-27：Provider 图标传输改为异步补全方案；原因是 `content write`、decode、hash 和保存图标会拉长 Shizuku 回调链路，拆成 `read_clip` 快速入库与 `commit_icon` 后置补图后，可缩短冷启动剪贴保存耗时，并通过 `iconPath + primaryColor + iconHash` 更新触发 UI 图标刷新。
- 2026-05-27：收敛 Shizuku 侧异步图标补全实现；原因是实测日志显示图标先 `content write` 成功但没有继续 `commit_icon` 时，数据库不会更新图标字段，改为 `read_clip` 确认成功后单独协程执行 `content write` 与 `commit_icon`，让验证链路更直观。
- 2026-05-26：新增 Shizuku Provider 回调通道验证方案；原因是旧 `am startservice`/`start-foreground-service` 唤醒主进程会撞后台服务和前台服务限制，Provider 冷启动可能更适合验证主进程被杀后的剪贴板读取。旧 AIDL callback 保留，`USE_PROVIDER_BRIDGE` 开关用于快速回退。
- 2026-05-22：调整悬浮窗权限兜底策略，Shizuku 回调时不再先用 `Settings.canDrawOverlays()` 判断，而是每次直接写入主包名 AppOps；主进程 `ClipboardService` 改为直接尝试添加透明 View，并只在真实失败时提示无悬浮窗权限。原因是 Shizuku 进程与主进程的 uid/package 检查口径不一致时，预检查可能产生假阴性。
- 2026-05-22：将 `ClipboardShizukuService(Context)` 显式 consumer rule 收敛为构造函数 `@Keep` 标注；原因是 Shizuku 反射实例化入口可以由 AndroidX annotation consumer rule 精确保留，`shizuku/consumer-rules.pro` 不再需要维护实际 keep 规则。
- 2026-05-22：新增 Shizuku 剪贴板服务方案文档，并记录 AppOps 隐藏 API consumer rule 收敛为 `@Keep` 注解保留；原因是 `AppOpsManagerHidden`、`OnOpNotedListener` 和 `ClipboardListener` 已分别显式标注 `@Keep`，可以删除重复且更宽泛的三条 ProGuard/R8 规则。
