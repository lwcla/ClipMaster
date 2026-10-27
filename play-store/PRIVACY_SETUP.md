# ClipMaster 隐私政策维护与发布说明

状态：已完成（本地 HTML 编写；尚未托管发布或完成 Play 审核）

更新日期：2026-09-29

## 产物与范围

- 用户隐私政策：`play-store/privacy-policy.html`，中英双语单文件，无 JavaScript、外部字体、分析脚本或远程图片，支持移动端、深色模式和打印。
- 开发者与联系邮箱：用户明确提供的 `cla`、`cla6939@gmail.com`。
- 应用：ClipMaster / 剪贴板助手，`com.cla.clip.master`，当前版本配置 `0.6.0` / `33`。
- 编写基线：本地工作区 `44035e6`，读取时工作区干净；按当前 `gradle.properties` 的 Play 分发配置编写。没有修改应用功能、Manifest、SDK 配置或备份行为。
- 根据用户要求，将 `privacy-policy-generate` 默认 Markdown 政策产物改为 HTML。正文以实际代码为依据，不沿用其“无数据收集”“所有数据仅在本机”或默认日志收集模板。
- 没有现成隐私政策可复用；已有 `docs/flows/` 是内部流程图生成产物，职责不同，未改动其生成器或 HTML。

## 当前渠道基线

`gradle.properties` 当前为：

| 配置 | 值 | 对政策的影响 |
| --- | --- | --- |
| `appDistribution` | `play` | 按 Google Play 版本描述 |
| `enableAdmobReleaseAdFeature` | `true` | 披露 Google AdMob / UMP |
| `enableCsjReleaseAdFeature` | `false` | 不把穿山甲列为当前版本的数据接收方 |
| `enableUniadReleaseAdFeature` | `false` | 不把 uni-ad 列为当前版本的数据接收方 |
| `enableMagnetFeature` | `false` | 不披露当前版本未启用的磁力搜索、Torznab 或 DoH 功能 |

这些是静态配置事实，不代表最终 AAB 已核对。发布命令、CI 或其他属性可覆盖配置；必须在正式包内容变化后同步更新政策。

## 源码依据

| 主题 | 主要源码位置 | 核实结果 |
| --- | --- | --- |
| 名称与版本 | `base/general/src/main/res/values/strings.xml`；`app/build.gradle.kts` | 中文名为“剪贴板助手”；版本与包名见上文 |
| 剪贴内容与来源 | `app/src/main/java/com/cla/clip/master/utils/ClipHelper.kt`；`MainActivity.kt`；`shizuku/src/main/` | 文本、来源包名/名称/图标、时间及链接摘要入库；前台读取与 Shizuku 后台路径分开 |
| 应用列表 | `app/src/main/java/com/cla/clip/master/installedapps/PackageManagerInstalledAppReader.kt` | 来源过滤页读取安装应用列表；完整列表仅为内存状态，保存的过滤包名和来源记录可进入备份；未安装的保存规则不补显示行 |
| 自动链接请求 | `app/src/main/java/com/cla/clip/master/utils/ClipHelper.kt`；`utils/LinkMetaParser.kt` | 保存链接后可自动解析远程页面；携带 URL、User-Agent、语言、Referer；没有假称“只有点击才联网” |
| 网页状态与下载 | `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeWebViewLayer.kt`；`entity/VideoCandidate.kt` | JavaScript、DOM storage、第一方/第三方 Cookie 已开启；媒体下载可携带所需 Cookie 等请求信息 |
| 广告 | `feature/ad-admob/src/sdk/java/com/cla/clip/feature/ad/admob/AdmobSdkLoader.kt`；`AdmobPrivacyController.kt`；`app/.../ad/AdHostEnvironment.kt` | GMA Next-Gen 1.4.0、UMP 4.0.0；隐私信息刷新与广告请求分开；广告请求构造没有传入剪贴正文或应用清单 |
| 广告隐私入口 | `app/src/main/java/com/cla/clip/master/ui/page/mine/MinePage.kt` | 只有 UMP 要求提供隐私选项时才显示入口；没有承诺所有地区均显示同一表单 |
| 本地与 WebDAV 备份 | `base/general/src/main/java/com/cla/clip/base/general/backup/BackupSnapshotExporter.kt`；`BackupEntityMappers.kt`；`WebDavClient.kt`；`backup/settings/BackupSettingsRepository.kt` | 记录及允许的设置可导出；自定义备份排除 WebDAV 凭据及媒体请求 Cookie；备份包未加密，自动备份默认关闭，WebDAV 默认 HTTPS，可显式允许 HTTP |
| 系统备份 | `app/src/main/AndroidManifest.xml`；`res/xml/backup_rules.xml`；`res/xml/data_extraction_rules.xml` | `allowBackup=true`；主剪贴数据库未整体排除，不能宣称不会被系统备份。宿主 DataStore 在 Auto Backup、Android 12+ 云备份与设备迁移规则中均被排除，设备绑定凭据和授权不随该文件迁移 |
| 删除与保留 | `base/general/.../config/clip/ClipSettingsRepository.kt`；`app/.../work/RecycleBinCleanupWorker.kt` | 回收站默认 30 天；后台清理受调度影响；备份和共享媒体的删除是独立操作 |
| 设备日志 | `base/general/src/main/java/com/cla/clip/base/general/utils/Logcat.kt` | 使用 Android 日志；没有据此虚构独立云端崩溃上报服务 |
| OCR | `app/build.gradle.kts`；`ClipHelper.kt` | `implementation(libs.mlkit.ocr)` 被注释；图像剪贴/OCR 为未启用预留方法，未把版本目录中的 ML Kit 定义当成已上线功能 |

