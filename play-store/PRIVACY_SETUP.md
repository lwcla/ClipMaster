# ClipMaster 隐私政策维护与发布说明

状态：已完成

最新维护范围：2026-10-31 将政策 HTML 全部配色收敛为黑白灰，包括深浅色背景、品牌标记、链接及其悬停/焦点态、摘要强调线；已同步公开网页并完成线上验证。既有“我的”页入口优先打开公开网页，浏览器启动失败时回退内置正文。尚未填写 Play Console 或完成 Play 审核。下方 2026-10-27 全量核对与后续接入验证记录保留为历史基线，不代表本轮重新审计所有数据流。

更新日期：2026-10-31

## 中性深浅色背景（2026-10-31）

- 用户要求去掉原背景的灰绿、墨绿色偏，采用普通深浅色阅读背景。复用唯一 HTML 的现有 CSS、响应式布局与 `prefers-color-scheme`，不新增样式文件、组件或主题脚本；宿主 Compose 主题无法直接供独立静态网页复用。
- 浅色使用 `#f5f5f5` 页面背景、白色正文卡片；深色使用 `#121212` 页面背景、`#1e1e1e` 正文卡片。用户反馈首轮仍有绿色后，将品牌标记、链接及悬停色、摘要强调线也统一为中性灰；焦点框从金色改为浅色模式深灰、深色模式浅灰。最终 CSS 仅使用黑白灰，链接继续通过下划线、按钮轮廓和可见焦点表达交互。
- 政策正文、更新日期、章节锚点、外链、布局、打印规则及应用入口保持原契约。公开仓库已逐字节同步本地唯一源文件；已安装应用的内置兜底正文未更新，后续应用构建继续按单源资产约定生成。
- 单元测试检查：仅静态 CSS 配色变化，无独立业务规则，按 `repo-unit-test-gate` 不新增单测、不走 TDD，不重复 Gradle/R8。使用浏览器分别验证深浅色、桌面/手机宽度、打印背景、文字对比度和横向溢出，并检查样式以外的 HTML 完全一致及 `git diff --check`；Android WebView 仍待设备验收，线上部署结果见下方。
- 日志与诊断计划：无运行时副作用或新失败分支，不新增日志；无持久数据、备份、反射或序列化契约变化。CSS 配色分支补充简体中文注释，无新增或修改方法、变量。
- 最终全灰度验证：扩展并重新运行 `node build/privacy-background-check.cjs`（本地临时验证脚本）通过。Playwright 使用独立、无头 Edge，关闭页面脚本并阻止网络；全部 CSS 色值、所有节点的文字颜色、背景、边框、品牌标记与摘要强调线均为灰度，实际悬停及键盘焦点颜色检查通过。浅色/深色分别覆盖 1200px、390px、320px，无横向溢出，所测文字最小对比度分别为 5.27:1、9.95:1。CSSOM 规则数量、ID 唯一性、内部锚点、白色打印背景及样式以外的 HTML 一致性检查通过；桌面与手机共 4 张 `privacy-neutral-*` 新截图已逐张核对，取代首轮仅调整背景的截图。沿用此前获准的沙箱外本地浏览器验证方式。`git diff --check` 通过；Android WebView 与已安装应用未重新验收。
- 线上同步与验证：用户后续截图显示的是公开网址的旧绿色版本，核对公开仓库仍为旧样式后，通过 GitHub 插件只更新 `lwcla/clipmaster-privacy` 的 `index.html`，提交为 [`80fe363c73c4652ffdf5375456e8aaa17f3a9379`](https://github.com/lwcla/clipmaster-privacy/commit/80fe363c73c4652ffdf5375456e8aaa17f3a9379)；[Pages 构建与部署](https://github.com/lwcla/clipmaster-privacy/actions/runs/41192599172) 已成功。匿名请求公开原网址返回 `200`、`text/html; charset=utf-8`，线上与本地 SHA-256 均为 `b2349f336bcbd1d07259c18e92c8e918ae0db665b824cc7bf0588320c176a3b6`。`node build/privacy-background-check.cjs --online` 直接加载真实网址，响应字节一致性及上述六组渲染/交互检查全部通过，浅色与深色手机截图已核对。网页脚本关闭，验证期间仅允许目标 HTML 请求，不读取用户浏览器会话。手机已有标签页需要刷新以获取新版本；未修改政策正文或发布应用安装包。

2026-10-30 离线阅读接入验证：Debug 编译、4 项链接边界测试、Release/R8、两种变体的资产生成/合并均通过；最终 HTML 与源文件逐字节一致，维护文档未打入资产。HTML 锚点与字符串资源静态检查通过。后续公开链接接入已重新通过 Debug 编译、4 项既有测试和固定 URL 资源校验，未重跑 R8。公开政策打开与返回 App 已完成 Galaxy S10（Android 12）两轮真机验收；Samsung Internet 在本机需要两次系统返回，返回后“我的”页位置保持。内置正文完整交互和无浏览器回退仍待设备验收；完整命令、产物哈希、截图与限制见 [我的页方案](../docs/mine_page_plan.md)。

## 产物与范围

- 用户隐私政策：`play-store/privacy-policy.html`，中英双语单文件，无 JavaScript、外部字体、分析脚本或远程图片，支持移动端、深色模式和打印。
- 应用内入口：我的 → 设置 → 隐私政策，优先通过系统浏览器打开 <https://lwcla.github.io/clipmaster-privacy/>，无法启动浏览器时回退到内置正文。内置正文通过 AGP 生成 assets API 按变体打包同一 HTML，不维护手工副本；只有此文件进入安装包。内置阅读页禁止脚本与自动联网，用户点击 HTTPS/邮箱链接后才交给外部应用。完整实现及验证见 [我的页方案](../docs/mine_page_plan.md)。
- 开发者与联系邮箱：用户明确提供的 `cla`、`cla6939@gmail.com`。
- 应用：ClipMaster / 剪贴板助手，`com.cla.clip.master`，2026-10-27 核对时的版本配置为 `0.9.8` / `44`；后续版本发布记录见 [自更新主方案](../docs/app_update_plan.md)。
- 2026-10-27 全量核对基线：本地工作区 `95586c0`，当时开始时工作区干净；按当日实际源码及 `gradle.properties` 更新。当次只更新政策，没有修改应用功能、Manifest、SDK 配置或备份行为。2026-10-30 新增应用内阅读入口，不调整 SDK、Manifest 或备份。
- 复用现有双语 HTML 的十章节、锚点、联系方式和响应式样式，仅更新文案；不新建分叉政策或改动内部流程图。正文适用于当前启用 AdMob 的分发版本，并分别说明国内与 Play 更新检查行为；不覆盖开启 CSJ、uni-ad 或磁力搜索后的其他构建配置。
- 2026-09-29 首次生成时按用户要求采用 HTML；本轮直接对照源码维护，不宣称再次运行历史第三方 skill 或自动合规审计。

## 当前渠道基线

`gradle.properties` 当前为：

| 配置 | 值 | 对政策的影响 |
| --- | --- | --- |
| `appDistribution` | `domestic` | 披露“我的”页自动/手动访问 Gitee、GitHub 更新源；Play 分支仅打开商店 |
| `enableAdmobReleaseAdFeature` | `true` | 披露 Google AdMob / UMP |
| `enableCsjReleaseAdFeature` | `false` | 不把穿山甲列为当前版本的数据接收方 |
| `enableUniadReleaseAdFeature` | `false` | 不把 uni-ad 列为当前版本的数据接收方 |
| `enableMagnetFeature` | `false` | 不披露当前版本未启用的磁力搜索、Torznab 或 DoH 功能 |

这些是本轮源码和默认配置事实，不代表本轮重新核对过最终 AAB。发布命令、CI 或其他属性可覆盖配置；必须在正式包内容变化后同步更新政策。2026-10-27 应用过滤回退任务的 AAB 验证是独立历史证据，见下方发布边界，不能替代本轮运行验收。

## 源码依据

| 主题 | 主要源码位置 | 核实结果 |
| --- | --- | --- |
| 名称与版本 | `base/general/src/main/res/values/strings.xml`；`app/build.gradle.kts` | 中文名为“剪贴板助手”；版本与包名见上文 |
| 剪贴内容与来源 | `app/src/main/java/com/cla/clip/master/utils/ClipHelper.kt`；`MainActivity.kt`；`shizuku/src/main/` | 文本、来源包名/名称、时间及链接摘要保存；图标按本机应用信息展示。前台读取与 Shizuku 后台路径分开，撤销 Shizuku 不会禁止前台读取或删除既有数据 |
| 应用列表 | `app/src/main/java/com/cla/clip/master/installedapps/PackageManagerInstalledAppReader.kt` | 来源过滤页读取安装应用列表；完整列表仅为内存状态，保存的过滤包名和来源记录可进入备份；未安装的保存规则不补显示行 |
| 自动链接请求 | `app/src/main/java/com/cla/clip/master/utils/ClipHelper.kt`；`utils/LinkMetaParser.kt` | 保存链接后可自动解析远程页面；携带 URL、User-Agent、语言、Referer；没有假称“只有点击才联网” |
| 网页状态与下载 | `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeWebViewLayer.kt`；`base/general/src/main/java/com/cla/clip/base/general/dao/DownloadDao.kt`；`ImageExtractDao.kt` | JavaScript、DOM storage、第一方/第三方 Cookie 已开启；下载任务持久保存 URL、Cookie、Referer、User-Agent、文件位置及状态。历史页面已移除，但任务表仍供 Worker、当前结果和失败重试使用，未新增完成后自动删除期限 |
| 更新检查 | `app/src/main/java/com/cla/clip/master/ui/page/mine/MinePage.kt`；`MineVm.kt`；`update/AppUpdateChecker.kt`；`AppUpdateConfigFactory.kt` | domestic 在“我的”页可触发 24 小时限频自动检查或手动检查，GET Gitee/GitHub 发布信息，在本机比较版本；Play 分支不进入外部检查链路，手动只打开商店 |
| 广告 | `feature/ad-admob/src/sdk/java/com/cla/clip/feature/ad/admob/AdmobSdkLoader.kt`；`AdmobPrivacyController.kt`；`app/.../ad/AdHostEnvironment.kt` | GMA Next-Gen 1.4.0、UMP 4.0.0；UMP 联网刷新可先于广告请求，canRequestAds 不等于个性化授权；广告请求构造没有传入剪贴正文或应用清单。首页移除广告不代表全应用无广告 |
| 广告隐私入口 | `app/src/main/java/com/cla/clip/master/ui/page/mine/MinePage.kt` | 只有 UMP 要求提供隐私选项时才显示入口；没有承诺所有地区均显示同一表单 |
| 本地与 WebDAV 备份 | `base/general/src/main/java/com/cla/clip/base/general/backup/BackupSnapshotExporter.kt`；`BackupEntityMappers.kt`；`BackupModels.kt`；`backup/settings/BackupSettingsRepository.kt`；`app/.../ui/page/backup/BackupPage.kt` | 格式 5 导出剪贴（含未永久删除的回收站条目）、来源、预览、搜索历史和设置白名单；清单含版本、时间、本机安装标识及数量。不读取三张媒体任务表，不导出媒体、请求 Cookie、日志、广告同意或 WebDAV 凭据。首次生成/开启自动备份前有明文风险确认；自动备份默认关闭，WebDAV 默认 HTTPS，可显式允许 HTTP |
| 系统备份 | `app/src/main/AndroidManifest.xml`；`res/xml/backup_rules.xml`；`res/xml/data_extraction_rules.xml` | `allowBackup=true`；主数据库未整体排除，可能包含剪贴、下载任务及已保存请求信息。自定义备份的任务排除不改变系统备份；宿主 DataStore 和 WebView 状态按既有规则排除，不能据此承诺全部本机数据不出设备 |
| 删除与保留 | `base/general/.../config/clip/ClipSettingsRepository.kt`；`app/.../work/RecycleBinCleanupWorker.kt` | 回收站默认 30 天；后台清理受调度影响；备份和共享媒体的删除是独立操作 |
| 设备日志 | `app/src/main/java/com/cla/clip/master/logging/LogCapturePolicy.kt`；`LogcatCaptureController.kt`；`LogDirectoryStore.kt`；`base/general/.../config/logging/LogSettingsRepository.kt` | 默认关闭；开启后按主 PID 保存业务/SDK/原生 Logcat 至公共 `Download/ClipMaster/logs/`；10 MiB 阈值、20 历史加 1 当前文件，关闭不删旧文件。后台继续、进程结束停止、再次启动按开关恢复；SDK/原生日志不保证经过统一脱敏。该保存功能无自动上传，不虚构云端崩溃上报 |
| OCR | `app/build.gradle.kts`；`ClipHelper.kt` | `implementation(libs.mlkit.ocr)` 被注释；图像剪贴/OCR 为未启用预留方法，未把版本目录中的 ML Kit 定义当成已上线功能 |

## 本轮同步范围与取舍

- 下载与备份的最终行为以 [下载记录退役方案](../docs/download_history_plan.md) 和 [备份主方案](../docs/webdav_backup_plan.md) 为准；政策移除“自定义备份包含媒体下载记录”和“恢复后媒体关联”的过时描述，明确仍保留本机任务、请求信息和系统备份边界。
- 日志行为以 [日志主方案](../docs/app_logging_plan.md) 为准；补充开启条件、公开目录、未加密、轮转、第三方日志脱敏限制和删除方式，不承诺只有低敏信息或卸载即清除公共文件。
- 应用列表沿用 [来源过滤方案](../docs/clip_source_app_filter_plan.md) 2026-10-27 回退后的真实范围；没有因准备上架擅自收窄读取权限，也不把扫描内存列表与已持久化来源/过滤数据混为一谈。
- 更新检查以 [更新主方案](../docs/app_update_plan.md) 为准；政策范围从仅 Google Play 改为当前 AdMob 分发版本，分别说明默认 domestic 和显式 Play 的行为，不声称已上架。
- 联网预览开关及首次说明已于 2026-10-30 接入，交互边界以 [首次安装说明主方案](../docs/first_launch_consent_plan.md) 为准；常驻政策入口以 [我的页方案](../docs/mine_page_plan.md) 为准。仍不承诺自动敏感剪贴排除或下载任务定期清理；政策入口不能代替显著披露或后台声明。

## 发布前需确认的实际边界

1. **核对最终 AAB。** 2026-10-27 已按用户要求恢复安装应用扫描和广泛包可见性，并重新构建 Release/R8 AAB；实际 Manifest 确认包含 QUERY_ALL_PACKAGES、保留 Shizuku 查询及自身启动入口、不含此次桌面查询声明。311+7 项测试通过，产物 hash 与证据记录于 `docs/clip_source_app_filter_plan.md`。2026-10-26 的有限权限 AAB 只代表已回退版本；尚未做宿主真机 UI 或抓包，后续正式包变化仍须重新核对。
2. **填写政策网址。** 同一政策已于 2026-10-30 发布为公开 HTTPS 网页：<https://lwcla.github.io/clipmaster-privacy/>。应用内常驻入口默认打开该地址，并在浏览器无法启动时提供内置正文，不依赖 UMP 隐私选项。上架前仍须将该网页地址填写到 Play Console；本轮未操作 Play Console。
3. **受限权限。** 主 Manifest 已恢复 `QUERY_ALL_PACKAGES`，并保留“自用/私有分发；上架需重新评估”的注释；此前 MAIN + LAUNCHER queries 已撤销，Shizuku 管理器指定包查询保留。政策披露不能代替 Google Play 对权限必要性和资格的审核；存储权限及 Shizuku/后台剪贴读取应按实际包单独核对。主 Manifest 仍保留至 API 32 的旧存储读取权限，但不能将已移除的媒体关联流程当作当前用途。
4. **自动链接预览与后台剪贴。** 它们可能在用户未点击链接或正在使用其他 App 时处理数据。首次说明与联网预览开关已独立实现；本轮仅补政策描述与阅读入口，未改变这些授权流程。正式发布前仍须核查显著披露和同意时机，本政策不是这些交互的替代品。
5. **系统备份。** 政策已如实描述主库可能被系统备份。若发布目标要求剪贴内容严格仅存本机，需另行调整系统备份规则，之后同步更新政策。
6. **备份与删除。** 已存在的快照可能保留已删除记录；未对本地文件、远端备份或系统备份承诺联动删除。未承诺完整数据库/备份端到端加密。
7. **广告与年龄设置。** 发布前确认 AdMob 后台隐私消息、广告合作方/中介配置、目标年龄和 Play 目标受众。文档没有自行承诺“面向儿童”“无个性化广告”“不收集任何数据”或虚构已认证合规。
8. **法律与运营事实。** 开发者应核对实际服务地区、联系方式和用户请求处理流程；政策草稿不等于法律审查或商店通过结论。

## 托管说明

2026-10-30 按用户明确授权，在 `lwcla` 账号下创建独立公开仓库并启用 GitHub Pages：

- 公开政策网址（填写到 Play Console）：<https://lwcla.github.io/clipmaster-privacy/>。
- 维护仓库：<https://github.com/lwcla/clipmaster-privacy>，可见性为 Public。
- 发布来源：Deploy from a branch → `main` → `/ (root)`；默认域名强制 HTTPS。
- 仓库仅包含 `index.html`，内容逐字节复用本项目 `play-store/privacy-policy.html`；未上传应用源码、内部方案文档或凭据。
- 首次提交：[`ac983392f04211249dd2f562ffa36e807d262c22`](https://github.com/lwcla/clipmaster-privacy/commit/ac983392f04211249dd2f562ffa36e807d262c22)；[Pages 构建与部署](https://github.com/lwcla/clipmaster-privacy/actions/runs/41054465045) 已成功。
- 匿名 `curl.exe --fail --location` 请求返回 HTTP `200`、`text/html; charset=utf-8`，最终 URL 未重定向到登录页。下载正文与本地源文件 SHA-256 同为 `d4599281e045bb2e96cf97e3840bf59bbdd709456ad4d686b342a0ddc1605b56`。
- 在线页面桌面显示、英文锚点跳转、内部锚点目标及邮箱链接检查通过；中英各 10 个章节，无脚本或自动加载的外部资源。后续已在 Galaxy S10 / Samsung Internet 验证公开页面显示及 App 往返；未进行全球各地区可达性或多设备矩阵验证。

后续先修改本项目的唯一正文源，再将同一文件同步为公开仓库根目录的 `index.html`；等待 Pages 部署成功并检查线上正文后，再发布对应应用版本。不要在两个仓库分别编辑出分叉政策。应用入口按用户后续要求默认打开公开页面，随包正文保留为无法启动浏览器时的兜底。

本轮通过用户自行登录的浏览器创建仓库和配置 Pages，通过已连接的 GitHub 插件提交政策。没有读取或使用曾被自动审批拒绝的本地发布令牌；用户完成公开操作确认后才创建仓库并启用 Pages。未写入 Play Console。

发布后的检查：

- 用无登录浏览器及手机打开最终 URL，验证两种语言、邮箱、章节跳转和可读性。
- 确认页面可公开访问且不是 PDF、登录页、编辑页面或地域限制页。
- 对照实际 AAB、SDK 文档和运行行为填写 Data safety，不直接将“本机处理”或“用户主动上传”简单等同于整份表单的“无收集”。
- 若日后启用磁力搜索、其他广告源、OCR 或新的云端服务，先重新核查数据流再修改政策。

## 首次编写的历史工具记录（2026-09-29）

以下仅保留首次生成时的记录，本轮没有重新安装、调用或验证这些第三方 skill：

- `privacy-policy-generate`：来自 `hitoshura25/claude-devtools` 的 `skills/privacy-policy-generate`；已完整安装，本文使用其源码扫描、信息确认与政策生成流程，将 Bash 示例改为 PowerShell 兼容操作。
- `play-policy-insights`：来自官方 `android/skills` 的 `play/play-policy-insights`；包含 `SKILL.md`、`scripts/`、`resources/`，已完整安装。本次没有运行其完整自动审计流程，也不将本文的源码核查描述为该工具的审计报告。
- 项目文档流程：使用 `repo-docs-logs`、`repo-final-self-check`；参照 `repo-unit-test-gate` 判断仅为静态文档，无需新增 Android 单元测试或 Gradle 构建。

## 日志与诊断计划

政策正文与静态托管不新增 App 运行时日志。公开入口启动浏览器失败时，应用以 WARN 记录 `reasonCode=public_policy_open_failed` 与异常类型后进入内置正文；不记录 URL、异常原文或用户内容，完整入口诊断以我的页方案为准。HTML 不加载脚本、字体、远程图片或统计资源；打开政策、外部链接或发送邮件由用户主动触发。静态托管服务仍可能按其自身政策记录访问日志。

验证仅检查文件、HTML 结构、锚点、双语完整性、页面布局、匿名公开访问与 Git diff，不读取真实剪贴内容、WebDAV 凭据、广告 ID 或用户数据库。托管发布诊断仅记录公开 URL、提交号、构建结果、HTTP 状态、内容类型和正文哈希；不记录登录会话或令牌。

2026-10-30 托管验证与规则边界：复用既有 HTML，仅新增公开仓库文件并更新维护文档，没有 App 组件、类、方法、变量、运行时日志、备份或混淆规则变更，无新增代码注释适用项。按 `repo-unit-test-gate` 判定无需新增单测、TDD 或重复 Gradle/R8 构建；本轮验证覆盖部署、正文一致性和网页显示，不代表正式 AAB 或商店审核验证。应用源码工作区未提交，既有改动保留。

## 本轮验证与规则例外（2026-10-27）

- 按 `repo-unit-test-gate` 判定为静态政策文案同步，没有应用行为、Kotlin、Compose、持久化协议或反射变更，不补业务单测、不走 TDD，也不重新运行 Gradle/R8；风险在文案准确性与显示，由源码交叉核对、双语检查、HTML 校验及页面预览验证。
- 复用原 HTML，不新增组件、类、方法、变量、脚本或样式；无新增代码注释适用项。无运行时日志、备份实现或 keep 规则改动；无需生成 Mermaid/流程图。
- Python 标准库 `HTMLParser` 静态校验通过：20 个章节组成 10 对中英章节，段落/列表/标题/代码标记结构对应；26 个 ID 唯一、13 个链接及内部锚点有效、5 个本地主方案链接存在、日期为 2026-10-27、无乱码替代字符。已检查退役功能的旧肯定性描述被移除，日志、更新源及轮转参数两种语言均有覆盖；结构检查不代替语义审校，已逐项对照上方源码依据核对文案。
- CSS 与修改前完全一致；没有脚本、事件处理属性或自动加载的外部资源。`git diff --check` 通过。校验器不比较中英文强调标签数量，因为原页面对应用名称的加粗不同；不为通过检查修改既有排版。
- 本轮尝试通过内置浏览器打开本地 HTML 时，浏览器安全策略拒绝 `file://` 协议；未绕过限制或改用其他入口。因此未取得本轮桌面/手机/深色模式实际渲染证据，只能确认原样式未变。下方 2026-09-29 预览是历史记录，不能当作本轮验证。
- 本轮使用项目工作流：`repo-coding-gate`（范围与复用检查）、`repo-docs-logs`、`repo-unit-test-gate`（无需业务单测的判定）、`repo-git-discipline`（只读差异检查）、`repo-final-self-check`；另遵循 `superpowers:verification-before-completion`。没有创建/修改 skill、暂存、提交或推送。

## 首次编写验证记录（2026-09-29）

- 静态文档改动，不新增应用组件、类、方法、业务变量或 Android 字符串资源；CSS/HTML 使用简体中文说明注释。
- 不需要业务单元测试、Gradle 编译、R8 或真机验证；这些未执行，不能据此认定正式包合规。
- 未使用 `repo-doc-flow-html`：本文件是对外政策，不是 `docs/flows/` 的 Mermaid 方案流程图。
- 本次仅安装现成第三方 skill，没有创建或修改项目专用 skill。
- HTML 静态校验通过：20 个章节、中英两种语言、13 个链接，标签闭合、唯一 ID、内部锚点、邮箱与包名、无占位符、无脚本及自动加载的外部资源均通过。
- 内置浏览器预览通过：默认视口和 390px 手机视口均可阅读；手机视口实测内容宽度与滚动宽度相等（375px，扣除滚动条），无横向溢出；英文跳转定位正确。深色模式显示已人工检查。
- 两个 skill 的目录与配套文件检查通过：`privacy-policy-generate` 2 个文件；`play-policy-insights` 14 个文件，包含 orchestrator、报告生成器和资源目录。
- 项目复制后另行检查文件哈希、Git 状态与 `git diff --check`，结果以任务交付说明为准。

## 参考资料

2026-10-27 重新核对下列 Google Play 用户数据政策和 GMA Next-Gen 数据披露页面。官方 SDK 页面说明的是最新版本，不代替对本项目固定 SDK、配置及最终包的核对；历史工具链接仅供追溯。

- [Google Play User Data / 隐私政策要求](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [GMA Next-Gen SDK 数据披露](https://developers.google.com/admob/android/next-gen/privacy/play-data-disclosure?hl=en)：官方页面说明适用于其最新版本；最终提交时仍需核对项目固定版本和配置。
- [Google 隐私政策](https://policies.google.com/privacy)
- [privacy-policy-generate 源码](https://github.com/hitoshura25/claude-devtools/tree/main/skills/privacy-policy-generate)
- [play-policy-insights 源码](https://github.com/android/skills/tree/main/play/play-policy-insights)

## 变更记录

- 2026-10-31：根据用户线上截图定位到公开网页尚未同步，发布同源黑白灰 HTML，记录提交、Pages 部署、线上哈希及真实网址渲染证据，关闭网站同步待办。
- 2026-10-31：根据用户“仍有灰绿、墨绿部分”的反馈，移除首轮保留的品牌、链接与摘要强调绿色，并将金色焦点框同步改为中性灰；将验证范围扩展到所有 CSS 色值和交互状态。
- 2026-10-31：按用户要求将隐私政策的灰绿、墨绿底色调整为中性深浅色，统一正文卡片、提示块、普通文字和边框；复用原 HTML 的自动主题切换，记录本地验证及尚未发布的边界。
- 2026-10-30：完成 Galaxy S10（Android 12）上从“我的”入口打开公开政策并返回 App 的两轮验收；保留安装数据、页面位置与进程，记录 Samsung Internet 的两次返回表现。详细真机证据集中维护在我的页方案，不改政策正文或网站。
- 2026-10-30：公开网页发布后，按用户要求将“我的”常驻入口接到同一 HTTPS URL，无法启动浏览器时复用内置正文；入口验证与诊断以我的页主方案为准，政策正文和已部署网站无需修改。
- 2026-10-30：用户确认公开发布后创建 `lwcla/clipmaster-privacy`，仅提交同源 `index.html` 并启用 `main` 根目录 Pages；匿名 HTTPS 访问、部署状态、正文哈希及在线阅读检查通过。记录正式 URL 和后续同步方式，关闭政策公开托管待办；Play Console 填写及商店审核仍待完成。
- 2026-10-30：在“我的”设置分组新增常驻入口；用户确认网页未发布，先按构建变体打包唯一 HTML 正文供离线阅读。补充已实现的联网预览开关说明，更新日期；源码基线的全量审计仍是 2026-10-27。网站托管和 Play Console 链接仍待完成，未自动部署。
- 2026-10-29：随 0.9.9 发布纳入已完成的政策更新，将 0.9.8 标注为历史核对基线并链接版本主方案，避免与新的应用版本配置混淆；政策网站仍未部署。
- 2026-10-27：按 `95586c0` / 0.9.8 源码同步双语政策、日期及维护基线。移除自定义备份中的媒体任务和恢复后媒体关联描述；补充任务留存、系统备份请求信息、公共日志保存与轮转、首次备份明文提示、安装标识及 domestic/Play 更新检查差异；明确 UMP 刷新与广告请求的边界。原因是旧政策仍描述退役能力，并遗漏新增日志及实际默认分发数据流。本轮未修改 App、部署网站或提交商店。
- 2026-09-29：随宿主设置介质替换，将源码依据及系统备份排除说明更新为 DataStore 的实际文件和规则；政策正文未因这次介质替换改变数据用途。
- 2026-09-29：首次创建中英双语政策 HTML 与维护发布说明；根据当前 Play 配置和实际数据流披露自动链接联网、来源应用、后台剪贴、未加密备份、系统备份和 AdMob，避免套用不符合项目事实的通用模板。
- 2026-09-29：按用户要求，将隐私政策和本说明从 docs 迁至项目根目录的 play-store/，集中存放 Google Play 上架准备材料；更新政策路径，避免与内部方案文档混放。该调整仅移动文件和更新路径，不改政策正文及 App 行为。


## 2026-10-26 读取范围变更记录

历史记录（2026-10-27 已回退）：当时按用户确认方案收窄过滤页查询，更新本地政策源文件与代码索引。历史来源、过滤名单和备份契约保持不变；此修改不表示政策网站已发布，构建/验证证据以 docs/clip_source_app_filter_plan.md 为准。

## 2026-10-27 回退记录

按用户要求恢复原安装应用列表及权限，同步中英文政策正文与本文件源码索引。移除历史/已保存包名补行，保留全部过滤规则、来源记录及备份数据；未发布政策网站，未提交 Play Console，也不把本次功能回退视为通过权限审核。
