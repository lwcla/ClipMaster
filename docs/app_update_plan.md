状态：已完成

# App 自更新检查方案

## 当前状态

当前 App 源码仓库保持 private。应用内自更新检查第一版已接入“我的”页：用户可手动检查更新，页面可见时按 24 小时限频自动做一次轻量检查，检查结果只展示发布信息和外部下载入口，不在 App 内下载或安装 APK。

当前采用 GitHub + Gitee 双发布源方案：Gitee release 仓库负责中国境内优先检查和下载，GitHub public release 仓库作为国际网络和原有发布链路兜底。源码、签名文件、密钥、构建脚本、token 和内部配置继续保留在 private 仓库或本地安全位置，不进入 public release 仓库。

## 目标与范围

- 新增轻量版本检查能力，优先从 Gitee release API 读取最新 Release 并定位 `update.json` 附件，失败后再从 GitHub public release 仓库读取 `update.json`。
- 在“我的”页提供“检查更新”入口，展示当前版本、检查状态、可用新版本和下载入口。
- Gitee 和 GitHub 同步承载 `update.json`、APK 与 `sha256.txt`；App 端按 Gitee 优先、GitHub 兜底的顺序检查，下载入口同时展示两端地址。
- 第一版只通过外部浏览器打开下载链接，不在 App 内直接下载、校验、安装 APK，不申请 `REQUEST_INSTALL_PACKAGES`。
- 第一版不做灰度、人群控制、后台静默下载、断点续传、启动页强弹窗或应用内安装器唤起。

## 用户体验

- “我的”页新增“检查更新”入口，默认展示当前版本号。
- 用户点击入口后立即检查更新，手动检查不受 24 小时限频影响。
- “我的”页可见时允许做一次轻量自动检查，但需要通过本地设置记录上次检查时间并按 24 小时限频；页面不可见时不启动检查任务。
- 检查到新版本时弹出更新提示，展示版本号、发布时间、更新日志、SHA256 摘要、Gitee 下载入口和 GitHub 下载入口。
- Gitee 下载入口标记为中国境内推荐；GitHub 入口作为备用下载路径。
- Gitee manifest 不可用时自动尝试 GitHub；两个源都不可用时，不向用户表达为“更新失败”，而展示“暂时无法检查更新，可稍后重试或前往发布页查看”，并保留可配置的发布页入口。
- `forceUpdate` 或低于 `minSupportedVersionCode` 时，第一版只加强提示文案，不完全阻断用户进入 App，避免内测分发链路异常导致用户无法使用。

## 发布仓库与数据契约

建议在 GitHub 和 Gitee 各维护一个 public 发布仓库，只放发布产物和版本信息。当前默认更新源通过 BuildConfig 注入：

```text
Gitee release API: https://gitee.com/api/v5/repos/clip-master-2/clip-master-releases/releases/latest
GitHub manifest: https://github.com/clip-master-2/ClipMaster-Releases/releases/latest/download/update.json
```

如果实际发布仓库、发布页或渠道不同，可通过 Gradle property 覆盖：`appUpdateGiteeRepo`、`appUpdateGithubRepo`、`appUpdateGiteeReleaseApiUrl`、`appUpdateGithubManifestUrl`、`appUpdateGiteeReleasePageUrl`、`appUpdateGithubReleasePageUrl`、`appUpdateChannel`。仓库配置使用 `owner/repo` 形式，便于后续切换到其他账号或组织。

每次发布创建 tag，例如 `v0.4.1`，上传以下资产：

- `update.json`
- `ClipMaster-v0.4.1.apk`，由 app 模块打包输出规则按当前 `versionName` 自动生成；非 release 构建会追加变体名，例如 `ClipMaster-v0.4.1-debug.apk`
- `sha256.txt`

public 仓库 README 作为人可读发布页维护，至少包含最新版版本号、Gitee Release 入口、GitHub Release 入口、SHA256 校验说明、Android 安装说明、最近历史版本和“源码仓库仍为 private、此仓库只放发布产物”的说明。README 不承载任何密钥、token、签名配置、内部构建路径或完整用户反馈内容。

`update.json` 第一版契约如下：

```json
{
  "schemaVersion": 1,
  "channel": "internal",
  "packageName": "com.cla.clip.master",
  "versionCode": 22,
  "versionName": "0.4.1",
  "minSupportedVersionCode": 21,
  "forceUpdate": false,
  "publishedAt": "2026-05-24T20:00:00+08:00",
  "sha256": "APK_SHA256",
  "changelog": ["修复若干问题", "优化稳定性"],
  "fallbackReleasePage": {
    "name": "Gitee Release",
    "url": "https://gitee.com/<giteeOwner>/clip-master-releases/releases/tag/v0.4.1"
  },
  "downloads": [
    {
      "id": "gitee",
      "name": "Gitee Release",
      "url": "https://gitee.com/<giteeOwner>/clip-master-releases/releases/download/<attachFileId>/ClipMaster-v0.4.1.apk",
      "recommendedForChina": true
    },
    {
      "id": "github",
      "name": "GitHub Release",
      "url": "https://github.com/<githubOwner>/ClipMaster-Releases/releases/download/v0.4.1/ClipMaster-v0.4.1.apk",
      "recommendedForChina": false
    }
  ]
}
```

