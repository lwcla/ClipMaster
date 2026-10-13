状态：已完成

# 应用日志保存与移除方案

## 开关控制 Logcat 直接保存与大小轮转（2026-10-13，已完成）

### 当前状态、已确认需求与范围

本节状态：已完成，用户已授权开始执行。用户撤销日期目录和小时文件要求，改为按大小限制；已通过选择明确每文件 10 MiB、保留 20 个历史文件，另有一个当前文件，总占用约 210 MiB，达到保留数量后淘汰最旧文件。保留“我的”页保存开关、Download 固定目录、本应用实际输出的 SDK/原生/业务日志范围；不要求主进程结束后继续保存。顶部状态表示本轮源码实现与本地验证已完成；2026-10-10 移除方案保留为历史，当前按新的用户开关接入直接保存。

当前方案是 `ProcessBuilder` 启动 Android 的 `logcat` 命令子进程，直接使用 `-f` 写入 `Download/ClipMaster/logs/` 中的当前日志，使用 `-r 10240 -n 20` 进行原生大小轮转。应用只负责设置、路径/归属准备、进程启停和必要的 stderr/退出状态监测，不读取每条业务日志再转存，不引入应用级队列、背压协议、小时切换任务或额外文件 sink。无需新增独立 Android Service 进程、前台通知、WorkManager 或保活。

### 文件组织、轮转与删除授权

- 固定目录为 `Download/ClipMaster/logs/`，取消每日子目录和每小时文件。建议基础文件名 `clipmaster.txt`，历史文件由 logcat 生成编号后缀；20 份历史在已核对 AOSP 实现中为 `clipmaster.txt.01` 至 `clipmaster.txt.20`，01 是最近轮转，20 是最旧，当前文件无编号。
- `-r` 单位是 KiB，10240 KiB 即 10 MiB；`-n 20` 表示 20 个历史文件，不包含当前文件。正常情况下最多 21 份受控文件，总量约 210 MiB。轮转是在完整日志记录写入后判断，单文件可能略超过阈值，不把此数字当成严格字节硬上限。
- 当前文件达到大小阈值后由同一个 logcat 完成关闭、历史编号重命名及当前文件重开，无需应用定时器或重启采集。重复开关和主进程重启后使用同一受控基础文件追加，不按会话/日期创建新的文件集合。
- 用户已明确选择历史数量与淘汰行为，删除授权仅涵盖本方案管理的当前安装基础文件及其固定 20 份编号日志；不扫描删除历史采集器的会话日志、旧日期目录、其他文件或其他安装归属的条目。重装后归属无法确认或同名冲突时显示存储冲突，不擅自覆盖或删除。
- 参数必须显式提供 `-n 20`，不依赖默认 4 份，不用 `-n 0` 假定无限保留；当前核对的 CLI 会拒绝 0。禁止通过 `-c` 或 `--id` 顺手清除历史文件。

