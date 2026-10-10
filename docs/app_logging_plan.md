状态：已完成

# 应用日志保存与移除方案

## 移除本地日志保存（2026-10-10，已完成）

### 当前状态、目标与已确认范围

用户反馈 WebDAV 备份已经正常，要求去掉保存日志到本地的功能，并已确认按本方案修改代码。本轮在当前工作区实施，不创建提交、不安装应用或上传真实数据；下方 2026-10-08 章节保留旧实现与当时验证，不能作为本轮移除功能已完成的证据。

移除前有两条独立的日志落盘链路：

| 链路 | 移除前行为 | 本轮处理 |
| --- | --- | --- |
| 应用进程日志录制 | Application 启动时开启各级日志，再由专用线程读取本 PID 的 Logcat，分卷写入 `Download/ClipMaster/logs` | 删除自动采集、公共目录写入和分卷实现 |
| 备份私有诊断留证 | 备份阶段事件原子写入 `noBackupFilesDir/backup_diagnostics/events.log`，最多 200 条且不超过 64 KiB，同时输出 Logcat | 删除文件留证，沿用现有事件入口输出常规 Logcat |

用户已明确选择“全部停止日志落盘，保留常规 Logcat”，因此两条日志落盘链路均纳入本次移除范围。常规 Logcat、最近备份成功/失败状态、失败原因提示和 WorkManager 运行态继续保留；这些业务状态用于备份与界面，不属于被移除的文本日志。备份 ZIP、manifest、本地备份目录发布和 WebDAV 上传也继续保留。

完整方案已获用户批准，代码、退役测试和文档已调整，最终源码验证与独立只读复核通过；设备边界单独列为后续验收，不以编译或 JVM 测试代替。

### 方案选择与取舍

1. 已选择完整移除两条日志落盘链路：停止长期采集和日志文件 IO，清除仅为开发留证存在的代码；复用 Logcat 与备份事件结构，保持业务接口稳定。
2. 仅删除公共 Download 录制：改动最小，但私有备份日志仍会写盘，未采用，因为用户明确要求全部停止日志落盘。
3. 仅关闭启动调用或增加录制开关：不采用。该开发功能已经完成用途，保留无调用实现、分卷测试或新增设置会增加维护负担；本次也没有继续提供日志导出的需求。

停止文件留证后，Logcat 仅提供系统有限缓冲内且实际输出的日志，不能承诺跨重启或事后完整追溯。备份最近结果与稳定失败 code 仍可用于界面提示；系统停止分类继续由现有 `BackupStopReasonMapper` 提供。

### 复用检查与职责边界

已按 app/logging、app 备份诊断 package、app 启动/Worker/页面、base/general 日志与备份能力、全仓引用的顺序检查。公共录制的四个类只有本链路使用，可整体删除；`enableDevelopmentLogRecording()` 只有 Application 调用，可一并退役。

保留 `BackupDiagnostics.start(...)`、`stopReason(worker)` 和 `record(record)` 的调用契约，继续由既有 `BackupDiagnosticTrace` 组织阶段与单调时钟耗时。`BackupDiagnostics` 收敛为即时日志适配器，去掉 Context 注入、AtomicFile、journal 和文件读取；Worker 与 `BackupExecutionCoordinator` 不新增日志 IO、不改调度或上传分支。`BackupDiagnosticPolicy.encode(...)` 的字段白名单仍用于 Logcat，删除其中仅服务文件保留的规则。不新增日志框架、通用工具类、设置项或页面组件。

### 涉及文件与组件、类、方法

下表路径以仓库根目录为基准，记录本轮代码改动。