字段约束：

- `schemaVersion` 当前只接受 `1`，未知版本按 `InvalidManifest` 处理。
- `channel` 当前只接受 `internal`，为后续 `stable`、`beta`、`internal` 分流预留；不匹配时忽略该 manifest，不提示更新。
- `packageName` 必须等于当前应用包名，防止误读其他应用发布信息。
- `versionCode` 大于当前 `BuildConfig.VERSION_CODE` 才视为有更新。
- `fallbackReleasePage` 表示发布页入口，即使具体下载链接不可用，也应让用户有手动查看路径。
- `downloads` 可包含具体版本链接；展示顺序优先 `recommendedForChina = true` 的 Gitee，再展示 GitHub。
- App 解析模型使用 `@Serializable` 和显式 `@SerialName` 固定字段名；不把 Kotlin 属性名、枚举 `name` 或 ordinal 作为长期外部协议。

## 实现设计

- 新增 app 模块内的自更新领域包，保留在宿主 App 层，不下沉到 `base/general`，因为版本发布、渠道和下载入口属于 App 宿主契约。
- 已新增 `AppUpdateManifestParser` 纯逻辑协作者，负责 JSON 解析、schema 校验、package 校验、channel 校验、版本比较、下载源排序和从 Gitee Release 响应中定位 `update.json` 附件下载 URL。
- 已新增 `AppUpdateChecker`、`AppUpdateManifestFetcher`、`OkHttpAppUpdateManifestFetcher`、`AppUpdateLogger` 和 `AppUpdateConfigFactory`，通过现有短超时 `@LinkPreviewClient` OkHttp 拉取 manifest，读取 `BuildConfig.VERSION_CODE` / `BuildConfig.VERSION_NAME` 并返回 sealed result。`AppUpdateChecker` 按配置源顺序尝试，默认 Gitee 优先、GitHub 兜底。
- `MineVm` 只负责触发检查、持有 UI state、处理限频和分发打开外部链接事件；网络请求、解析和版本判断不得堆入 `MineVm`。
- 已新增 `AppUpdateEntry`、`AppUpdateDialog` 和 `AppUpdateUiState`，入口复用我的页 `ListEntryCard` 风格，复杂展示状态使用具名 UI state。
- 本地设置只保存设备态：上次检查时间 `AppSetting.appUpdateLastCheckAt`。该状态不进入 Room，不纳入 WebDAV 备份恢复，也不触发 backup dirty。

## 发布流程

1. 使用 release 签名构建 APK，并确认 `versionCode` 单调递增；打包产物文件名应自动带上当前 `versionName`，例如 `ClipMaster-v0.4.1.apk`。
2. 推荐使用 `scripts/publish_github_release.sh` 自动完成 GitHub + Gitee 双端发布：脚本会执行 `:app:assembleRelease`，读取 release APK metadata，计算 APK SHA256，生成 `build/github-gitee-release/update.json` 与 `build/github-gitee-release/sha256.txt`，按 `v<versionName>` 创建或复用两端 Release，并上传 APK、`sha256.txt` 和 `update.json`。
3. 脚本默认先上传 Gitee APK，读取 Gitee API 返回的 `browser_download_url` 后重新生成最终 `update.json`，再把同一份 manifest 同步到 Gitee 和 GitHub，避免两个源的下载链接不一致。
4. 脚本使用 GitHub token 和 Gitee token 调用 REST API。日常发布推荐在用户级 `~/.gradle/gradle.properties` 中配置 `githubToken=<token>` 与 `giteeToken=<token>`，这样不需要每次输入，也不会随项目源码上传；脚本也兼容读取项目 `local.properties`。禁止把真实 token 放进项目 `gradle.properties`，因为该文件会进入源码仓库。临时覆盖可使用 `GITHUB_TOKEN` / `GITEE_TOKEN` 环境变量，且环境变量优先级最高。token 不写入仓库、不写入生成文件、不打印到日志。
5. 如需先检查生成内容，可运行 `scripts/publish_github_release.sh --dry-run`，该模式只构建 APK 和生成发布文件，不调用 GitHub/Gitee API；如需临时单端发布，可用 `--skip-github` 或 `--skip-gitee`。
6. 用旧版本 App 手动执行一次“检查更新”，验证 Gitee release API 可解析 `update.json` 附件、GitHub manifest 兜底可用、弹窗文案正确、Gitee 和 GitHub 下载入口均可打开。
7. 更新 public 仓库 README 的最新版、历史版本、Gitee/GitHub Release 入口和 SHA256 说明，确保用户手动访问时也能找到正确 APK。
8. 发布完成后在方案文档变更记录或 release note 中记录版本、发布时间、主要变更和验证结果。

