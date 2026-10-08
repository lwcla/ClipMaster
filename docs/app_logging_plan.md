状态：已完成

# 开发阶段应用日志自动保存

## 目标与确认范围

2026-10-08 用户明确要求将应用运行日志直接自动输出到系统 Download，以便没有 root 的手机取出分析；当前为个人开发使用，不增加导出按钮或隐私确认。范围由仅备份诊断扩展为应用进程已经输出的各级 Logcat 日志及异常堆栈。既有日志内容不另外裁剪或脱敏，不新增凭据/用户内容日志字段；统一日志入口的 V/D 输出开启，正式包也生效。Android 系统其他进程、独立 Shizuku 服务及未实际输出的第三方日志不属于当前应用 PID，不能宣称录到了全系统日志。

## 复用与职责

已依次检查备份 diagnostics、app 文件与日志能力、base/general Logcat 与 SaveToFile，以及全仓原生日志调用。统一入口可复用，但广告、搜索等模块还有直接 android.util.Log 调用，只扩展入口会遗漏。采用应用自己 UID/PID 的 logcat 流，不增加 READ_LOGS、root 或模块反向依赖。[AOSP 读取边界](https://android.googlesource.com/platform/system/logging/+/refs/heads/main-16k-with-phones/logd/LogReader.cpp)明确普通客户端只访问自己的日志；仍需设备确认厂商行为。

录制器放在 app/logging，Application 的 attachBaseContext 仅启动独立后台线程。纯分卷写入器负责字节阈值、行顺序与关闭，Download 写入器负责 MediaStore/旧存储 API。既有 SaveToFile 仅支持图片/视频及相册目录，不扩展为日志工具。备份私有原子诊断保留，新的日志包含其阶段输出；不改变生成、上传、重试及调度策略。

## 文件、生命周期与失败契约

- 目录固定为 Download/ClipMaster/logs；文件按手机启动时间、PID 和随机会话身份命名，每卷为文本文件。每个进程只初始化一次，后台录制覆盖前台和后台存活期，无页面轮询或额外 WorkManager/前台服务。
- 采集 main/system/crash 缓冲区，PID 精确过滤，采用 threadtime/year 格式，启动时间稍前的回读覆盖初始化期间日志。统一入口不再因 Release 默认关闭 V/D 而遗漏开发诊断。
- 单文件以 UTF-8 字节计达到 10 MiB 后分卷，跨卷不删除记录或旧文件；单个超长行可超过阈值，以完整保存内容。历史文件由用户在系统文件管理器管理，不承诺无限磁盘容量。
- Android 10+ 使用 MediaStore.Downloads，不需要额外存储权限；Android 9 及以下仅在已有 WRITE_EXTERNAL_STORAGE 权限时写公共 Download，不从后台弹权限。参考 [Android 文件存储说明](https://developer.android.com/training/data-storage/shared/media)。文件创建使用 pending 标记，流打开后发布；不长期隐藏仍在记录的文件。
- 连续流使用专用线程，无日志时阻塞等待而非定时轮询。每行写入后 flush；reader 退出、打开/写入失败或读取返回后发现线程中断时关闭流并销毁子进程。阻塞 readLine 本身不会被普通 interrupt 立即解除，当前不提供独立停止接口；未来需要停止时应先销毁子进程解除阻塞。没有新增保活；硬杀和系统日志环形缓冲覆盖仍可能丢失末尾未读取内容，不能保证全量审计。
- 创建、读取或写入失败仅停止本轮录制，固定警告使用原生 Logcat；不抛到 Application/Worker，不修改备份结果，不自动重启高频失败循环。重启应用会重新尝试。

## 日志与诊断计划

录制开始 INFO，字段为 SDK/PID、应用版本和分卷身份；启动/文件/读取失败 WARN，仅输出固定 reasonCode 与异常类型。录制器不打印采集到的正文，避免自反馈循环。写入的正文来自既有应用 Logcat，按用户要求保留原内容及堆栈；不额外新增密码、Token、Cookie、剪贴内容或网络响应日志。开发用途的公共记录由用户直接在文件管理器或 USB/MTP 读取。

## 备份、契约与验证

公开日志是运行态文件，不加入自定义备份白名单，不改 Room/schema/设置或用户数据；系统应用 Auto Backup 不扫描公共 Download。卸载后公开文件可能保留，重装开启新会话，不复用或覆盖旧文件。纯分卷对象和命令配置仅编译期调用，无新增反射/序列化模型或 keep 规则；保留正式包 mapping 用于堆栈回溯。

采用测试先行：保护 UTF-8 分卷、完整行、行顺序、流关闭及输出异常；同时核对命令 PID/各级输出及固定文件目录。运行 app 相关 testDebugUnitTest、compileDebugKotlin、assembleRelease/R8 和 git diff --check。MediaStore 可见性、无 root 自身 Logcat 读取及系统硬杀属于 Android 真机边界，JVM 不能验收。

2026-10-08 验证完成：首轮 9 项规则测试按预期失败，实现后通过；独立复核未发现 Critical/Important 问题，已修正阻塞读取中断说明并增加 write/flush/close 故障回归。最终新增 12 项日志测试与既有 8 项备份诊断/提示测试共 20 项，无失败、错误或跳过。运行 `.\gradlew.bat :app:testDebugUnitTest --tests '*RollingLogOutputTest' --tests '*AppLogRecordingPolicyTest' --tests '*BackupDiagnosticsTest' --tests '*BackupFailureMessageMapperTest' :app:compileDebugKotlin :app:assembleRelease --console=plain` 完成编译、测试和 Release/R8；补齐故障测试后重跑同一测试筛选及 compileDebugKotlin，BUILD SUCCESSFUL。git diff --check 通过。只存在既有 Gradle 废弃配置及 Shizuku 接口相关警告。

安装包为 `app/build/outputs/apk/release/ClipMaster-v0.7.2-admob.apk`，生成时间 2026-10-08 15:32:01，签名核验通过且与既有设备签名一致；APK SHA-256 为 `A423FB8FBA7A0AB447C895C8FDE5F243439DB1F2B2C13E19F2B2688A8B1C17F2`。mapping 保留在 `app/build/outputs/mapping/release/mapping.txt`。未安装到手机、触发备份或上传，不把编译/纯测试结果宣称为设备录制验收。新增/修改的方法、变量及关键分支均有简体中文注释，无未拆分或未补纯规则测试的临时例外；Android 平台访问仍待真机验收。

## 后续待办

安装后只查看文件生成与内容，不触发备份或上传；后续实际后台任务用于定位 WebDAV 停止原因。厂商是否允许自己的 logcat、旧系统存储权限与末尾丢失边界需真机核对；未来公开发布前另评估开发日志开关和磁盘保留策略。

## 变更记录

- 2026-10-08：按用户澄清改为全应用进程日志自动写公共 Download，而非手动导出或仅同步 200 条备份摘要；通过独立录制器覆盖分散原生日志，避免逐模块改造与主线程 IO。
- 2026-10-08：完成录制器、公共目录写入、UTF-8 分卷及 Application 早期接入；12 项新测试、20 项相关回归、Debug 编译、Release/R8、签名及独立复核通过。修正文档的中断退出边界，新增输出故障回归；开发自动录制已可交付，真机文件读取仍待验证。
