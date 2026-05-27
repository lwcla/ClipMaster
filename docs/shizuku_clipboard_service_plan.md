状态：实现中

# Shizuku 剪贴板服务方案

## 当前状态

Shizuku 模块通过 `ClipboardShizukuService` 在 Shizuku 进程中注册 AppOps 隐藏 API 监听器，用于感知其他应用写入剪贴板后回调主进程。隐藏 API 壳类型位于 `base/hidden-api`，其中 `AppOpsManagerHidden` 和 `AppOpsManagerHidden.OnOpNotedListener` 需要在 release/R8 构建中保持类名、方法签名和接口签名稳定；实际监听实现 `ClipboardListener` 使用具名类并通过 `@Keep` 保留，避免 R8 将隐藏 API 回调改写成不兼容形态。本轮继续保留 `ClipboardBridgeProvider` 实验通道，`ClipboardShizukuService.USE_PROVIDER_BRIDGE=true` 时 Shizuku 回调只走 Provider，不自动 fallback AIDL，用于验证主进程被系统杀掉后的冷启动读取、来源缓存预热和图标同步可靠性；旧 AIDL callback 代码完整保留，关闭开关即可回到旧路径。

当前 Provider 通道已经从“`read_clip` 返回 `iconDeferred`，再由 Shizuku 侧单独异步补图”收敛为“双协程彻底解耦”：

- 协程 A 只负责快速调用 `read_clip`，让主进程冷启动后尽快读取当前系统剪贴板并入库。
- 协程 B 独立调用 `query_icon_state`，由 app 侧自行判断当前来源图标是否需要继续同步；只有明确需要时才执行 `content write + commit_icon`。
- `read_clip` 与图标链路互不等待，图标同步不再阻塞下一次剪贴板读取。
- `source_apps` 的语义显式扩展为“来源展示缓存 + 图标预热缓存”，允许先于真实剪贴记录出现。

## 目标

- 保持 Shizuku 剪贴板监听在 debug 和 release/R8 构建下都能按隐藏 API 签名注册、回调和注销。
- 让主进程冷启动读取剪贴板尽量靠前，优先抓住“当前最新剪贴板”，而不是保证捕获极短时间内每一次连续变化的中间态。
- 将图标同步彻底移出剪贴内容保存关键路径，减少图标 IO、decode、hash 和文件写入对下一次 `read_clip` 的干扰。
- 把来源图标是否需要同步的判断集中在 app 侧统一决策，避免 Shizuku 和 app 各维护一套命中规则。
- 保持 AIDL callback 代码和 `ClipboardService` 旧路径可快速回退，不在验证阶段正式淘汰 AIDL。

## 范围