## 日志与诊断计划

- 检查开始：`INFO`，记录触发方式、当前 versionCode、是否强制检查、是否命中限频，不记录完整 manifest URL。
- HTTP 结果：`INFO` 或 `WARN`，记录状态码、耗时、是否可重试、reasonCode，不记录响应体和完整 URL 查询串。
- 解析结果：`INFO`，记录目标 versionCode、channel、是否有更新、是否 forceUpdate，不记录更新日志全文。
- 降级路径：`WARN`，单个更新源不可达、Gitee Release 中缺少 `update.json` 附件或解析失败时记录 `sourceId`、reasonCode 和状态码；下一个源可用时继续检查，全部失败时让 UI 展示发布页入口。
- 打开外部链接：`INFO`，只记录 linkType，例如 `github`、`gitee`、`releasePage`，不记录完整下载 URL、token 或用户可恢复凭据。
- 高频自动检查按 24 小时限频，不输出重复成功日志，避免噪声。
- 发布脚本日志：仅输出构建阶段、生成文件路径、tag、repo、附件名称和最终 release 地址；禁止输出 `GITHUB_TOKEN`、`githubToken`、签名密码、完整构建环境变量或响应体中的敏感字段。GitHub/Gitee API 失败时输出 HTTP 状态码和低敏 `message`/`errors` 摘要，其中 GitHub 422 会额外提示 release/tag、同名附件、token 权限和仓库状态等常见排查方向；Gitee 按 tag 查询 Release 返回 JSON `null` 时应视为 Release 不存在并继续创建；Gitee 返回空响应、HTML 登录页或其它非 JSON 响应时，脚本应输出“响应体为空/响应体不是 JSON”的可读诊断，不暴露 Python traceback；日志不得输出 token 或完整敏感配置。

失败 `reasonCode` 第一版约定：

- `network_error`：网络不可用、超时、DNS 或连接失败。
- `http_error`：GitHub manifest、Gitee release API 或 Gitee `update.json` 附件请求返回非 2xx，日志只记录状态码。
- `invalid_json`：响应无法按 manifest JSON 解析。
- `schema_unsupported`：`schemaVersion` 不受当前 App 支持。
- `package_mismatch`：manifest 的 `packageName` 与当前应用不一致。
- `channel_mismatch`：manifest 的 `channel` 与当前 App 渠道不匹配。
- `version_not_newer`：manifest 可用但 `versionCode` 不高于当前版本。
- `no_download_source`：有更新但没有任何可展示下载源或固定发布页。
- `unknown_error`：未被以上分类覆盖的异常；实现时应尽量收敛为更具体 reasonCode。

## 测试验证

- 单元测试覆盖 manifest parser：正常更新、无更新、低版本、缺字段、非法 JSON、schema 不支持、channel 不匹配、packageName 不匹配、下载源为空、Gitee/GitHub 排序、发布页存在、Gitee Release 中提取 `update.json` 附件 URL。
- 单元测试覆盖 checker/result：HTTP 200、非 2xx、超时或 IOException、解析失败、Gitee release API 到 `update.json` 的二段拉取、Gitee 不可用时回退 GitHub、版本比较边界和失败 `reasonCode` 映射。
- 如 `MineVm` 接入状态流较多，补 ViewModel 测试：手动检查 loading/success/failure、24 小时限频、强制检查跳过限频、两个源失败后仍提供发布页入口。
- 人工验证：中国网络下 Gitee release API 可达时能优先发现更新；Gitee 不可达而 GitHub 可达时仍能检查更新。
- 人工验证：public 仓库 README 在浏览器中可读，能找到最新版、历史版本、SHA256 和 Gitee/GitHub Release 入口。
- 人工验证：当前版本不弹更新；新版本弹窗展示更新日志；`forceUpdate` 只加强提示不阻断使用；外部浏览器可打开 Gitee 和 GitHub 入口。
- 实现后运行 `./gradlew :app:testDebugUnitTest`、`./gradlew :app:compileDebugKotlin`、`git diff --check`；发布前评估并运行 release/R8 验证，确认序列化模型在混淆后仍可解析 manifest。
- 发布脚本验证：运行 `bash -n scripts/publish_github_release.sh`、`scripts/publish_github_release.sh --help`、`scripts/publish_github_release.sh --dry-run`、`scripts/publish_github_release.sh --self-test-json-errors` 和 `git diff --check`，确认脚本语法、token 配置说明、release APK 构建、`update.json`/`sha256.txt` 生成、发布路径推导和非 JSON API 响应诊断正确；使用无效临时 token 做受控失败验证时，应能看到 GitHub/Gitee 返回的状态码与 `message`，但不能输出 token。真实上传需要在用户级 `~/.gradle/gradle.properties` 配置有效 `githubToken` 与 `giteeToken`，或通过 `GITHUB_TOKEN` / `GITEE_TOKEN` 临时覆盖后再执行非 dry-run。

