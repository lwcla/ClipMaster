状态：已完成

# Shizuku 剪贴板服务方案

## 当前状态

Shizuku 模块通过 `ClipboardShizukuService` 在 Shizuku 进程中注册 AppOps 隐藏 API 监听器，用于感知其他应用写入剪贴板后回调主进程。隐藏 API 壳类型位于 `base/hidden-api`，其中 `AppOpsManagerHidden` 和 `AppOpsManagerHidden.OnOpNotedListener` 需要在 release/R8 构建中保持类名、方法签名和接口签名稳定；实际监听实现 `ClipboardListener` 使用具名类并通过 `@Keep` 保留，避免 R8 将隐藏 API 回调改写成不兼容形态。

当前保留策略已经收敛为注解优先：

- `AppOpsManagerHidden` 使用 `@Keep` 保留隐藏 API 壳类和成员。
- `AppOpsManagerHidden.OnOpNotedListener` 使用 `@Keep` 保留接口和回调方法签名。
- `ClipboardListener` 使用 `@Keep` 保留唯一实现类和成员。
- `ClipboardShizukuService(Context)` 构造函数使用 `@Keep` 保留 Shizuku 反射实例化入口。
- `shizuku/consumer-rules.pro` 不再保留显式 keep 规则，只作为空规则文件保留给 Gradle 配置引用。
- Shizuku 侧在每次 AppOps 回调时直接对主包名执行 `setMode(OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName, MODE_ALLOWED)`，不再依赖 Shizuku 进程里的 `Settings.canDrawOverlays()` 判断；主进程 `ClipboardService` 直接尝试添加 1x1 透明 View，并只在真实添加失败时提示悬浮窗权限问题。

## 目标

- 保持 Shizuku 剪贴板监听在 debug 和 release/R8 构建下都能按隐藏 API 签名注册、回调和注销。
- 避免在 consumer rules 中重复维护已经由 `@Keep` 覆盖的隐藏 API 壳类、监听实现和 Shizuku 反射构造函数规则。
- 避免 Shizuku 进程和主进程对 `Settings.canDrawOverlays()` 的 uid/package 检查口径不一致时误判为无悬浮窗权限。
- 将隐藏 API、反射实例化和 R8 保留边界集中记录，方便后续修改时按职责补充注解或规则。

## 范围