| 文件 | 实现改动 |
| --- | --- |
| `app/src/main/java/com/cla/clip/master/App.kt` | 删除录制器和开发日志开关的 import；删除仅承载这两个调用的 `attachBaseContext` 覆写及无用 Context import |
| `app/src/main/java/com/cla/clip/master/logging/RuntimeLogcatRecorder.kt` | 删除独立录制线程与 Logcat 子进程实现 |
| `app/src/main/java/com/cla/clip/master/logging/DownloadLogStore.kt` | 删除日志专用 MediaStore/旧存储 API 写入实现 |
| `app/src/main/java/com/cla/clip/master/logging/RollingLogOutput.kt` | 删除日志专用 UTF-8 分卷写入器 |
| `app/src/main/java/com/cla/clip/master/logging/AppLogRecordingPolicy.kt` | 删除采集命令、公共目录和日志文件名规则 |
| `base/general/src/main/java/com/cla/clip/base/general/utils/Logcat.kt` | 删除 `developmentRecording` 和 `enableDevelopmentLogRecording()`；`loggable(level)` 恢复为 `BuildConfig.DEBUG` 或系统 `Log.isLoggable` 控制，保留现有各级日志方法 |
| `app/src/main/java/com/cla/clip/master/backup/diagnostics/BackupDiagnostics.kt` | 构造函数去掉 Context 参数；删除 AtomicFile、journal、`readBytesBounded()` 和保存失败分支，`record(...)` 仅输出已有级别的 Logcat |
| `app/src/main/java/com/cla/clip/master/backup/diagnostics/BackupDiagnosticJournal.kt` | 删除诊断文件读写及并发追加实现 |
| `app/src/main/java/com/cla/clip/master/backup/diagnostics/BackupDiagnosticPolicy.kt` | 保留事件结构和 `encode(...)` 脱敏；删除 `maxRecords`、`maxBytes`、`append(...)`，更新文件保留相关注释 |
| `app/src/main/java/com/cla/clip/master/backup/diagnostics/BackupDiagnosticTrace.kt` | 更新回调与阶段注释，明确即时输出边界，不再宣称硬杀后能够从本地文件留证 |
| `app/src/main/java/com/cla/clip/master/work/BackupAutoWorker.kt`、`app/src/main/java/com/cla/clip/master/work/BackupWebDavUploadWorker.kt`、`app/src/main/java/com/cla/clip/master/backup/BackupExecutionCoordinator.kt` | 仅更新诊断字段和取消边界的注释，不再宣称有限文件 IO 或正式包历史留证；执行逻辑保持 |
| `app/src/test/java/com/cla/clip/master/logging/RollingLogOutputTest.kt`、`app/src/test/java/com/cla/clip/master/logging/AppLogRecordingPolicyTest.kt` | 随对应生产实现删除 12 项退役功能测试 |
| `app/src/test/java/com/cla/clip/master/backup/diagnostics/BackupDiagnosticsTest.kt` | 删除文件保留与 journal 故障/追加/并发测试，保留停止分类、字段脱敏和 trace 耗时/阶段测试，清理失效 import 与注释 |
| `docs/app_logging_plan.md` | 维护本主方案、日志边界、测试与实施状态 |
| `docs/webdav_backup_plan.md` | 简短引用本方案，说明备份自身的诊断接入影响和业务契约边界 |

本轮修改的方法、构造函数、变量和关键分支均维护贴近代码的简体中文注释。无新增共享 UI 契约、序列化模型、Room/schema 或 keep 规则。

### 数据流、生命周期与历史文件

移除后的数据流为：业务事件 → 既有统一日志方法 → Android Logcat；备份额外通过既有 trace/白名单格式化器输出阶段、停止原因和耗时。Application 不再创建日志录制线程或 Logcat 子进程，前台、后台和进程重建均不启动文件录制；备份 Worker 的生命周期与任务取消语义保持现状。

本轮只停止后续写入，不自动删除历史日志。公共 `Download/ClipMaster/logs` 文件由用户通过文件管理器管理；私有 `backup_diagnostics` 文件不再读取或更新，清除应用数据/卸载时由系统处理。这样无需在 Application 增加迁移扫描、MediaStore 删除、权限申请或新的启动 IO。历史日志占用不会因升级自动释放，这是明确取舍；若另有清理要求，再限定公共与私有文件范围设计一次性清理。

日志使用的旧系统存储权限与媒体保存能力共享，不随日志移除删除 `WRITE_EXTERNAL_STORAGE`，也不删除媒体发布代码；没有为该录制器单独申请的 `READ_LOGS` 权限或 WorkManager 任务需要取消。