- `base/hidden-api/src/main/java/android/app/AppOpsManagerHidden.java`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardListener.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardShizukuService.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardBridgeContract.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeProvider.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeReadCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconQueryCoordinator.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconCommitter.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconSyncDecider.kt`
- `app/src/main/java/com/cla/clip/master/provider/ClipboardBridgeIconStore.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepositoryImpl.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/SourceAppDao.kt`
- `docs/shizuku_clipboard_service_plan.md`
- `docs/webdav_backup_plan.md`

不包含：

- 主进程剪贴板入库、通知、数据库或列表展示逻辑的语义变更；Provider 通道继续复用 `ClipHelper.processClip()`。
- Shizuku 权限申请 UI 和“我的”页设置项结构。
- Room schema 迁移、备份协议结构变更或新的 release/R8 策略。

## 用户体验

开启 Shizuku 能力后，服务仍在 Shizuku 进程监听剪贴板写入事件。`USE_PROVIDER_BRIDGE=true` 时，Shizuku 在每次回调后会尽快把来源包名、应用名和图标 hash 投递到 app，让主进程冷启动后马上通过透明悬浮窗读取当前剪贴板并入库；来源图标是否需要同步则由另一条独立链路判断。图标同步成功前，剪贴记录可以先展示旧图标或空图标；一旦 `source_apps` 被 `commit_icon` 更新，列表、搜索和详情依赖 Room 观察自动刷新为新图标。

搜索页来源筛选继续直接读取 `source_apps`，因此允许出现“某个来源 App 已经在筛选器中可见，但当前并没有对应到一条现存剪贴记录”的情况。这是图标预热缓存的显式产品预期，不额外增加“必须先有 clip 才能写来源缓存”的门槛。

## 数据流

1. 主进程连接 Shizuku 并实例化 `ClipboardShizukuService(Context)`。
2. `ClipboardShizukuService.start()` 通过 `HiddenApiBypass` 和 `Refine.unsafeCast<AppOpsManagerHidden>` 注册 `ClipboardListener` 到 `startWatchingNoted(intArrayOf(30), listener)`。
3. `ClipboardListener` 收到字符串 op 或数字 code 回调后过滤自身包名，再调用 `owner.handleOpNoted(clipPackageName)`。
4. `ClipboardShizukuService` 每次回调都对主包名执行 `setMode(OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName, MODE_ALLOWED)`，避免旧状态、系统回收或跨进程检查口径导致主进程读剪贴板前被误拦截。
5. `handleOpNoted()` 防抖 100ms 后解析来源应用名、图标 bitmap 和 `Bitmap.toStableHash()`。
6. 当 `USE_PROVIDER_BRIDGE=true` 时，Shizuku 侧生成同一个 `eventId`，随后并行启动两条协程：
   - 协程 A 调用 `content call --method read_clip`，把 `eventId`、来源包名、应用名和图标 hash 传给 app，主进程只负责尽快读取剪贴板并入库。
   - 协程 B 调用 `content call --method query_icon_state`，由 app 独立查询 `source_apps`、检查 `iconPath` 是否存在，并返回 `shouldSyncIcon` 与 `iconDecisionReason`。
7. `ClipboardBridgeReadCoordinator` 切主线程添加 1x1 透明悬浮窗、读取 `ClipboardManager.primaryClip`、移除 View，再复用 `ClipHelper.processClip()` 入库；如果数据库中已有可读旧图标，入库时先沿用旧图标，避免列表从真实图标闪回占位图。若旧 `iconPath` 指向坏文件，则 `read_clip` 不再把坏路径重新写回来源缓存。
8. `ClipboardBridgeIconQueryCoordinator` 使用 `ClipboardBridgeIconSyncDecider` 统一判断图标是否需要同步：
   - `cache_hit`：数据库 hash 命中且文件存在，直接返回 `shouldSyncIcon=false`。
   - `stale_file_missing`：数据库 hash 命中但文件缺失，先清空 `source_apps` 的 `iconPath/primaryColor/iconHash`，再返回 `shouldSyncIcon=true`。
   - `no_cached_icon`：数据库没有任何缓存，返回 `shouldSyncIcon=true`。
   - `hash_changed`：数据库 hash 与当前请求 hash 不同，返回 `shouldSyncIcon=true`。
   - `no_icon_available`：当前事件没有图标参数，返回 `shouldSyncIcon=false`。
9. `query_icon_state` 返回 `shouldSyncIcon=false` 时，Shizuku 图标协程直接结束；返回 `true` 时，才继续执行 `content write --uri content://<authority>/icon/<eventId>` 上传 PNG，再调用 `commit_icon`。
10. `commit_icon` 从临时目录读取 PNG、decode、重新计算 `Bitmap.toStableHash()`、校验和 Shizuku 传入 hash 一致后，通过 `saveIcon()` 把图标写入 app 私有目录，并提取主色写回 `source_apps`。只有“数据库 `iconHash` 相同且本地图标文件真实存在”时才允许直接返回 `reused`。
11. 图标同步状态只保存在 Shizuku 进程内存的 `iconSyncKey=packageName#iconHash` 集合中，用于去重；不落数据库。进程被杀后允许下次事件自然重试。
12. 当 `USE_PROVIDER_BRIDGE=false` 时，保留旧流程：`ClipboardShizukuService` 通过 AIDL callback 回传主进程，主进程再走 `ClipboardService` 读取剪贴板。

## Provider Methods 与返回契约

### `read_clip`

- 输入：`eventId`、`packageName`、`appName`、`iconHash`
- 作用：冷启动 app、读取当前系统剪贴板并入库
- 返回：
  - `resultCode`
  - `saved`
  - `readClip`
  - `overlayAdded`
  - `iconStatus`
- 不再返回 `iconDeferred` 或 `needIconSync`

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

## Provider 失败码与图标决策原因

### 结果码

