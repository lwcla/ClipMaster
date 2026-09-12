# TorrentSearch 最终发布报告

> 状态：已完成（本机可执行门禁）
>
> 日期：2026-09-12
>
> 功能开关：`enableMagnetFeature`

## 结论

TorrentSearch 任务 6、任务 7 与任务 8 的实现和本机可执行发布门禁已完成。发布候选满足用户最终确认的网络可用性：任意合法 host 的 HTTP/HTTPS 都允许真实请求，包括公网域名/IP、局域网、`.local`、回环、链路本地 IP 与自定义端口；HTTP 只显示明文风险提示，不阻止保存、使用或请求。

当前主机没有 Android 设备、AVD 或 system image。因此 API 24/36 instrumentation、真实磁力客户端、SAF Provider、Cloudflare WebView 交互和冷启动计时没有执行，不能声明这些设备项通过。对应 instrumentation 已在功能开关开/关两条 classpath 下编译通过，设备可用后可直接运行。

## 构建与依赖门禁

- 功能关闭 Release/R8：通过，255 个任务，APK 4,849,592 bytes。
- 功能开启 Release/R8：通过，296 个任务，APK 5,738,045 bytes。
- 体积增量：888,453 bytes，18.32%。
- 功能开启 mapping：已保存，69,577,216 bytes。
- Gradle wrapper distribution SHA-256：已固定。
- `gradle/verification-metadata.xml`：399,898 bytes，覆盖 853 个组件；后续未带写入参数的 Debug、AndroidTest 与单测命令均在 dependency verification 开启状态下通过。
- 依赖许可证：`LICENSE`、`NOTICE.md` 与 `THIRD_PARTY_DEPENDENCIES.md` 存在且通过发布静态门禁。

产物位于 `app/build/reports/torrent-search-release/`：

- `feature-disabled-release.apk`，SHA-256 `EB81761741A7526BD4138A20B4BF3A5F3AF357D5CEF00166568B8816554587F5`
- `feature-enabled-release.apk`，SHA-256 `611B7BC9333447A7B88E4986A10DDDFC5AA02441DB940BE10ADFBFD09E7BB8B9`
- `feature-disabled-AndroidManifest.xml`
- `feature-enabled-AndroidManifest.xml`
- `feature-enabled-mapping.txt`

## APK、权限与 Manifest 差异

两个 APK 的 applicationId、versionCode 33、versionName 0.6.0、minSdk 24、targetSdk 36、启动 Activity 和系统权限集合一致。TorrentSearch 没有增加系统权限、service、receiver 或 provider。

启用版相对关闭版只增加：

- 非导出的 `TorrentSearchActivity`。
- 导出的 `ACTION_SEARCH`、`ACTION_SEND`、`ACTION_PROCESS_TEXT` 三个 activity-alias，均指向非导出真实 Activity。
- `android:usesCleartextTraffic="true"`，用于落实用户确认的真实 HTTP 可用性。

Application 类保持 `com.cla.clip.master.App`，没有新增启动期 provider/service/receiver。静态冷启动初始化边界因此未扩大到主动后台组件；真实冷启动时间差必须等设备可用后测量。

## 静态扫描与测试

`checkTorrentSearchReleaseSafety` 已通过，覆盖：

- 生产日志不得插值 URL、查询、magnet、Cookie、API Key、header/body/response、文件名或异常 message。
- fixture 不得包含授权头、私钥、AWS key 或疑似明文凭据。
- Android 资源必须使用 `torrent_search_` 前缀。
- Feature Manifest 不得声明权限、provider、service、receiver、备份接管或 `tools:replace`。
- 上游许可证、署名和直接依赖许可清单必须完整。

功能开关关闭与开启两条路径的 `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin` 均通过；启用路径的 `:app:testDebugUnitTest` 为 69 tests / 19 suites，0 failures/errors/skipped。TorrentSearch 模块全量 JVM 回归为 654 tests / 98 suites，0 failures/errors/skipped，Kotlin 编译、中文注释门禁和发布静态门禁同时通过。

## 真实 Provider 联网冒烟

真实冒烟使用生产 `OkHttpTorrentSearchNetworkClient`、固定中性查询词和全部 43 个内置 Provider。报告只包含 Provider ID、状态、结果数量、耗时、可选 HTTP 状态与未分类异常类型，不包含 URL、查询原文、标题、magnet、响应正文或异常 message。

2026-09-12 收尾复跑时，AudioBookBay 搜索与详情 parser 已兼容当前站点把主容器从 `#primary` 改为 `#content` 的 DOM 漂移，并由真实搜索请求返回 9 条结果。SubsPlease 的公开 `search` API 对多个查询词均返回 HTTP 200 空正文，而同站 `latest` 与 `schedule` API 仍返回正常 JSON；因此继续准确分类为外部搜索端点 `parse_failed`，不通过放宽 JSON parser 伪装成成功。

最新结果：

- `ok`：25
- `cloudflare_locked`：10
- `http_denied`：6
- `network_failed`：1
- `parse_failed`：1
- `code_failure`：0

低敏报告位于 `feature/torrent-search/build/reports/torrent-search/provider-live-smoke.json`。第三方站点的挑战、拒绝和解析漂移记录为健康状态，不阻断发布；未分类代码异常为发布阻断项。

## 已知警告与设备待补

- Release/R8 仍显示仓库既有 Shizuku `ClipboardListener` / `OnOpNotedListener` 警告，不来自 TorrentSearch。
- Manifest 合并仍显示宿主既有 `application@label tools:replace` 无对端声明警告。
- App Debug 编译仍显示宿主既有 `ShizukuServiceUnavailableTip.kt` 恒假类型判断警告。
- API 24/36 `connectedDebugAndroidTest`、真实磁力打开、SAF 保存、Cloudflare 挑战页和冷启动计时待具备设备/模拟器时补跑。

## 回退

若真实设备补测发现发布阻断问题，使用 `enableMagnetFeature=false` 重新构建发布；关闭版不包含 TorrentSearch 实现 Activity、外部 alias 或 cleartext 开关，用户数据和旧数据不做破坏性自动迁移。

## 变更记录

- 2026-09-12：建立最终发布报告；原因是任务 8 需要把构建、依赖、许可、真实联网、APK/权限/Manifest 差异、mapping 和设备限制统一形成可审计结论。
- 2026-09-12：收尾修复 AudioBookBay 搜索与详情当前 `#content` 容器并复跑 43 Provider；AudioBookBay 真实搜索返回 9 条，整体更新为 25 ok、10 Cloudflare、6 HTTP 拒绝、1 网络失败、1 SubsPlease 搜索空正文解析失败。原因是发布报告必须区分可修复的 DOM 漂移与第三方端点自身异常，不能把 HTTP 200 空正文当作成功。