依据：[AOSP logcat 参数、追加及轮转实现](https://android.googlesource.com/platform/system/logging/+/refs/heads/main/logcat/logcat.cpp)。普通大小轮转与历史淘汰是不同于“未读取日志丢失”的行为；21 份数量与约 210 MiB 只描述本方案受控文件，不包括原有历史文件，也不作为磁盘故障后的强保证。

### 日志覆盖、权限与存储兼容

采集当前应用主进程实际输出到 Logcat 的各级日志，包括统一封装、原生 android.util.Log、SDK 和 NDK Android 日志接口；按父应用 PID 过滤，不按统一 TAG 过滤，明确选择 main/system/crash 缓冲区。不能获取未执行的 DEBUG 分支、未输出到 Logcat 的 SDK 诊断或系统其他进程/独立渲染进程/crash_dump 的完整输出。[Logcat 官方说明](https://developer.android.com/tools/logcat?hl=en)。

不新增 root、Shizuku 读取或 READ_LOGS 特权；普通客户端读取本 UID 自身日志，并进一步用父应用 PID 限定范围。最低支持版本与厂商行为需真机验证。[AOSP 读取权限](https://android.googlesource.com/platform/system/logging/+/refs/heads/main/logd/LogReader.cpp)。

开启期间建议让统一日志封装放行自有 V/D，关闭后恢复原门控；统一日志方法仅接入采集许可，不写文件。编译或 SDK 自身级别过滤发生在采集之前，命令 `*:V` 无法补出从未进入 logd 的记录。采集起点限定本次开启时刻，初始化缓冲只在有界会话范围处理，不做无界历史回读或每次重启重复保存。

`-f` 接收普通文件路径，不能直接使用 MediaStore content URI；原生轮转还要求同目录创建、追加、重命名和替换受控文件的权限。Android 10+ 最终采用固定 Download 路径与 noBackup 安装归属标记准备，不把 content URI 作为写入目标，也不预登记 21 个 MediaStore 文件。由原生 logcat 创建/追加/轮转；其共享存储兼容性通过设备证据确认，失败时显式停止。不能仅因 ContentResolver 能打开一个 URI 就推断原生 rename 一定可用，也不能一概宣称所有 Android 版本均支持或禁止直接路径。Android 7–9 需已获得旧存储写权限，权限动作只能在前台开关流程发起。依据：[Android 共享存储说明](https://developer.android.com/training/data-storage/shared/media)。

实施前优先以合成日志和较小阈值验证真正目标设备的直接创建/追加/轮转、Download 可见性及重启。若直接路径在受支持系统失败，显式报告存储不可用，先根据证据调整平台方案；不悄悄恢复队列或增加所有文件访问权限。平台兼容是实施验证项，不是当前设计已经验收的事实。

### 复用、组件职责与我的页接入

已完成对 Mine 页面、共享设置 UI、app/logging 历史、base/general 日志和 DataStore 及全仓原生日志的复用检查。复用 `SettingSwitchEntryCard`、HostSettingsStore/HostSettingsKeys、Application 主进程检查、应用级作用域和既有日志 API。窄日志设置仓库放日志设置领域，不污染 ClipSettingsRepository、备份或广告仓库。

app/logging 由采集协调器、固定 logcat 命令/文件命名纯规则、Download 路径归属准备、目录元数据 Reader 和可取消进程会话组成；参考旧 RuntimeLogcatRecorder 的启动/停止与故障隔离，不恢复其逐行转存或旧 RollingLogOutput。Application 只初始化控制器与观察已提交开关；MineVm/Composable 不持有子进程、文件流或平台 writer。

“我的 → 设置”增加标题为“日志”的 item，右侧开关控制是否读取和保存，说明区展示保存目录和当前文件数量，两段文案为蓝色且点击打开同一个日志目录；设置与文件详情独立，不因点击详情而改变开关。建议新安装默认关闭并持久化，重启读取已提交状态后恢复；完整展示、数量和外部打开契约见本节下方补充及 [我的页方案](mine_page_plan.md)。设置提交失败保留原值，读取失败显式反馈，不能把未读到默认值当真实配置。页面生命周期感知收集轻量状态，离开页面/切后台不停止开启的采集。

### 日志 item 的目录、数量与打开动作（2026-10-13，已完成）

用户明确要求 item 显示标题、保存文件夹、当前日志文件数量和开关；目录与数量文案为蓝色，两处点击均直接拉起系统文件界面查看对应文件夹。建议布局为首行“日志 + 右侧 Switch”，下方为可换行的 `Download/ClipMaster/logs/` 和资源化 `日志文件：%1$d 个`。复用项目现有浅/深色品牌蓝 token，不在页面硬编码另一套颜色；长目录、大字体、窄屏和读屏须验收。10 MiB/20 历史的策略保持，可在补充说明中简短显示，不用它替代实际文件数量。

只有开关动作提交采集设置，开启后启动读取和直接文件保存，关闭后停止读取/保存并保留已有文件。蓝色目录和数量属于同一个“打开日志文件夹”动作，可以合并为一个清晰的点击/无障碍区域，均不触发 Switch 或父卡片的开关 callback；关闭保存后仍允许查看已有日志。标题/普通空白区域不默认作为切换入口，避免目录动作误触保存。

复用 `SettingSwitchEntryCard` 的图标、标题、Switch、卡片与 theme token，按 [共享表单组件主方案](shared_form_components_plan.md) 扩展说明 slot 和独立动作语义，保留原字符串重载及权限项整卡切换契约。原组件仅有 description 字符串；现在已扩展 descriptionContent 与未知状态 trailingContent slot。不在 MinePage 复制一份卡片骨架或用多个隐式 Boolean 驱动状态。日志业务通过稳定 UI state 和 callback 传入，统计、导航 Intent 与日志控制不进入共享组件。

当前文件数量必须实际查询，不能用“21”常量或开关状态推算。口径为日志根目录中当前可访问、可识别的本应用日志文件，包括当前文件、编号历史及保留的旧应用日志；排除子目录与无关文件，不递归统计旧日期目录。旧会话日志若还在根目录，数量可能超过本轮受控的 21 份，这不代表轮转数量失效；文件归属/可见性限制必须反映真实查询边界。目录未创建可明确显示 0，加载中/查询失败显示对应状态，不能把权限或查询异常伪装成 0。

数量由职责单一的目录元数据 reader 查询，不读取日志正文。仅在“我的”页可见时启动：首次进入、从系统文件界面返回、开关动作完成以及可见期文件 CREATE/DELETE/MOVE 事件时刷新；事件合并后查询，忽略逐条日志写入/MODIFY 噪声。离页停止不必要的观察和查询，日志子进程仍按开关继续运行。可优先使用目标目录轻量元数据/当前可访问 MediaStore ID 查询；具体统计与 FileObserver/ContentObserver 的原生轮转兼容需设备验证，不用持续轮询或全盘扫描。

外部打开独立为具名平台 opener：构造实际存储卷下日志目录的 document URI，以 ACTION_VIEW 和目录 MIME 请求系统文件浏览界面。[AOSP DocumentsUI](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/heads/master/AndroidManifest.xml)的 FilesActivity 提供目录 VIEW 入口，支持时可直接定位。不得硬编码某品牌文件管理器的 Activity，不通过 file:// URI，也不为浏览目录新增所有文件访问权限。

厂商不支持目录 VIEW 或定位失败时，降级到系统文件选择器并通过 EXTRA_INITIAL_URI 指向日志目录；这属于可浏览/选择文件的兼容路径，不能宣称所有设备都直接进入文件管理器。用户选择日志文件后可交给系统文本查看器，取消不修改保存设置或目录；不使用 ACTION_OPEN_DOCUMENT_TREE 要求重新选择日志保存位置。[Android 文件选择器说明](https://developer.android.com/training/data-storage/shared/documents-files)。目录未生成或没有可用系统处理器时提示明确原因，不悄悄开启日志或生成日志内容。

既有 `ImageFolderOpenHelper` 只打开相册，历史上已因目录直达不稳定退回相册，与文本日志职责不符；不能直接拿来打开日志。数量读取、目录 URI/Intent 构造及外部动作失败各自保持可测试边界；共享组件仅抛打开事件，Mine 页面负责启动外部 UI 和资源化反馈。

日志与诊断计划补充：目录数量/打开失败 WARN，仅限 operation、固定 reasonCode、异常类型/可见数量，不输出日志正文、完整路径或 URI；正常按需数量更新不打印每次重组/写入事件。数量状态与外部打开属于本机瞬时展示，不进入备份，不改变已确认的日志开关或 10 MiB/20 历史轮转。

### 生命周期、故障与丢失边界

一个主进程只允许一套受控 logcat。开启时在后台准备受控路径、确认设置仍有效后启动；关闭时撤销许可并销毁进程，读取退出状态并释放监测任务。快速切换用会话身份和串行控制，避免旧会话停止新子进程；不在 UI 线程等待阻塞退出，不无限重启故障中的采集。

直接写与大小轮转均由 logcat 处理，应用不再人工切换目标流，因而不需要之前为时间归档增加的队列/转存逻辑。原生文件轮转仍可能受到 rename/open/磁盘故障影响；stderr 固定分类必须与 UI 运行状态一致，不能只看进程存活就断言每次轮转成功。命令错误文本可能含完整路径，仅转固定 reasonCode/类型，不原样展示或输出。

系统循环缓冲覆盖、子进程读写过慢、权限/磁盘故障和主进程/采集进程硬杀仍可能使记录未保存；采集开关本身不提供无遗漏审计或主进程结束后的留证保证。业务后台任务结果不受录制失败影响，不把存储或命令故障归为 WebDAV 网络失败。[Logcat 缓冲说明](https://developer.android.com/tools/logcat?hl=en)。

### 日志与诊断计划、隐私与备份/R8

采集开始 INFO，字段为父 PID、固定大小/数量策略；停止 INFO 使用固定 log_session_finished，停止瞬间此条不保证已落盘。设置、路径准备、启动、命令退出/轮转错误 WARN，限稳定 reasonCode、异常类型与退出码，不记录完整路径、采集正文或命令 stderr 原文。原生轮转不新增每行回放、日志循环或应用级进度噪声；异常监测可以使用后台线程，但不处理业务日志 stdout 转存。

公开文件仍需遵守源头隐私规则，不能新增凭据、完整 URL/查询串、剪贴正文、授权 URI、请求/响应体或备份内容。已发现的自有完整 URL 日志在实施中收敛，不把 SDK 内容未经审查就宣称全部已脱敏；WebDAV 固定 DNS/连接分类由 [备份主方案](webdav_backup_plan.md) 维护。

文件与保存开关属于本机诊断状态，建议排除自定义备份、恢复及 dirty；宿主 DataStore 已被系统备份/设备迁移排除，新 key 不进备份 mapper。历史文件按用户确认的受控集合轮转，其他旧文件不清理。纯命令规则/UI state 不新增外部序列化或反射 keep；Application/Hilt/正式包日志门控接入后，交付前运行 Release/R8 并保留 mapping。

### 测试、验证与方案演进

按 repo-unit-test-gate，实施前选择性 TDD 覆盖父应用 PID、固定目录/文件族、10240 KiB 与 20 历史参数、无 -c/--id/任意 shell、开关提交与重启读取、快速开关会话隔离、进程退出/轮转错误分类及诊断无递归。新增 item 回归保护实际数量与加载/失败状态、当前/轮转/旧日志识别、元数据失败不变成 0、目录 URI/VIEW 意图及系统浏览降级；纯规则先失败测试，点击蓝色说明不触发开关、关闭后仍可打开、前后台统计启停、字体/主题通过 Preview/UI 或设备验收。撤销小时/日期归档、队列、应用流切换与自写大小分卷测试计划；原生轮转的真实追加、完整记录、后缀/顺序和最旧淘汰通过设备合成日志验收，不以 JVM 参数断言代替。运行受影响模块 testDebugUnitTest 和 :app:compileDebugKotlin；交付前叠加 Release/R8，确认正式包诊断门控。

剩余验证/取舍项集中为：直接路径及 rename 的系统兼容、旧存储权限、默认关闭及自有 V/D 放行、失败重试入口、重装归属冲突、SDK 输出隐私。10 MiB、20 个历史加当前、最旧淘汰、固定目录和不按时间归档均已确认，不再反复询问。

| 同日候选 | 当前状态及取舍 |
| --- | --- |
| 统一日志文件 sink | 已废弃；不能覆盖全部实际 SDK/原生 Log |
| 每日目录/小时文件，流式追加或双任务队列 | 已废弃；用户撤销时间归档，不再需要该复杂切换设计 |
| 我的页开关控制 logcat -f/-r/-n 直接保存，10 MiB/20 历史 | 已完成源码与本地验证；完整设备验收待完成 |

本轮已按用户授权实现。实际文件清单、选择性 TDD 证据和最终验证统一见 [执行记录](logging_execution_plan.md)；现行 Mermaid/HTML 未改变。统一封装新增公开采集期间的 URI 令牌过滤与有界异常代码栈表示，三个 HTTP BASIC logger 同步抑制原文；视频候选不展开 Cookie/标题，图片/下载异常不拼 message。SDK/原生日志不会经过应用的脱敏函数，分享文件前仍需人工检查。

### 最终实现与平台验收边界（2026-10-13）

本轮源码及本地验证已完成：新增 17 项回归，最终 app 304、base:general 109，共 413 项单测零失败/错误/跳过；Debug、Release/R8、lintVital、APK 签名验证和差异检查通过。正式 APK 与 mapping 路径、测试先行记录及规则例外见 [执行记录](logging_execution_plan.md)。完成状态不代表已安装或完成各版本真机验收。

| 归口 | 实现职责 |
| --- | --- |
| `app/logging/LogCapturePolicy` | 父 PID、main/system/crash、起点、10 MiB/20 历史参数和固定文件族 |
| `LogDirectoryStore` / `LogDirectoryMetadataReader` / `LogDirectoryWatchPlan` | 固定公共目录、noBackup 安装归属、真实计数、有界三层事件重绑；不读正文 |
| `LogcatCaptureController` / `LogcatProcessSession` | Application 主进程观察设置，串行取消旧会话；stderr/退出分类和流/进程释放 |
| `LogSettingsVm` / `MineLogSettingsItem` / `LogSettingsEntryCard` | 专用轻量状态、生命周期/Tab 可见性、权限与系统打开动作、品牌蓝说明和独立开关 |
| `LogDirectoryOpener` / 共享开关 slot | 系统目录 VIEW、文件选择/文本查看降级；原权限项整卡切换继续复用 |
| `LogSettingsRepository` / `HostSettingsKeys` | 本机已提交开关，默认关闭，不进入备份白名单/dirty |
| `WebDavFailureDiagnostics` / 统一日志 Formatter | 请求/响应失败固定分类、有界 cause、地址/异常隐私收敛 |

最终只读审查提出目录身份变化重绑、设置错误明确展示两项 P2，已修复：事件覆盖 MOVE_SELF/DELETE_SELF，保留 Download/ClipMaster 父锚点并在事件后重新注册；设置未知时显示加载或“保存状态未知”，不绘制假的关闭 Switch。设置失败但目录计数成功有专门 Preview。纯目录路径计划先 RED 后 GREEN，Compose slot/文案仅编译与结构检查，不写镜像 JVM 测试。

API 31 三星设备的 debug run-as 同 UID 合成探测确认直接写、追加与小阈值当前+2 历史轮转；合成文件已清理。只读 Intent 查询找到系统 Resolver，表明存在匹配入口，不能代替目标目录打开成功。未安装本轮 APK、未上传真实备份；正式应用 SELinux 域、Android 7–9 权限与 Android 10+ 不同版本、FileObserver 实际事件、页面点击/读屏与 SDK 原文仍是设备验收项。新文件不先登记 MediaStore；当前固定路径/安装标记模式以真实创建/rename 成功为准，平台拒绝就显示保存失败，不增加所有文件访问或正文队列。

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

- 2026-10-13：按用户开始执行指令完成日志开关、实际计数/目录打开、原生 10 MiB/20 历史轮转及 WebDAV 固定分类；修复独立审查的目录重绑和未知设置展示。新增 17 项回归，最终 413 项单测、Debug、Release/R8 与差异检查通过；本轮平台验收边界和完整证据以应用日志主方案及执行记录为准。

- 2026-10-13：按用户新增要求补齐我的页日志 item 的标题、固定目录、实际文件数量和开关；目录/数量使用品牌蓝并独立打开系统文件夹，关闭后仍可查看，扩展共享开关说明 slot 而非复制页面卡片。补按可见性读取目录元数据、查询失败/旧日志口径、AOSP 目录 VIEW 与系统文件选择器降级、平台职责及回归计划。原因：用户需要直接查找保存的日志，而非只有一个开关；本轮只更新已确认方案，未修改 UI 或运行代码。
- 2026-10-13：用户取消时间归档并明确选择每文件 10 MiB、保留 20 个历史加当前、约 210 MiB 和最旧淘汰，方案标记已确认但未实施。改用 logcat -f/-r 10240/-n 20 的直接文件与原生大小轮转，固定 Download/ClipMaster/logs，删除应用级队列、小时流切换和自写分卷设计，补直接路径/rename 平台验证与仅受控文件族的删除边界。原因：利用现成命令降低复杂度，同时保留全进程实际日志与我的页开关；本轮未执行采集或修改运行代码。
- 2026-10-13：按用户对队列转存必要性的质疑核对 AOSP，确认 `logcat -f` 支持直接追加、内置轮转仅按大小，不能直接满足日期目录/小时单文件。将尚未实施的双任务有界队列简化为同一 logcat 加单后台线程直接流式追加，更新切换、关闭、故障和回归边界，取消队列/背压默认设计。原因：尚无吞吐证据证明额外队列必要，固定路径直写与时间归档需要分别判断，不能一概用复杂缓冲解决；本轮只修正方案，未执行日志命令或修改运行代码。
- 2026-10-13：针对用户询问切换日期目录/小时文件是否漏日志，补充同一 logcat 持续运行、reader/writer 解耦、有界 FIFO、固定读取桶、先准备新文件再关闭旧流、整行写入和故障进度边界；增加小时/午夜顺序、阻塞打开、跨桶积压及背压停止回归计划。原因：正常切换不应主动产生采集间隙，但不能承诺存储故障、长期积压或硬杀时零丢失；本轮只完善方案，未新增运行代码或测试。
- 2026-10-13：按用户最新要求将当前方案改为我的页开关控制 logcat 命令子进程采集，由后台 writer 按每天目录和每小时单文件保存；用户已澄清只要求覆盖本应用实际输出，不要求主进程结束后持续留证。收敛同日两个过期候选为演进表，明确父应用 PID、UID 权限、实际输出边界、同小时追加/重装冲突、关闭时销毁子进程、存储故障和平台验收。原因：统一入口方案遗漏 SDK 与直接原生日志；本轮只改方案，未启动子进程或修改运行代码。
- 2026-10-13：按用户面向未来排查的要求，新增统一日志异步分发、自然小时拆分与我的页保存开关可行性评估，废弃同日独立 Logcat 捕获候选；补齐文件/Logcat 独立门控、单次 message、设置与共享 UI 复用、进程生命周期、命名冲突、队列压力、存储故障、隐私与备份排除，以及集中开放问题。原因：用户更倾向长期可控制的统一入口保存，并只要求评估可行性；本轮未修改运行代码或确认实现。
- 2026-10-13：按用户“保存到 Download 供后续排查”的新要求增加恢复日志保存草案；用户已确认范围为本应用进程全部日志。比较同步写入、统一入口异步写入和独立 Logcat 采集，结合仓库直接原生日志调用推荐本次采用独立采集线程；明确权限、门控、分卷、阻塞停止、故障隔离、源头隐私与设备证据边界。原因：仅 WebDAV 细分 Logcat 无法满足事后取出文件的需求；尚未确认实现方式或恢复代码，历史移除记录继续描述当前代码。
- 2026-10-10：完成两条日志落盘链路、Application 接入、强制门控、分卷/journal/保留规则和 16 项失效测试的移除，保留即时日志、3 项诊断规则回归及全部业务契约；补正 Worker/协调器失效注释。最终 362 项完整单测、Debug、Release/R8、lint、正式打包及独立只读复核通过，方案标记已完成；设备未安装/上传、历史文件保留明确作为后续边界。
- 2026-10-10：用户批准按完整方案修改代码，状态改为实现中；在当前工作区保留既有方案改动并继续实施。使用仓库选择性 TDD 判断，删除退役能力和失效测试，不为删除动作引入新的规则或源码快照测试；保留停止分类、脱敏、trace 和业务结果回归，系统行为留待设备验证。
- 2026-10-10：根据用户反馈 WebDAV 备份已正常，编写移除本地日志保存草案；用户进一步确认“全部停止日志落盘，保留常规 Logcat”，纳入公共应用录制与私有备份文件留证，保留停止分类和备份结果，明确历史文件、测试退役及设备验收边界。尚未实施，旧实现和验证记录保留为历史。
- 2026-10-08：按用户澄清改为全应用进程日志自动写公共 Download，而非手动导出或仅同步 200 条备份摘要；通过独立录制器覆盖分散原生日志，避免逐模块改造与主线程 IO。
- 2026-10-08：完成录制器、公共目录写入、UTF-8 分卷及 Application 早期接入；12 项新测试、20 项相关回归、Debug 编译、Release/R8、签名及独立复核通过。修正文档的中断退出边界，新增输出故障回归；开发自动录制已可交付，真机文件读取仍待验证。