### 备份覆盖与兼容性

公共日志和私有诊断原本都不进入自定义备份；日志移除不改变 `BackupSnapshot`、manifest/checksum、schemaVersion、字段白名单、dirty 标记或恢复合并规则，也不需要数据迁移。私有历史文件继续位于 noBackupFilesDir，公开历史文件仍位于公共 Download，不为它们增加系统 Auto Backup 条目。移除诊断文件不会影响最近目标结果的持久化，也不会清除备份产物。

相关类仅为编译期内部调用，不新增反射或外部协议依赖，不新增反混淆规则。由于 Application 和 Hilt 构造入口调整，交付正式安装包前仍安排 Release/R8 构建回归。

### 日志与诊断计划

不新增日志点或日志存储，沿用当前统一 TAG `cla_clip_master` 及既有业务日志级别。录制开始/录制失败/文件保存失败日志将随录制与文件留证功能一起移除。

| 级别 | 触发时机 | 字段与边界 |
| --- | --- | --- |
| INFO | 备份开始、阶段进入、目标完成或跳过 | 既有工作身份、产物/轮次低敏身份、source、phase、event、耗时、attempt；Worker 成功不能解释为远端成功 |
| WARN | 当次停止、中断或可恢复清理失败 | stopReason、固定 reasonCode、异常类型和阶段，不拼接异常 message |
| ERROR | 既有生成、上传或 Worker 失败 | 稳定失败 code、异常类型及既有低敏上下文，沿用当前业务事件分类 |

保留 `BackupDiagnosticPolicy.encode(...)` 的有限 token 白名单与非法自由文本整体脱敏，不输出 WebDAV 地址、账号、密码、Token、Cookie、完整 URI/路径、异常 message、剪贴正文、请求/响应体或 ZIP 内容。停止强制开发日志开关后，正式包的 V/D 及其他级别仍取决于系统日志许可，不能宣称正式包各级日志始终输出；Debug 和按需 ADB 日志设置可用于后续诊断。高频与逐字节进度日志不新增。

验证白名单与停止分类继续使用合成 JVM fixture；“不创建/更新日志文件、不启动录制线程或子进程”通过源码引用检查和设备观测验证，不以日志正文或截图中的业务内容作为证据。

### 实施步骤与单元测试检查

按 `$repo-unit-test-gate`，本次主要删除退役能力，不新增纯业务规则，也不为类名消失编写源码快照式测试。文件存储/分卷契约消失后，其测试应同步删除；保留有效的脱敏、停止分类、trace、失败提示与备份结果回归。Application/MediaStore/Hilt 的系统行为以编译及设备观测验证。本次不为删除实现强行采用失败测试先行；若实施时引入新的判断、日志级别规则或清理策略，须先写能失败的规则测试再实现，不能沿用本次例外。