## 发布前需确认的实际边界

1. **核对最终 AAB。** 2026-10-27 已按用户要求恢复安装应用扫描和广泛包可见性，并重新构建 Release/R8 AAB；实际 Manifest 确认包含 QUERY_ALL_PACKAGES、保留 Shizuku 查询及自身启动入口、不含此次桌面查询声明。311+7 项测试通过，产物 hash 与证据记录于 `docs/clip_source_app_filter_plan.md`。2026-10-26 的有限权限 AAB 只代表已回退版本；尚未做宿主真机 UI 或抓包，后续正式包变化仍须重新核对。
2. **接入政策入口。** 本次过滤功能改动未增加通用隐私政策入口。`MinePage` 中的 UMP 隐私入口不等于本政策链接；需确认应用内和 Play Console 均提供本政策。
3. **受限权限。** 主 Manifest 已恢复 `QUERY_ALL_PACKAGES`，并保留“自用/私有分发；上架需重新评估”的注释；此次 MAIN + LAUNCHER queries 已撤销，Shizuku 管理器指定包查询保留。政策披露不能代替 Google Play 对权限必要性和资格的审核；照片/视频广泛访问、Shizuku/后台剪贴读取也应按实际包单独核对。
4. **自动链接预览与后台剪贴。** 它们可能在用户未点击链接或正在使用其他 App 时处理数据。应核查应用内显著披露和同意时机；本政策不是这些交互的替代品。本次未修改交互。
5. **系统备份。** 政策已如实描述主库可能被系统备份。若发布目标要求剪贴内容严格仅存本机，需另行调整系统备份规则，之后同步更新政策。
6. **备份与删除。** 已存在的快照可能保留已删除记录；未对本地文件、远端备份或系统备份承诺联动删除。未承诺完整数据库/备份端到端加密。
7. **广告与年龄设置。** 发布前确认 AdMob 后台隐私消息、广告合作方/中介配置、目标年龄和 Play 目标受众。文档没有自行承诺“面向儿童”“无个性化广告”“不收集任何数据”或虚构已认证合规。
8. **法律与运营事实。** 开发者应核对实际服务地区、联系方式和用户请求处理流程；政策草稿不等于法律审查或商店通过结论。

## 托管说明

将 `privacy-policy.html` 单独放到您控制的静态站点，获得无需登录、全球可访问、不可由访问者编辑的 HTTPS 页面，再填入 Play Console，并在应用内提供链接或正文。

如果使用 GitHub Pages，建议仅发布该 HTML 或使用专门的公开政策仓库，不要为了发布这一页直接公开整个项目或全部 `docs/`。本仓库 `docs/` 还包含内部方案文档；是否公开这些文档应另行决定。本次未启用 Pages、推送、部署、修改仓库可见性或写入 Play Console。

发布后的检查：

- 用无登录浏览器及手机打开最终 URL，验证两种语言、邮箱、章节跳转和可读性。
- 确认页面可公开访问且不是 PDF、登录页、编辑页面或地域限制页。
- 对照实际 AAB、SDK 文档和运行行为填写 Data safety，不直接将“本机处理”或“用户主动上传”简单等同于整份表单的“无收集”。
- 若日后启用磁力搜索、其他广告源、OCR 或新的云端服务，先重新核查数据流再修改政策。

