状态：已完成

# Logcat 文件采集实施计划

目标：实现已确认的日志开关、原生 10 MiB/20 历史轮转、蓝色目录与实际数量入口，并补 WebDAV 固定异常诊断。

设计以 [日志主方案](app_logging_plan.md)、[我的页接入](mine_page_plan.md)、[共享组件](shared_form_components_plan.md) 和 [WebDAV 诊断](webdav_backup_plan.md) 为准。使用 writing-plans、executing-plans 和选择性 TDD，在用户当前工作区执行；用户已明确“开始执行”，不再增加一轮设计确认。现有四份文档是本会话设计，不回退或覆盖；不创建提交、推送或安装应用。

## 职责与执行清单

- [x] 任务一：先测试日志命令/文件识别/失败分类及 DataStore 默认关闭、保存和重读。新增 `app/logging/LogCapturePolicy.kt`、`base/general/config/logging/LogSettingsRepository.kt` 和 `backup/webdav/WebDavFailureDiagnostics.kt`，运行受影响 JVM 单测。
- [x] 任务二：实现 `LogDirectoryStore` 的本安装归属校验、固定日志族准备、轻量数量与事件观察；实现可停止的 `LogcatCaptureController`，观察设置并只启动一个原生 `logcat -f/-r/-n`。路径/rename 验证失败显式报错，不改用消息转存或扩大权限。
- [x] 任务三：新增日志专用 ViewModel/薄页面适配与目录打开平台封装，扩展共享设置卡片说明 slot。标题、目录、数量、开关及读屏文案资源化；页面可见才观察目录，蓝色点击不触发开关。复用旧存储权限请求，关闭仍可浏览。
- [x] 任务四：WebDAV 请求与响应 IO 分支使用固定分类和有界 cause 类型链，源头收敛已有完整 URL 日志。运行完整 app/base 单测、Debug、Release/R8、差异检查及独立审查，同步主文档和实际设备边界。

## 验证与复核重点

- 命令不清理用户旧日志，仅原生固定日志族轮转，10240 KiB/20 历史加当前。
- 设置关闭、快速开关、启动/目录/rename 失败和进程退出能停止监测，不无限重试或留下子进程。
- DataStore/目录查询失败不伪装成关闭/0；SDK 内容不宣称自动全脱敏。
- 根目录数量不读正文；生命周期和 FileObserver 创建/删除/移动事件合并；长文字、深色和点击/开关语义保持独立。
- 系统目录 VIEW 优先，选择器和文本查看器降级，不改变保存路径或开关。

命令：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' --console=plain`；交付叠加 `:app:assembleRelease`，以及 `git diff --check`。

## 日志与诊断计划

沿用日志主方案低敏 INFO/WARN，固定 reasonCode、类型、退出码；不输出完整 URL、URI、命令 stderr、密码、日志正文或用户内容。统一封装在采集许可期间过滤 HTTP/content/file 地址令牌，异常只保留有界类型和代码帧；三个自有 OkHttp BASIC logger 暂停原文输出，视频候选不再整对象打印 Cookie/网页标题。原生/SDK 绕过统一封装的输出不宣称自动脱敏。日志保存故障不改变备份结果，写设置错误沿用资源化提示。

目录元数据下沉到 `LogDirectoryMetadataReader`，区分缺失、普通文件、枚举失败和本安装归属冲突，先写失败回归再实现。原生进程等待、stderr 分类和取消清理下沉到 `LogcatProcessSession`，合成进程桩覆盖退出、rename 失败及取消时释放阻塞流；Android 权限、真实 ProcessBuilder、FileObserver 和系统 Activity 仍属于平台验收，不用 JVM 参数测试宣称已验收。

最终只读审查发现两项 P2，均纳入一次修复：`DELETE_SELF/MOVE_SELF` 与逐级目录创建后重新绑定固定三层观察路径（纯计划回归先 RED 后 GREEN）；设置加载/失败用专属状态文案与非开关的尾部状态 slot，未知值不再表现为 unchecked Switch。UI 增加“设置失败但计数成功”Preview；纯 Compose 文案/slot 不写镜像 JVM 测试，无额外状态归约规则，实际点击/读屏仍需设备验收。不存在 Critical 或剩余独立 Minor。