- [x] 任务一：移除 Application 的自动录制接入和四个录制类，恢复统一日志原有门控；删除两份录制测试。检查剩余代码不存在录制入口或开发强制输出开关。
- [x] 任务二：保留备份诊断即时输出接口，删除私有文件读写和保留规则。核对 Hilt 注入、三个 Worker 与协调器的调用兼容性；保留稳定停止分类与失败提示。
- [x] 任务三：调整 `BackupDiagnosticsTest`，删除已退役的四项持久化测试，保留 `distinguishesKnownStopsAndKeepsUnknownInterruption`、`encodesSafeFieldsAndRejectsFreeText`、`traceKeepsPhaseAndUsesMonotonicExecutionTime`。检查脱敏与阶段/耗时断言仍覆盖有效契约。
- [x] 任务四：运行受影响模块回归与编译，并完成 Release/R8。最终 Gradle 输出 `BUILD SUCCESSFUL`，测试无失败，`git diff --check` 无错误；实际完整命令与数量见下方验证记录。
- [ ] 设备后续验收：检查全新安装和带历史日志的升级安装，覆盖启动/重启、前后台切换及既有备份执行；确认公共日志无新卷、旧卷和私有 events.log 内容/修改时间不再变化，不出现录制线程/子进程，备份最近结果与失败提示可正常展示。真实 WebDAV 使用合成内容或明确授权的设备数据，不在测试源码或输出中泄露配置。本轮只检查设备连接，没有安装或触发备份，不以 JVM/构建替代此项。
- [x] 任务六：已记录最终源码验证、设备后续验收与取舍，并同步两份主文档状态。已完成表示本轮代码与构建验证完成，设备安装及实际后台行为仍为后续验收。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*Backup*' :app:compileDebugKotlin --console=plain
.\gradlew.bat :base:general:testDebugUnitTest --tests '*Backup*' --tests '*WebDavDirectUploadTest' :base:general:compileDebugKotlin --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
git diff --check
```

本轮执行上述构建验证，不安装应用、触发真实备份或网络上传。测试分层与长期必测范围没有改变，测试细则在本功能主方案维护，不重复修改 `docs/unit_test_plan.md`。

### 最终实现与验证记录（2026-10-10）

公共录制与私有诊断文件读写已完整删除；全仓 app/base 源码检查未发现录制类、开发强制开关、journal、日志目录或文件保留规则的剩余引用。保留 `BackupDiagnostics` 的空参 Hilt 注入与既有方法、trace、停止分类、字段白名单和业务结果。调用方只补充了即时日志/取消边界的注释，没有改变执行代码；修改处方法、字段、变量和关键分支均有简体中文注释。

修改前先运行 4 组日志/备份诊断基线，共 20 项测试通过。删除 12 项公共录制与 4 项私有文件留证测试后，`BackupDiagnosticsTest` 保留 3 项有效回归。共享日志门控会影响各模块调用方，本轮合并运行 app/base 的完整单测与构建，避免仅按备份筛选遗漏其他调用方的编译/测试影响：

```powershell
.\gradlew.bat :app:testDebugUnitTest :base:general:testDebugUnitTest :app:compileDebugKotlin :base:general:compileDebugKotlin :app:assembleRelease --console=plain
git diff --check
```

最终源码验证 `BUILD SUCCESSFUL`；app 280 项、base/general 82 项测试共 362 项全部通过，无失败、错误或跳过。Debug 编译、Hilt、Release/R8、lint 与正式 APK 打包通过，mapping 保留在 `app/build/outputs/mapping/release/mapping.txt`，安装包为 `app/build/outputs/apk/release/ClipMaster-v0.7.3-admob.apk`。首轮之后仅补齐调用方的旧文件留证注释，并对最终源码重跑同一完整命令；不是沿用修改前的构建结论。

初次 Gradle wrapper 无法写现有缓存锁，使用获准的既有缓存权限后基线及两轮验证均成功；不改仓库构建配置。仅有既有 Gradle/Kotlin 废弃或注解警告、Shizuku `OnOpNotedListener` 的 R8 警告，本轮未改相关代码。独立只读复核未发现 Critical/Important 代码问题，指出的草案状态与尚未实施措辞已在本轮文档收尾修正，`git diff --check` 通过。

本轮在当前 main 工作区就地完成，没有创建提交或回退其他改动。按已批准的选择性 TDD 删除退役能力，不新增纯规则测试，不保留已失效测试；有效规则由既有测试覆盖，Android 文件/进程行为仍需设备验收。没有新增日志点、设置、备份字段、Room/schema 或 keep 规则。ADB 已只读确认有连接设备，本次未安装 APK 或触发真实备份/上传；历史文件按方案保留，不因源码修改自动清除。

### 开放问题与后续待办

- 两条日志落盘链路已移除；最终验证记录见收尾章节，设备行为单独作为后续验收。
- 推荐保留历史文件，避免为退役功能增加启动删除流程；用户若需要腾出旧公共日志空间，应另行明确删除范围。
- 移除后放弃跨重启的阶段文件留证。后续若出现难以复现的后台问题，再按具体需求设计临时诊断，不提前保留无调用的录制框架。

## 开发阶段应用日志自动保存（2026-10-08，历史已完成）

以下记录描述已经退役的开发录制实现与当时验证；本次实际行为、范围和验收以上方移除章节为准。

### 历史目标与确认范围

2026-10-08 用户明确要求将应用运行日志直接自动输出到系统 Download，以便没有 root 的手机取出分析；当前为个人开发使用，不增加导出按钮或隐私确认。范围由仅备份诊断扩展为应用进程已经输出的各级 Logcat 日志及异常堆栈。既有日志内容不另外裁剪或脱敏，不新增凭据/用户内容日志字段；统一日志入口的 V/D 输出开启，正式包也生效。Android 系统其他进程、独立 Shizuku 服务及未实际输出的第三方日志不属于当前应用 PID，不能宣称录到了全系统日志。

### 历史复用与职责

已依次检查备份 diagnostics、app 文件与日志能力、base/general Logcat 与 SaveToFile，以及全仓原生日志调用。统一入口可复用，但广告、搜索等模块还有直接 android.util.Log 调用，只扩展入口会遗漏。采用应用自己 UID/PID 的 logcat 流，不增加 READ_LOGS、root 或模块反向依赖。[AOSP 读取边界](https://android.googlesource.com/platform/system/logging/+/refs/heads/main-16k-with-phones/logd/LogReader.cpp)明确普通客户端只访问自己的日志；仍需设备确认厂商行为。

录制器放在 app/logging，Application 的 attachBaseContext 仅启动独立后台线程。纯分卷写入器负责字节阈值、行顺序与关闭，Download 写入器负责 MediaStore/旧存储 API。既有 SaveToFile 仅支持图片/视频及相册目录，不扩展为日志工具。备份私有原子诊断保留，新的日志包含其阶段输出；不改变生成、上传、重试及调度策略。

### 历史文件、生命周期与失败契约

- 目录固定为 Download/ClipMaster/logs；文件按手机启动时间、PID 和随机会话身份命名，每卷为文本文件。每个进程只初始化一次，后台录制覆盖前台和后台存活期，无页面轮询或额外 WorkManager/前台服务。
- 采集 main/system/crash 缓冲区，PID 精确过滤，采用 threadtime/year 格式，启动时间稍前的回读覆盖初始化期间日志。统一入口不再因 Release 默认关闭 V/D 而遗漏开发诊断。
- 单文件以 UTF-8 字节计达到 10 MiB 后分卷，跨卷不删除记录或旧文件；单个超长行可超过阈值，以完整保存内容。历史文件由用户在系统文件管理器管理，不承诺无限磁盘容量。
- Android 10+ 使用 MediaStore.Downloads，不需要额外存储权限；Android 9 及以下仅在已有 WRITE_EXTERNAL_STORAGE 权限时写公共 Download，不从后台弹权限。参考 [Android 文件存储说明](https://developer.android.com/training/data-storage/shared/media)。文件创建使用 pending 标记，流打开后发布；不长期隐藏仍在记录的文件。
- 连续流使用专用线程，无日志时阻塞等待而非定时轮询。每行写入后 flush；reader 退出、打开/写入失败或读取返回后发现线程中断时关闭流并销毁子进程。阻塞 readLine 本身不会被普通 interrupt 立即解除，当前不提供独立停止接口；未来需要停止时应先销毁子进程解除阻塞。没有新增保活；硬杀和系统日志环形缓冲覆盖仍可能丢失末尾未读取内容，不能保证全量审计。
- 创建、读取或写入失败仅停止本轮录制，固定警告使用原生 Logcat；不抛到 Application/Worker，不修改备份结果，不自动重启高频失败循环。重启应用会重新尝试。

### 历史日志与诊断计划

录制开始 INFO，字段为 SDK/PID、应用版本和分卷身份；启动/文件/读取失败 WARN，仅输出固定 reasonCode 与异常类型。录制器不打印采集到的正文，避免自反馈循环。写入的正文来自既有应用 Logcat，按用户要求保留原内容及堆栈；不额外新增密码、Token、Cookie、剪贴内容或网络响应日志。开发用途的公共记录由用户直接在文件管理器或 USB/MTP 读取。

### 历史备份、契约与验证

公开日志是运行态文件，不加入自定义备份白名单，不改 Room/schema/设置或用户数据；系统应用 Auto Backup 不扫描公共 Download。卸载后公开文件可能保留，重装开启新会话，不复用或覆盖旧文件。纯分卷对象和命令配置仅编译期调用，无新增反射/序列化模型或 keep 规则；保留正式包 mapping 用于堆栈回溯。

采用测试先行：保护 UTF-8 分卷、完整行、行顺序、流关闭及输出异常；同时核对命令 PID/各级输出及固定文件目录。运行 app 相关 testDebugUnitTest、compileDebugKotlin、assembleRelease/R8 和 git diff --check。MediaStore 可见性、无 root 自身 Logcat 读取及系统硬杀属于 Android 真机边界，JVM 不能验收。

2026-10-08 验证完成：首轮 9 项规则测试按预期失败，实现后通过；独立复核未发现 Critical/Important 问题，已修正阻塞读取中断说明并增加 write/flush/close 故障回归。最终新增 12 项日志测试与既有 8 项备份诊断/提示测试共 20 项，无失败、错误或跳过。运行 `.\gradlew.bat :app:testDebugUnitTest --tests '*RollingLogOutputTest' --tests '*AppLogRecordingPolicyTest' --tests '*BackupDiagnosticsTest' --tests '*BackupFailureMessageMapperTest' :app:compileDebugKotlin :app:assembleRelease --console=plain` 完成编译、测试和 Release/R8；补齐故障测试后重跑同一测试筛选及 compileDebugKotlin，BUILD SUCCESSFUL。git diff --check 通过。只存在既有 Gradle 废弃配置及 Shizuku 接口相关警告。

安装包为 `app/build/outputs/apk/release/ClipMaster-v0.7.2-admob.apk`，生成时间 2026-10-08 15:32:01，签名核验通过且与既有设备签名一致；APK SHA-256 为 `A423FB8FBA7A0AB447C895C8FDE5F243439DB1F2B2C13E19F2B2688A8B1C17F2`。mapping 保留在 `app/build/outputs/mapping/release/mapping.txt`。未安装到手机、触发备份或上传，不把编译/纯测试结果宣称为设备录制验收。新增/修改的方法、变量及关键分支均有简体中文注释，无未拆分或未补纯规则测试的临时例外；Android 平台访问仍待真机验收。

### 历史后续待办

安装后只查看文件生成与内容，不触发备份或上传；后续实际后台任务用于定位 WebDAV 停止原因。厂商是否允许自己的 logcat、旧系统存储权限与末尾丢失边界需真机核对；未来公开发布前另评估开发日志开关和磁盘保留策略。

## 变更记录

- 2026-10-10：完成两条日志落盘链路、Application 接入、强制门控、分卷/journal/保留规则和 16 项失效测试的移除，保留即时日志、3 项诊断规则回归及全部业务契约；补正 Worker/协调器失效注释。最终 362 项完整单测、Debug、Release/R8、lint、正式打包及独立只读复核通过，方案标记已完成；设备未安装/上传、历史文件保留明确作为后续边界。
- 2026-10-10：用户批准按完整方案修改代码，状态改为实现中；在当前工作区保留既有方案改动并继续实施。使用仓库选择性 TDD 判断，删除退役能力和失效测试，不为删除动作引入新的规则或源码快照测试；保留停止分类、脱敏、trace 和业务结果回归，系统行为留待设备验证。
- 2026-10-10：根据用户反馈 WebDAV 备份已正常，编写移除本地日志保存草案；用户进一步确认“全部停止日志落盘，保留常规 Logcat”，纳入公共应用录制与私有备份文件留证，保留停止分类和备份结果，明确历史文件、测试退役及设备验收边界。尚未实施，旧实现和验证记录保留为历史。
- 2026-10-08：按用户澄清改为全应用进程日志自动写公共 Download，而非手动导出或仅同步 200 条备份摘要；通过独立录制器覆盖分散原生日志，避免逐模块改造与主线程 IO。
- 2026-10-08：完成录制器、公共目录写入、UTF-8 分卷及 Application 早期接入；12 项新测试、20 项相关回归、Debug 编译、Release/R8、签名及独立复核通过。修正文档的中断退出边界，新增输出故障回归；开发自动录制已可交付，真机文件读取仍待验证。