- `ok`：当前阶段成功完成。
- `invalid_caller`：调用方不是 shell/root，Provider 拒绝处理。
- `invalid_args`：method 或 `eventId` 等参数非法。
- `icon_missing`：`commit_icon` 阶段图标缺失、半文件、decode 失败或 hash 校验失败。
- `overlay_failed`：透明悬浮窗添加失败，无法读取剪贴板。
- `no_clip`：剪贴板为空或没有可保存 item。
- `read_failed`：读取或入库过程出现异常。
- `timeout`：Provider 等待当前阶段完成超时。

### `iconDecisionReason`

- `cache_hit`：数据库图标 hash 命中且文件仍存在。
- `stale_file_missing`：数据库图标 hash 命中但本地图标文件缺失。
- `no_cached_icon`：数据库没有任何图标缓存。
- `hash_changed`：数据库图标 hash 与当前来源图标 hash 不一致。
- `no_icon_available`：当前事件没有可用于同步的图标参数。

## `source_apps` 与备份语义

- `source_apps` 现在承载两类职责：
  - 已保存剪贴记录的来源展示缓存；
  - 由图标链路独立预热的来源图标缓存。
- 因此 `source_apps` 允许先于真实剪贴记录出现，搜索页来源筛选继续直接读取这些数据，这是显式产品预期。
- 本次不新增字段、不改 Room schema、不改 JSONL/zip 备份协议结构。
- 但备份文档要明确：`source_apps` 里可能存在“仅由图标预热链路生成、当前未必对应现存剪贴记录”的来源缓存；恢复后来源筛选允许看到这些来源，这被视为可接受的缓存级可见性，而不是脏数据。

## R8 与隐藏 API 契约

- `AppOpsManagerHidden` 和 `OnOpNotedListener` 属于隐藏 API 编译壳，稳定边界是类名、接口名、成员方法签名和默认方法签名；通过 AndroidX `@Keep` 交给 annotation 自带 consumer rule 保留。
- `ClipboardListener` 属于隐藏 API 回调实现，稳定边界是实现类本身、接口关系和两个 `onOpNoted` 方法签名；当前类已使用 `@Keep`，因此不再需要宽泛 consumer rule。
- `ClipboardShizukuService(Context)` 仍由 Shizuku 反射实例化，稳定边界是服务类名和 `Context` 构造函数；当前构造函数已使用 `@Keep`。

## 日志与诊断计划

- Shizuku 侧继续记录 AppOps 回调、隐藏 API 注册/注销错误、Provider `content call/write` 的 exitCode 和结果码。
- `read_clip` 和图标链路都带同一个 `eventId`，用于串联同一次来源事件。
- 图标链路额外带 `iconSyncKey=packageName#iconHash`，用于定位内存去重是否命中以及后续 `query_icon_state`、`content write`、`commit_icon` 是否真正走完。
- `query_icon_state`、`content write`、`commit_icon` 分别记录 `resultCode`、`iconDecisionReason`、是否命中缓存、是否实际上传、是否复用旧图标。
- 日志禁止输出剪贴板正文、完整用户输入、Token、Cookie、完整 URL 查询串或本地授权 URI；允许保留包名、应用名、图标尺寸、图标字节数、hash、eventId、iconSyncKey、reasonCode 和耗时等低敏信息。

## 测试验证

- 编译验证：
  - `./gradlew :shizuku:compileDebugKotlin`
  - `./gradlew :app:compileDebugKotlin`
- 单元测试：
  - `./gradlew :shizuku:testDebugUnitTest --tests "com.cla.clip.shizuku.*ClipboardBridge*"`
  - `./gradlew :app:testDebugUnitTest --tests "com.cla.clip.master.provider.*"`
  - `./gradlew :base:general:testDebugUnitTest --tests "com.cla.clip.base.general.repository.ClipRepositorySourceAppMergeTest"`
- 静态检查：
  - `git diff --check`
- 人工回归建议：
  - app 存活和杀进程后分别复制其他应用内容，确认 `read_clip` 能先快速入库；
  - 相同来源 hash 命中时，`query_icon_state` 返回 `cache_hit` 且不会再上传 PNG；
  - 图标文件被删掉后，`query_icon_state` 返回 `stale_file_missing`，坏缓存被清空并允许重新补图；
  - 搜索页来源筛选允许出现仅由图标预热链路生成的新来源。