审查中舍弃行为逐项裁定：主进程死后留证与 SDK 自动全脱敏按用户/方案排除；旧会话数量大于 21 和故障不自动无限重启符合契约；实际 scoped storage/SELinux 与目录 VIEW 保留平台验收，不推断普遍成功；同名文件外部对抗性替换未发现普通使用触发，归属标记与 OS 权限保护按当前范围处理。

## 执行记录与规则取舍

### 最终验证结果

2026-10-13 最终源码命令：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleRelease --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' --console=plain`，`BUILD SUCCESSFUL`，5 分 23 秒、473 tasks。记录 `build/logging-release-validation.log`。XML 核对：app 304、base:general 109，共 413 项，失败/错误/跳过均为 0；新增 17 项日志回归。Debug/Hilt、Release/R8、lintVital 和 APK 打包通过，`apksigner.bat verify` 退出 0，`git diff --check` 通过。既有 Shizuku OnOpNotedListener 的 R8 警告未纳入本次日志改动。

产物：`app/build/outputs/apk/release/ClipMaster-v0.8.3-admob.apk`（2026-10-13 23:07，5,508,290 字节）；mapping 为 `app/build/outputs/mapping/release/mapping.txt`（同次构建保留）。没有安装、实际备份、推送或提交；普通 main 工作区与全部未提交改动保留，没有新分支/worktree需要合并或清理，收尾不增加无关集成菜单。

TDD：命令/文件族/诊断/settings 和异常 Formatter 的 RED→GREEN 记录在 `logging-red.log`、`logging-green-rules.log`、`logging-privacy-red.log`；元数据、消息和子进程取消先在 `logging-metadata-red.log`、`logging-process-red.log` 因缺少目标能力失败，随后 `logging-final-debug.log` 完整 411 项通过；最终目录重绑计划在 `logging-review-red.log` 失败，修复后由最终 413 项完整套件验证。Compose 文案/尾部 slot、Android 权限/Intent/FileObserver 运行态不写镜像 JVM 测试，已有 Preview/编译及同 UID 探测仅提供各自证据，完整设备交互待验收。

复用 HostSettingsStore、应用作用域和主进程检查、统一日志 API、SettingSwitchEntryCard/ClipMasterCard、品牌蓝 token 与旧存储权限入口；新增协作者按日志/配置/备份领域归口，无万能工具类或未拆分的大入口。新增/修改声明和关键分支有简体中文注释；自定义备份白名单和系统 DataStore 排除既有规则已覆盖，不改 backup schema/dirty。无新增外部反射协议/keep；本轮额外执行 R8 并保留 mapping。

实际项目 skills：repo-coding-gate、repo-architecture-boundaries、repo-compose-discipline、repo-docs-logs、repo-backup-coverage、repo-unit-test-gate、repo-git-discipline、repo-final-self-check。执行与复核同时遵循 using-superpowers、brainstorming、systematic-debugging、receiving-code-review、writing-plans、executing-plans、using-git-worktrees、test-driven-development、verification-before-completion、requesting-code-review；当前工作区与用户授权取舍如本文所记，未以门禁另请求二次确认。

- 2026-10-13：开始执行。用户当前目录为 main 且含本会话已确认设计，按明确执行指令在原目录保护性修改；不为执行流程另要求 worktree 或计划二次批准。计划进度在本文维护，避免与用户指定 docs 主文档分叉；技能 shell 脚本的临时账本/提交步骤改为 Windows 原生工具与此记录，未要求的 Git 提交不执行。
- 设备验证依赖 ADB 连接；直接 Download 路径与原生轮转兼容必须如实记录，不能以编译成功代替。

## 变更记录

- 2026-10-13：按用户开始执行指令记录实施任务、接口归口、选择性 TDD、平台验收与规则取舍。

- 2026-10-13：规则/设置/异常分类与 Throwable 隐私测试先 RED 后 GREEN；已有完整 app/base 单测和 Debug 编译通过。追加目录元数据失败/归属和 URI 脱敏回归，再完成最终整体验证。
- 2026-10-13：连接的 API 31 三星设备，同应用 UID 的 run-as 探测确认直接创建、追加及小阈值轮转（当前+2 历史）。只使用合成日志，未安装新 APK 或触发备份；debug run-as 的 SELinux 域不能替代正式包和各 Android 版本验收。