- `base/hidden-api/src/main/java/android/app/AppOpsManagerHidden.java`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardListener.kt`
- `shizuku/src/main/java/com/cla/clip/shizuku/ClipboardShizukuService.kt`
- `shizuku/consumer-rules.pro`
- `docs/shizuku_clipboard_service_plan.md`

不包含：

- 主进程剪贴板入库、通知、数据库或列表展示逻辑。
- Shizuku 权限申请 UI 和“我的”页设置项结构。
- AppOps 隐藏 API 的新增能力扩展。

## 用户体验

开启 Shizuku 能力后，服务仍在 Shizuku 进程监听剪贴板写入事件，并在主进程可用时回传来源应用信息。对用户可见的变化是：当 Shizuku 已经能写入悬浮窗 AppOps 允许状态时，主进程不会再因为 `Settings.canDrawOverlays()` 假阴性提前弹出“没有悬浮窗权限”；只有真实添加透明 View 失败时才提示用户检查悬浮窗权限。

## 数据流

1. 主进程连接 Shizuku 并实例化 `ClipboardShizukuService(Context)`。
2. `ClipboardShizukuService.startListener()` 通过 `HiddenApiBypass` 和 `Refine.unsafeCast<AppOpsManagerHidden>` 调用隐藏 API。
3. 服务创建 `ClipboardListener(packageName, owner)` 并注册到 `startWatchingNoted(intArrayOf(30), listener)`。
4. `ClipboardListener` 收到字符串 op 或数字 code 回调后过滤自身包名，再调用 `owner.handleOpNoted(clipPackageName)`。
5. `ClipboardShizukuService` 每次回调都对主包名执行 `setMode(OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName, MODE_ALLOWED)`，避免旧状态、系统回收或跨进程检查口径导致主进程读剪贴板前被误拦截。
6. `ClipboardShizukuService` 读取来源应用信息并通过 AIDL callback 回传主进程。
7. 主进程 `ClipboardService` 不提前调用 `Settings.canDrawOverlays()` 阻断流程，而是直接添加透明 View；如果系统真实拒绝添加 View，则记录异常并提示悬浮窗权限问题。
8. 销毁或重连时，服务使用当前 listener 调用 `stopWatchingNoted(listener)` 并清理协程。

## R8 与隐藏 API 契约

- `AppOpsManagerHidden` 和 `OnOpNotedListener` 属于隐藏 API 编译壳，稳定边界是类名、接口名、成员方法签名和默认方法签名；通过 AndroidX `@Keep` 交给 annotation 自带 consumer rule 保留。
- `ClipboardListener` 属于隐藏 API 回调实现，稳定边界是实现类本身、接口关系和两个 `onOpNoted` 方法签名；当前类已使用 `@Keep`，因此不再需要 `-keep class * implements android.app.AppOpsManagerHidden$OnOpNotedListener { *; }` 这种宽泛规则。
- 如果后续新增其他 `OnOpNotedListener` 实现类，新增实现也必须显式标注 `@Keep` 或补充更精确的 consumer rule，不能依赖已经删除的通配实现规则。
- `ClipboardShizukuService(Context)` 仍由 Shizuku 反射实例化，稳定边界是服务类名和 `Context` 构造函数；当前构造函数已使用 `@Keep`，不再需要 `shizuku/consumer-rules.pro` 中的显式 keep 规则。

## 日志与诊断计划

- 本次保留既有运行时日志：Shizuku 服务继续记录 AppOps 回调、前台服务拉起、callback 投递和隐藏 API 注册/注销错误；主进程 `ClipboardService` 继续记录透明 View 添加、剪贴板读取和异常分支。
- 悬浮窗权限提示从预检查失败改为真实添加 View 失败时触发；该分支记录 `logE(TAG, it) { "读取剪贴板内容出错" }`，并在 `SecurityException` 或 `WindowManager.BadTokenException` 时提示用户。
- 隐藏 API 注册失败、注销失败、主进程回调失败等运行时诊断继续由 `ClipboardShizukuService` 现有日志承担。
- release/R8 诊断以构建验证为主：执行 `./gradlew :app:minifyReleaseWithR8`，确认 `@Keep` 注解和 AndroidX annotation consumer rule 能覆盖隐藏 API 壳类、接口、监听实现和 Shizuku 反射构造函数。
- 日志禁止输出剪贴板正文、完整用户输入、Token、Cookie、完整 URL 查询串或本地授权 URI；允许保留包名、应用名、图标尺寸、哈希和 reasonCode 等低敏信息。

## 测试验证

- 编译验证：运行 `./gradlew :shizuku:compileDebugKotlin`。
- 主进程编译验证：运行 `./gradlew :app:compileDebugKotlin`。
- release/R8 验证：运行 `./gradlew :app:minifyReleaseWithR8`，确认删除显式 consumer rule 后混淆链路通过。
- 静态检查：运行 `git diff --check`。
- 人工回归建议：release 包开启 Shizuku 后，复制其他应用内容，确认列表能新增记录且来源应用正常显示；关闭或重连 Shizuku 后确认旧监听不会继续回调。

## 已知取舍

- 当前采用 AndroidX `@Keep` 作为统一保留标记，避免在 consumer rules 中逐项保留隐藏 API 壳类型、监听实现和 Shizuku 反射构造函数。
- `shizuku/consumer-rules.pro` 暂不删除文件本身，因为 Gradle 仍引用该文件；当前只保留中文说明，避免空文件让后续维护者误以为遗漏规则。
- `ClipboardShizukuService.handleOpNoted()` 每次回调都写一次悬浮窗 AppOps，牺牲一次轻量系统调用，换取跨 Shizuku 进程与主进程检查口径不一致时的稳定性。
- 本次不拆分 `ClipboardShizukuService` 或 `ClipboardService`，因为改动集中在权限兜底和 R8 保留边界；服务内部职责拆分应作为单独任务评估。

## 开放问题

- 如果后续接入更多 AppOps 隐藏 API 回调实现，需要统一检查新增实现类是否已显式 `@Keep`。
- 如果后续调整 Shizuku 服务构造函数签名或新增其他反射实例化入口，需要同步补充 `@Keep` 或精确 consumer rule。
- 如果未来迁移到自定义语义化 keep 注解，需要同步评估 AndroidX annotation consumer rule 的替代规则和 release/R8 验证方式。

## 变更记录

- 2026-05-22：调整悬浮窗权限兜底策略，Shizuku 回调时不再先用 `Settings.canDrawOverlays()` 判断，而是每次直接写入主包名 AppOps；主进程 `ClipboardService` 改为直接尝试添加透明 View，并只在真实失败时提示无悬浮窗权限。原因是 Shizuku 进程与主进程的 uid/package 检查口径不一致时，预检查可能产生假阴性。
- 2026-05-22：将 `ClipboardShizukuService(Context)` 显式 consumer rule 收敛为构造函数 `@Keep` 标注；原因是 Shizuku 反射实例化入口可以由 AndroidX annotation consumer rule 精确保留，`shizuku/consumer-rules.pro` 不再需要维护实际 keep 规则。
- 2026-05-22：新增 Shizuku 剪贴板服务方案文档，并记录 AppOps 隐藏 API consumer rule 收敛为 `@Keep` 注解保留；原因是 `AppOpsManagerHidden`、`OnOpNotedListener` 和 `ClipboardListener` 已分别显式标注 `@Keep`，可以删除重复且更宽泛的三条 ProGuard/R8 规则。