当前已新增 `AppUpdateManifestParserTest` 和 `AppUpdateCheckerTest`，覆盖 parser/checker 第一版核心契约；本次实现已运行 `./gradlew :app:testDebugUnitTest --tests '*AppUpdate*'`，结果通过。完整 app 单元测试、编译和 diff 检查以任务最终验证结果为准。

## 已知取舍

- GitHub private 仓库不适合作为客户端版本检查源，因为客户端不能内置 token；public release 仓库只暴露已经准备外发的 APK。
- 第一版展示 SHA256 摘要但不做 App 内下载校验；后续若接入直接下载，必须在下载完成后做 SHA256 校验并重新评估安装权限、文件存储和失败恢复。
- 第一版不做启动时全局检查，优先把任务绑定到“我的”页可见生命周期，降低不可见页面网络消耗。
- `update.json` 解析 DTO 使用 `@Serializable` 和 `@SerialName` 固定外部字段名；当前 release/R8 验证未在本次任务中执行，发布前仍需补跑混淆构建确认序列化模型可解析。

## 开放问题

- 是否需要在后续引入更稳定的国内静态文件源，例如对象存储、Gitee Pages 或 GitCode Pages，用于镜像 `update.json`，以减少 Gitee Release API 二段拉取复杂度。
- 是否需要增加“忽略本版本”能力；若增加，需要定义忽略版本的本地设置和强制更新时的覆盖规则。
- 是否需要把发布页入口放到关于页或错误弹窗外的常驻位置，方便两个自动源都不可达时用户主动查看。

## 变更记录

- 2026-05-24：新增 App 自更新检查方案草案；原因是内测分发需要在 GitHub private 源码仓库之外建立可公开访问的版本检查和下载路径。
- 2026-05-24：补充 GitHub manifest 国内兜底、历史备用下载路径、`channel` 字段、强制更新非阻断策略、固定发布流程和低焦虑失败文案；原因是需要提高中国境内网络环境下的可达性和维护稳定性。
- 2026-05-24：补充 public 仓库 README 发布页要求和失败 `reasonCode` 第一版约定；原因是需要让用户手动访问发布仓库时也能找到正确 APK，并让后续日志排查拥有稳定归因字段。
- 2026-05-25：落地 App 自更新检查第一版并将状态更新为已完成；原因是已完成 manifest parser/checker、Mine 页入口与弹窗、24 小时设备态限频、外部链接打开和 parser/checker 单元测试，第一版仍保持不下载、不校验、不安装 APK 的边界。
- 2026-05-25：补充 APK 打包输出命名规则；原因是发布产物需要自动带上当前 `versionName`，避免手动重命名时漏改版本号或与 `update.json` 下载链接不一致。
- 2026-05-25：新增 GitHub Release 自动发布脚本说明；原因是需要在 release APK 构建后自动生成 `update.json`、`sha256.txt` 并上传到默认发布仓库，减少手动上传和 manifest 下载链接不一致的风险。
- 2026-05-26：默认发布仓库更新为 Gitee `clip-master-2/clip-master-releases` 与 GitHub `clip-master-2/ClipMaster-Releases`；原因是实际发布仓库已迁移到新的账号和仓库地址。
- 2026-05-25：补充发布脚本 token 本地配置方式；原因是日常发布不应每次手动输入 token，同时真实 token 应只保留在用户级 `~/.gradle/gradle.properties`、项目 `local.properties` 或环境变量中，避免进入会上传到 GitHub 的项目 `gradle.properties` 和发布产物。
- 2026-05-26：增强 GitHub 发布脚本错误诊断；原因是 GitHub API 422 等失败不能只显示 `curl` 状态，需要输出低敏的 GitHub `message`/`errors` 摘要和常见排查方向，便于定位 release、附件或 token 权限问题。
- 2026-05-26：自更新发布源从 GitHub + 历史备用下载路径改为 GitHub + Gitee，并改造脚本为一键双端发布；原因是需要去掉非结构化网盘，同时为中国境内网络提供可结构化读取的更新源，且发布仓库地址需要可配置。
- 2026-05-26：增强 Gitee 发布脚本响应解析诊断；原因是 Gitee Release 查询可能返回 JSON `null`、空响应或非 JSON 内容，其中 `null` 表示 tag release 不存在，应继续创建，其它异常响应应输出可读错误而不是 Python traceback。
