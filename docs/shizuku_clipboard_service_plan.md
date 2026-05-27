状态：已完成

# Shizuku 剪贴板服务方案

## 当前状态

Shizuku 模块通过 `ClipboardShizukuService` 在 Shizuku 进程中注册 AppOps 隐藏 API 监听器，用于感知其他应用写入剪贴板后回调主进程。隐藏 API 壳类型位于 `base/hidden-api`，其中 `AppOpsManagerHidden` 和 `AppOpsManagerHidden.OnOpNotedListener` 需要在 release/R8 构建中保持类名、方法签名和接口签名稳定；实际监听实现 `ClipboardListener` 使用具名类并通过 `@Keep` 保留，避免 R8 将隐藏 API 回调改写成不兼容形态。本轮新增 `ClipboardBridgeProvider` 实验通道，`ClipboardShizukuService.USE_PROVIDER_BRIDGE=true` 时 Shizuku 回调只走 Provider，不自动 fallback AIDL，用于真实验证主进程被杀后的冷启动、图标传输、悬浮窗读取和入库可靠性；旧 AIDL callback 代码完整保留，关闭开关即可回到旧路径。

当前保留策略已经收敛为注解优先：

- `AppOpsManagerHidden` 使用 `@Keep` 保留隐藏 API 壳类和成员。
- `AppOpsManagerHidden.OnOpNotedListener` 使用 `@Keep` 保留接口和回调方法签名。
- `ClipboardListener` 使用 `@Keep` 保留唯一实现类和成员。
- `ClipboardShizukuService(Context)` 构造函数使用 `@Keep` 保留 Shizuku 反射实例化入口。
- `shizuku/consumer-rules.pro` 不再保留显式 keep 规则，只作为空规则文件保留给 Gradle 配置引用。
- Shizuku 侧在每次 AppOps 回调时直接对主包名执行 `setMode(OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName, MODE_ALLOWED)`，不再依赖 Shizuku 进程里的 `Settings.canDrawOverlays()` 判断；主进程 `ClipboardService` 直接尝试添加 1x1 透明 View，并只在真实添加失败时提示悬浮窗权限问题。
- 新增 `ClipboardBridgeProvider`，authority 为 `${applicationId}.clipboard-bridge`，仅允许 shell/root UID 调用；Provider 通过 `content write` 接收图标 PNG，通过 `content call --method read_clip` 接收 `eventId`、来源包名、应用名和图标 hash。
- Provider 内部使用 `ClipboardBridgeReadCoordinator` 添加 1x1 透明悬浮窗、读取 `ClipboardManager.primaryClip`、复用 `ClipHelper.processClip()` 入库，避免在 Provider 中再次启动 `ClipboardService`。

## 目标

- 保持 Shizuku 剪贴板监听在 debug 和 release/R8 构建下都能按隐藏 API 签名注册、回调和注销。
- 避免在 consumer rules 中重复维护已经由 `@Keep` 覆盖的隐藏 API 壳类、监听实现和 Shizuku 反射构造函数规则。
- 避免 Shizuku 进程和主进程对 `Settings.canDrawOverlays()` 的 uid/package 检查口径不一致时误判为无悬浮窗权限。
- 将隐藏 API、反射实例化和 R8 保留边界集中记录，方便后续修改时按职责补充注解或规则。
- 验证 ContentProvider 冷启动通道是否能在主进程被系统杀掉后稳定传递来源数据、读取剪贴板并入库。
- 保持 AIDL callback 代码和 `ClipboardService` 旧路径可快速回退，不在验证阶段正式淘汰 AIDL。

## 范围