## 已知取舍

- 当前采用 AndroidX `@Keep` 作为统一保留标记，避免在 consumer rules 中逐项保留隐藏 API 壳类型、监听实现和 Shizuku 反射构造函数。
- `ClipboardShizukuService.handleOpNoted()` 每次回调都写一次悬浮窗 AppOps，牺牲一次轻量系统调用，换取跨 Shizuku 进程与主进程检查口径不一致时的稳定性。
- 本次不重构旧 AIDL callback 或 `ClipboardService`，只在 Shizuku 侧通过实验开关新增 Provider 通道；Provider 不可靠时可快速关闭开关回到当前状态。
- Provider 模式下暂不自动 fallback AIDL，原因是验证阶段需要暴露 Provider 真实失败率，避免旧路径兜底掩盖问题。
- 图标同步状态只保存在内存，不落数据库；进程死亡后允许下次事件自然重试。
- `eventId` 只用于日志串联和临时图标文件，不参与来源缓存身份判断；数据库更新仍以 `packageName + Bitmap.toStableHash()` 为准。

## 开放问题

- 如果后续接入更多 AppOps 隐藏 API 回调实现，需要统一检查新增实现类是否已显式 `@Keep`。
- 如果后续调整 Shizuku 服务构造函数签名或新增其他反射实例化入口，需要同步补充 `@Keep` 或精确 consumer rule。
- 如果未来迁移到自定义语义化 keep 注解，需要同步评估 AndroidX annotation consumer rule 的替代规则和 release/R8 验证方式。
- Provider 通道验证通过后，需要单独评估是否将 `USE_PROVIDER_BRIDGE` 改成设置项、远端开关或正式替换策略。

## 变更记录

- 2026-05-27：将 Provider 通道改为“双协程彻底解耦”方案；原因是 `read_clip` 不应再承担图标同步判断，图标链路独立后可以更早进入当前剪贴板读取，并让 `query_icon_state` 与 `commit_icon` 在 app 侧闭环判断来源图标是否需要同步。
- 2026-05-27：Provider 图标传输改为异步补全方案；原因是 `content write`、decode、hash 和保存图标会拉长 Shizuku 回调链路，拆成 `read_clip` 快速入库与 `commit_icon` 后置补图后，可缩短冷启动剪贴保存耗时，并通过 `iconPath + primaryColor + iconHash` 更新触发 UI 图标刷新。
- 2026-05-27：收敛 Shizuku 侧异步图标补全实现；原因是实测日志显示图标先 `content write` 成功但没有继续 `commit_icon` 时，数据库不会更新图标字段，改为 `read_clip` 确认成功后单独协程执行 `content write` 与 `commit_icon`，让验证链路更直观。
- 2026-05-26：新增 Shizuku Provider 回调通道验证方案；原因是旧 `am startservice`/`start-foreground-service` 唤醒主进程会撞后台服务和前台服务限制，Provider 冷启动可能更适合验证主进程被杀后的剪贴板读取。旧 AIDL callback 保留，`USE_PROVIDER_BRIDGE` 开关用于快速回退。
- 2026-05-22：调整悬浮窗权限兜底策略，Shizuku 回调时不再先用 `Settings.canDrawOverlays()` 判断，而是每次直接写入主包名 AppOps；主进程 `ClipboardService` 改为直接尝试添加透明 View，并只在真实失败时提示无悬浮窗权限。原因是 Shizuku 进程与主进程的 uid/package 检查口径不一致时，预检查可能产生假阴性。
- 2026-05-22：将 `ClipboardShizukuService(Context)` 显式 consumer rule 收敛为构造函数 `@Keep` 标注；原因是 Shizuku 反射实例化入口可以由 AndroidX annotation consumer rule 精确保留，`shizuku/consumer-rules.pro` 不再需要维护实际 keep 规则。
- 2026-05-22：新增 Shizuku 剪贴板服务方案文档，并记录 AppOps 隐藏 API consumer rule 收敛为 `@Keep` 注解保留；原因是 `AppOpsManagerHidden`、`OnOpNotedListener` 和 `ClipboardListener` 已分别显式标注 `@Keep`，可以删除重复且更宽泛的三条 ProGuard/R8 规则。