## 已安装的 skill

- `privacy-policy-generate`：来自 `hitoshura25/claude-devtools` 的 `skills/privacy-policy-generate`；已完整安装，本文使用其源码扫描、信息确认与政策生成流程，将 Bash 示例改为 PowerShell 兼容操作。
- `play-policy-insights`：来自官方 `android/skills` 的 `play/play-policy-insights`；包含 `SKILL.md`、`scripts/`、`resources/`，已完整安装。本次没有运行其完整自动审计流程，也不将本文的源码核查描述为该工具的审计报告。
- 项目文档流程：使用 `repo-docs-logs`、`repo-final-self-check`；参照 `repo-unit-test-gate` 判断仅为静态文档，无需新增 Android 单元测试或 Gradle 构建。

## 日志与诊断计划

本次无 App 网络、文件、数据库或运行逻辑修改，不新增 App 运行时日志。HTML 不加载脚本、字体、远程图片或统计资源；打开外部政策链接或发送邮件由用户主动触发。静态托管服务仍可能按其自身政策记录访问日志。

验证仅检查文件、HTML 结构、锚点、双语完整性、窄屏布局与 Git diff，不读取真实剪贴内容、WebDAV 凭据、广告 ID 或用户数据库。

## 验证与规则例外

- 静态文档改动，不新增应用组件、类、方法、业务变量或 Android 字符串资源；CSS/HTML 使用简体中文说明注释。
- 不需要业务单元测试、Gradle 编译、R8 或真机验证；这些未执行，不能据此认定正式包合规。
- 未使用 `repo-doc-flow-html`：本文件是对外政策，不是 `docs/flows/` 的 Mermaid 方案流程图。
- 本次仅安装现成第三方 skill，没有创建或修改项目专用 skill。
- HTML 静态校验通过：20 个章节、中英两种语言、13 个链接，标签闭合、唯一 ID、内部锚点、邮箱与包名、无占位符、无脚本及自动加载的外部资源均通过。
- 内置浏览器预览通过：默认视口和 390px 手机视口均可阅读；手机视口实测内容宽度与滚动宽度相等（375px，扣除滚动条），无横向溢出；英文跳转定位正确。深色模式显示已人工检查。
- 两个 skill 的目录与配套文件检查通过：`privacy-policy-generate` 2 个文件；`play-policy-insights` 14 个文件，包含 orchestrator、报告生成器和资源目录。
- 项目复制后另行检查文件哈希、Git 状态与 `git diff --check`，结果以任务交付说明为准。

## 参考资料（2026-09-29 核对）

- [Google Play User Data / 隐私政策要求](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [GMA Next-Gen SDK 数据披露](https://developers.google.com/admob/android/next-gen/privacy/play-data-disclosure?hl=en)：官方页面说明适用于其最新版本；最终提交时仍需核对项目固定版本和配置。
- [Google 隐私政策](https://policies.google.com/privacy)
- [privacy-policy-generate 源码](https://github.com/hitoshura25/claude-devtools/tree/main/skills/privacy-policy-generate)
- [play-policy-insights 源码](https://github.com/android/skills/tree/main/play/play-policy-insights)

## 变更记录

- 2026-09-29：随宿主设置介质替换，将源码依据及系统备份排除说明更新为 DataStore 的实际文件和规则；政策正文未因这次介质替换改变数据用途。
- 2026-09-29：首次创建中英双语政策 HTML 与维护发布说明；根据当前 Play 配置和实际数据流披露自动链接联网、来源应用、后台剪贴、未加密备份、系统备份和 AdMob，避免套用不符合项目事实的通用模板。
- 2026-09-29：按用户要求，将隐私政策和本说明从 docs 迁至项目根目录的 play-store/，集中存放 Google Play 上架准备材料；更新政策路径，避免与内部方案文档混放。该调整仅移动文件和更新路径，不改政策正文及 App 行为。


## 2026-10-26 读取范围变更记录

历史记录（2026-10-27 已回退）：当时按用户确认方案收窄过滤页查询，更新本地政策源文件与代码索引。历史来源、过滤名单和备份契约保持不变；此修改不表示政策网站已发布，构建/验证证据以 docs/clip_source_app_filter_plan.md 为准。

## 2026-10-27 回退记录

按用户要求恢复原安装应用列表及权限，同步中英文政策正文与本文件源码索引。移除历史/已保存包名补行，保留全部过滤规则、来源记录及备份数据；未发布政策网站，未提交 Play Console，也不把本次功能回退视为通过权限审核。