- `base/hidden-api/src/main/java/android/app/AppOpsManagerHidden.java`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardListener.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardShizukuService.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardBridgeContract.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeProvider.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeReadCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconStore.kt`
- `shizuku/consumer-rules.pro`
- `docs/shizuku_clipboard_service_plan.md`

不包含：

- 主进程剪贴板入库、通知、数据库或列表展示逻辑的语义变更；Provider 通道只复用 `ClipHelper.processClip()`。
- Shizuku 权限申请 UI 和“我的”页设置项结构。
- AppOps 隐藏 API 的新增能力扩展。

## 用户体验

开启 Shizuku 能力后，服务仍在 Shizuku 进程监听剪贴板写入事件。`USE_PROVIDER_BRIDGE=true` 时，Provider 通道拆成 `read_clip` 与 `commit_icon` 两段：`read_clip` 只传来源包名、应用名和图标 hash，主进程冷启动后先读取剪贴板并快速入库；图标 PNG 在后台异步通过 `content write` 和 `commit_icon` 补齐。`USE_PROVIDER_BRIDGE=false` 时恢复旧 AIDL callback 和 `ClipboardService` 路径。Provider 模式下如果图标写入失败、缺失、超时或 hash 不匹配，剪贴内容仍保持已保存状态，但不会写入失败的 `iconHash`，便于下一次同来源剪贴事件自然重试补图。

## 数据流

1. 主进程连接 Shizuku 并实例化 `ClipboardShizukuService(Context)`。
2. `ClipboardShizukuService.startListener()` 通过 `HiddenApiBypass` 和 `Refine.unsafeCast<AppOpsManagerHidden>` 调用隐藏 API。
3. 服务创建 `ClipboardListener(packageName, owner)` 并注册到 `startWatchingNoted(intArrayOf(30), listener)`。
4. `ClipboardListener` 收到字符串 op 或数字 code 回调后过滤自身包名，再调用 `owner.handleOpNoted(clipPackageName)`。
5. `ClipboardShizukuService` 每次回调都对主包名执行 `setMode(OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName, MODE_ALLOWED)`，避免旧状态、系统回收或跨进程检查口径导致主进程读剪贴板前被误拦截。
6. `ClipboardShizukuService` 读取来源应用信息、图标 bitmap 和图标 hash。
7. 当 `USE_PROVIDER_BRIDGE=true` 时，Shizuku 侧生成 `eventId`，先通过 `content call --uri content://com.cla.clip.master.clipboard-bridge --method read_clip --extra eventId:s:<eventId> ...` 传递小字段并触发读取；Provider 只接受 shell/root UID。
8. `ClipboardBridgeProvider` 解析参数后交给 `ClipboardBridgeReadCoordinator`，后者切主线程添加透明悬浮窗、读取剪贴板并移除 View，再复用 `ClipHelper.processClip()` 入库；如果数据库中已有旧图标，入库时先沿用旧图标，避免列表从真实图标闪回占位图。
9. Provider 返回 `resultCode`、`saved`、`readClip`、`overlayAdded`、`iconStatus` 和 `iconDeferred`；Shizuku 侧必须看到 `resultCode=ok` 且 `saved=true` 才记录为 `read_clip` 成功，`iconDeferred` 仅表示后续是否有图标待补齐。
10. `read_clip` 成功后，Shizuku 侧单独启动一个 IO 协程补齐图标；任务把 PNG 通过 `content write --uri content://com.cla.clip.master.clipboard-bridge/icon/<eventId>` 写入 `<eventId>.tmp`，再通过 `commit_icon` 让 Provider decode、校验 `Bitmap.toStableHash()`、保存图标并更新来源 App。
11. `content write` 和 `commit_icon` 都设置 2-3 秒超时；超时、半文件、decode 失败或 hash 不匹配只记录图标补全失败，不回滚已保存剪贴记录，也不重复发剪贴通知。
12. 当 `USE_PROVIDER_BRIDGE=false` 时，保留旧流程：`ClipboardShizukuService` 通过 AIDL callback 回传主进程，主进程再走 `ClipboardService` 读取剪贴板。
13. 销毁或重连时，服务使用当前 listener 调用 `stopWatchingNoted(listener)` 并清理协程。

## Provider 失败码与回退

- `ok`：已读取剪贴板并完成入库。
- `invalid_caller`：调用方不是 shell/root，Provider 不读取剪贴板。
- `invalid_args`：method 或 `eventId` 等参数非法。
- `icon_missing`：`commit_icon` 阶段图标缺失、半文件、decode 失败或校验失败；不写入新的 `iconHash`，等待下一次事件重试。
- `overlay_failed`：透明悬浮窗添加失败，无法读取剪贴板。
- `no_clip`：剪贴板为空或没有可保存 item。
- `read_failed`：读取或入库过程出现异常。
- `timeout`：Provider 等待读取和入库超过 3 秒。
- `iconDeferred=true`：`read_clip` 已保存剪贴记录，但本次有图标 hash 待异步补齐。
- 回退方式：将 `ClipboardShizukuService.USE_PROVIDER_BRIDGE` 改为 `false`，旧 AIDL callback、`ShizukuConnector.setCallback()` 和 `ClipboardService.start()` 代码仍保留。

## R8 与隐藏 API 契约

- `AppOpsManagerHidden` 和 `OnOpNotedListener` 属于隐藏 API 编译壳，稳定边界是类名、接口名、成员方法签名和默认方法签名；通过 AndroidX `@Keep` 交给 annotation 自带 consumer rule 保留。
- `ClipboardListener` 属于隐藏 API 回调实现，稳定边界是实现类本身、接口关系和两个 `onOpNoted` 方法签名；当前类已使用 `@Keep`，因此不再需要 `-keep class * implements android.app.AppOpsManagerHidden$OnOpNotedListener { *; }` 这种宽泛规则。
- 如果后续新增其他 `OnOpNotedListener` 实现类，新增实现也必须显式标注 `@Keep` 或补充更精确的 consumer rule，不能依赖已经删除的通配实现规则。
- `ClipboardShizukuService(Context)` 仍由 Shizuku 反射实例化，稳定边界是服务类名和 `Context` 构造函数；当前构造函数已使用 `@Keep`，不再需要 `shizuku/consumer-rules.pro` 中的显式 keep 规则。

## 日志与诊断计划

- 本次保留既有运行时日志：Shizuku 服务继续记录 AppOps 回调、前台服务拉起、callback 投递和隐藏 API 注册/注销错误；主进程 `ClipboardService` 继续记录透明 View 添加、剪贴板读取和异常分支。
- Provider 通道新增日志：Shizuku 侧分别记录 `read_clip` 和 `commit_icon` 的 `eventId`、包名、应用名、耗时、图标字节数、`content write/call` exitCode、Provider `resultCode`、`saved`、`iconStatus`、是否启动异步补图和是否超时；主进程 Provider 记录调用方 UID、参数校验结果、图标状态、悬浮窗是否添加、是否入库、是否补图和失败 reasonCode。
- 悬浮窗权限提示从预检查失败改为真实添加 View 失败时触发；该分支记录 `logE(TAG, it) { "读取剪贴板内容出错" }`，并在 `SecurityException` 或 `WindowManager.BadTokenException` 时提示用户。
- 隐藏 API 注册失败、注销失败、主进程回调失败等运行时诊断继续由 `ClipboardShizukuService` 现有日志承担。
- release/R8 诊断以构建验证为主：执行 `./gradlew :app:minifyReleaseWithR8`，确认 `@Keep` 注解和 AndroidX annotation consumer rule 能覆盖隐藏 API 壳类、接口、监听实现和 Shizuku 反射构造函数。
- 日志中的 `iconHash` 均指现有 `Bitmap.toStableHash()`，不把 PNG bytes hash 当作数据库签名；日志禁止输出剪贴板正文、完整用户输入、Token、Cookie、完整 URL 查询串或本地授权 URI；允许保留包名、应用名、图标尺寸、图标字节数、哈希、eventId 和 reasonCode 等低敏信息。

## 测试验证

- 编译验证：运行 `./gradlew :shizuku:compileDebugKotlin`。
- 主进程编译验证：运行 `./gradlew :app:compileDebugKotlin`。
- 单元测试：运行 `./gradlew :app:testDebugUnitTest --tests "com.cla.clip.master.provider.*"` 和 `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.*ClipboardBridge*"`，覆盖 Provider 参数解析、调用方 UID 校验、图标路径解析、过期临时图标清理、图标 hash 校验规则、`read_clip`/`commit_icon` 返回结果解析。
- release/R8 验证：运行 `./gradlew :app:minifyReleaseWithR8`，确认删除显式 consumer rule 后混淆链路通过。
- 静态检查：运行 `git diff --check`。
- 人工回归建议：`USE_PROVIDER_BRIDGE=true` 时分别在 app 存活和杀进程后复制其他应用内容，确认记录先快速入库；验证日志中 `read_clip` 成功后继续出现 `content write` 和 `commit_icon`，首次占位/旧图标随后异步刷新，图标写入失败或超时不丢剪贴记录，非 shell/root 调用拒绝；`USE_PROVIDER_BRIDGE=false` 时确认旧 AIDL 行为恢复。

## 已知取舍

- 当前采用 AndroidX `@Keep` 作为统一保留标记，避免在 consumer rules 中逐项保留隐藏 API 壳类型、监听实现和 Shizuku 反射构造函数。
- `shizuku/consumer-rules.pro` 暂不删除文件本身，因为 Gradle 仍引用该文件；当前只保留中文说明，避免空文件让后续维护者误以为遗漏规则。
- `ClipboardShizukuService.handleOpNoted()` 每次回调都写一次悬浮窗 AppOps，牺牲一次轻量系统调用，换取跨 Shizuku 进程与主进程检查口径不一致时的稳定性。
- 本次不重构旧 AIDL callback 或 `ClipboardService`，只在 Shizuku 侧通过实验开关新增 Provider 通道；这样 Provider 不可靠时可快速关闭开关回到当前状态。
- Provider 模式下暂不自动 fallback AIDL，原因是验证阶段需要暴露 Provider 真实失败率，避免旧路径兜底掩盖问题。
- 图标失败时使用旧图标或占位图继续入库，优先验证剪贴内容冷启动保存链路；图标保存成功前不记录新的 `iconHash`，避免后续图标无法补齐。
- `eventId` 只用于临时图标文件和日志串联，不参与来源 App 缓存身份判断；数据库更新以 `packageName + Bitmap.toStableHash()` 为准。

## 开放问题

- 如果后续接入更多 AppOps 隐藏 API 回调实现，需要统一检查新增实现类是否已显式 `@Keep`。
- 如果后续调整 Shizuku 服务构造函数签名或新增其他反射实例化入口，需要同步补充 `@Keep` 或精确 consumer rule。
- 如果未来迁移到自定义语义化 keep 注解，需要同步评估 AndroidX annotation consumer rule 的替代规则和 release/R8 验证方式。
- Provider 通道验证通过后，需要单独评估是否将 `USE_PROVIDER_BRIDGE` 改成设置项、远端开关或正式替换策略。
- 如果发现部分 ROM 中 Provider 添加悬浮窗仍无法读取剪贴板，需要记录机型、Android 版本、resultCode 和是否 app 存活，再决定是否恢复 AIDL 或增加其他合法入口。
- 异步图标补全稳定后，需要评估是否补充更严格的同包名串行或去重策略；当前验证阶段优先保持“read_clip 成功后单独协程补图”的直观链路。

## 变更记录

- 2026-05-27：Provider 图标传输改为异步补全方案；原因是 `content write`、decode、hash 和保存图标会拉长 Shizuku 回调链路，拆成 `read_clip` 快速入库与 `commit_icon` 后置补图后，可缩短冷启动剪贴保存耗时，并通过 `iconPath + primaryColor + iconHash` 更新触发 UI 图标刷新。
- 2026-05-27：收敛 Shizuku 侧异步图标补全实现；原因是实测日志显示图标先 `content write` 成功但没有继续 `commit_icon` 时，数据库不会更新图标字段，改为 `read_clip` 确认成功后单独协程执行 `content write` 与 `commit_icon`，让验证链路更直观。
- 2026-05-26：新增 Shizuku Provider 回调通道验证方案；原因是旧 `am startservice`/`start-foreground-service` 唤醒主进程会撞后台服务和前台服务限制，Provider 冷启动可能更适合验证主进程被杀后的剪贴板读取。旧 AIDL callback 保留，`USE_PROVIDER_BRIDGE` 开关用于快速回退。
- 2026-05-22：调整悬浮窗权限兜底策略，Shizuku 回调时不再先用 `Settings.canDrawOverlays()` 判断，而是每次直接写入主包名 AppOps；主进程 `ClipboardService` 改为直接尝试添加透明 View，并只在真实失败时提示无悬浮窗权限。原因是 Shizuku 进程与主进程的 uid/package 检查口径不一致时，预检查可能产生假阴性。
- 2026-05-22：将 `ClipboardShizukuService(Context)` 显式 consumer rule 收敛为构造函数 `@Keep` 标注；原因是 Shizuku 反射实例化入口可以由 AndroidX annotation consumer rule 精确保留，`shizuku/consumer-rules.pro` 不再需要维护实际 keep 规则。
- 2026-05-22：新增 Shizuku 剪贴板服务方案文档，并记录 AppOps 隐藏 API consumer rule 收敛为 `@Keep` 注解保留；原因是 `AppOpsManagerHidden`、`OnOpNotedListener` 和 `ClipboardListener` 已分别显式标注 `@Keep`，可以删除重复且更宽泛的三条 ProGuard/R8 规则。
