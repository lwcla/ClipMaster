状态：草案

# App 自更新检查方案

## 当前状态

当前 App 源码仓库保持 private，尚未接入应用内版本检查、发布信息拉取或下载入口。内测分发目标是让用户在中国境内也能稳定看到新版本提示，并能通过可达链接获取 APK。

第一版采用 GitHub + 百度网盘共存方案：GitHub public release 仓库负责承载结构化版本信息和 release asset，百度网盘固定发布文件夹负责中国境内兜底可达性。源码、签名文件、密钥、构建脚本、token 和内部配置继续保留在 private 仓库或本地安全位置，不进入 public release 仓库。

## 目标与范围

- 新增轻量版本检查能力，优先从 GitHub public release 仓库读取 `update.json`。
- 在“我的”页提供“检查更新”入口，展示当前版本、检查状态、可用新版本和下载入口。
- GitHub 负责自动版本判断；百度网盘固定发布文件夹负责 GitHub 不可达、下载慢或用户手动获取 APK 时的兜底路径。
- 第一版只通过外部浏览器打开下载链接，不在 App 内直接下载、校验、安装 APK，不申请 `REQUEST_INSTALL_PACKAGES`。
- 第一版不做灰度、人群控制、后台静默下载、断点续传、启动页强弹窗或应用内安装器唤起。

## 用户体验

- “我的”页新增“检查更新”入口，默认展示当前版本号。
- 用户点击入口后立即检查更新，手动检查不受 24 小时限频影响。
- “我的”页可见时允许做一次轻量自动检查，但需要通过本地设置记录上次检查时间并按 24 小时限频；页面不可见时不启动检查任务。
- 检查到新版本时弹出更新提示，展示版本号、发布时间、更新日志、SHA256 摘要、GitHub 下载入口和百度网盘入口。
- 百度网盘入口优先使用固定发布文件夹；如果当前 manifest 提供具体版本分享链接，则同时展示版本链接和提取码。
- GitHub 检查失败时，不向用户表达为“更新失败”，而展示“暂时无法检查更新，可稍后重试或前往发布页查看”，并保留百度网盘固定发布文件夹入口。
- `forceUpdate` 或低于 `minSupportedVersionCode` 时，第一版只加强提示文案，不完全阻断用户进入 App，避免内测分发链路异常导致用户无法使用。

## 发布仓库与数据契约

建议新建 public 仓库 `ClipMaster-Releases`，只放发布产物和版本信息。Manifest 稳定地址使用 GitHub latest release asset：

```text
https://github.com/<githubOwner>/ClipMaster-Releases/releases/latest/download/update.json
```

每次发布创建 tag，例如 `v0.4.1`，上传以下资产：

- `update.json`
- `ClipMaster-v0.4.1.apk`
- `sha256.txt`

public 仓库 README 作为人可读发布页维护，至少包含最新版版本号、百度网盘固定发布文件夹、GitHub Release 入口、SHA256 校验说明、Android 安装说明、最近历史版本和“源码仓库仍为 private、此仓库只放发布产物”的说明。README 不承载任何密钥、token、签名配置、内部构建路径或完整用户反馈内容。

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
    "name": "百度网盘发布文件夹",
    "url": "https://pan.baidu.com/s/xxxx",
    "extractCode": "1234"
  },
  "downloads": [
    {
      "id": "baidu",
      "name": "百度网盘",
      "url": "https://pan.baidu.com/s/yyyy",
      "extractCode": "5678",
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
- `fallbackReleasePage` 表示固定发布文件夹，即使 GitHub 下载链接不可用，也应让用户有手动查看路径。
- `downloads` 可包含具体版本链接；展示顺序优先 `recommendedForChina = true` 的百度网盘，再展示 GitHub。
- App 解析模型使用 `@Serializable` 和显式 `@SerialName` 固定字段名；不把 Kotlin 属性名、枚举 `name` 或 ordinal 作为长期外部协议。

## 实现设计

- 新增 app 模块内的自更新领域包，保留在宿主 App 层，不下沉到 `base/general`，因为版本发布、渠道和下载入口属于 App 宿主契约。
- 新增 `AppUpdateManifestParser` 或等价纯逻辑协作者，负责 JSON 解析、schema 校验、package 校验、channel 校验、版本比较和下载源排序。
- 新增 `AppUpdateChecker` 或 `AppUpdateRepository`，通过现有 OkHttp 拉取 manifest，读取 `BuildConfig.VERSION_CODE` / `BuildConfig.VERSION_NAME` 并返回 sealed result。
- `MineVm` 只负责触发检查、持有 UI state、处理限频和分发打开外部链接事件；网络请求、解析和版本判断不得堆入 `MineVm`。
- 新增 `AppUpdateEntry` 和 `AppUpdateDialog`，入口复用我的页 `ListEntryCard` 风格，复杂展示状态使用具名 UI state。
- 本地设置只保存设备态：上次检查时间、可选的本次已提示版本；这些状态不进入 Room，不纳入 WebDAV 备份恢复。

## 发布流程

1. 使用 release 签名构建 APK，并确认 `versionCode` 单调递增。
2. 计算 APK SHA256，生成或更新 `sha256.txt`。
3. 创建 GitHub release tag，例如 `v0.4.1`。
4. 上传 APK、`sha256.txt` 和 `update.json` 到 public release 仓库。
5. 将同一 APK、`sha256.txt` 和更新说明同步到百度网盘固定发布文件夹；如需要，也可生成该版本单独分享链接并写入 `downloads`。
6. 用旧版本 App 手动执行一次“检查更新”，验证 manifest 可解析、弹窗文案正确、GitHub 链接和百度网盘入口均可打开。
7. 更新 public 仓库 README 的最新版、历史版本、百度网盘固定发布文件夹和 SHA256 说明，确保用户手动访问时也能找到正确 APK。
8. 发布完成后在方案文档变更记录或 release note 中记录版本、发布时间、主要变更和验证结果。

## 日志与诊断计划

- 检查开始：`INFO`，记录触发方式、当前 versionCode、是否强制检查、是否命中限频，不记录完整 manifest URL。
- HTTP 结果：`INFO` 或 `WARN`，记录状态码、耗时、是否可重试、reasonCode，不记录响应体和完整 URL 查询串。
- 解析结果：`INFO`，记录目标 versionCode、channel、是否有更新、是否 forceUpdate，不记录更新日志全文。
- 降级路径：`WARN`，GitHub manifest 不可达或解析失败时记录 reasonCode，并让 UI 展示固定发布文件夹入口。
- 打开外部链接：`INFO`，只记录 linkType，例如 `github`、`baidu`、`fallbackReleasePage`，不记录完整下载 URL、百度提取码或用户可恢复凭据。
- 高频自动检查按 24 小时限频，不输出重复成功日志，避免噪声。

失败 `reasonCode` 第一版约定：

- `network_error`：网络不可用、超时、DNS 或连接失败。
- `http_error`：GitHub manifest 请求返回非 2xx，日志只记录状态码。
- `invalid_json`：响应无法按 manifest JSON 解析。
- `schema_unsupported`：`schemaVersion` 不受当前 App 支持。
- `package_mismatch`：manifest 的 `packageName` 与当前应用不一致。
- `channel_mismatch`：manifest 的 `channel` 与当前 App 渠道不匹配。
- `version_not_newer`：manifest 可用但 `versionCode` 不高于当前版本。
- `no_download_source`：有更新但没有任何可展示下载源或固定发布页。
- `unknown_error`：未被以上分类覆盖的异常；实现时应尽量收敛为更具体 reasonCode。

## 测试验证

- 单元测试覆盖 manifest parser：正常更新、无更新、低版本、缺字段、非法 JSON、schema 不支持、channel 不匹配、packageName 不匹配、下载源为空、百度/GitHub 排序、固定发布文件夹存在。
- 单元测试覆盖 checker/result：HTTP 200、非 2xx、超时或 IOException、解析失败、版本比较边界和失败 `reasonCode` 映射。
- 如 `MineVm` 接入状态流较多，补 ViewModel 测试：手动检查 loading/success/failure、24 小时限频、强制检查跳过限频、GitHub 失败后仍提供固定发布文件夹入口。
- 人工验证：中国网络下 GitHub manifest 不可达时，UI 提示不制造焦虑且百度网盘固定发布文件夹入口可打开。
- 人工验证：public 仓库 README 在浏览器中可读，能找到最新版、历史版本、SHA256 和百度网盘固定发布文件夹。
- 人工验证：当前版本不弹更新；新版本弹窗展示更新日志；`forceUpdate` 只加强提示不阻断使用；外部浏览器可打开 GitHub 和百度网盘入口。
- 实现后运行 `./gradlew :app:testDebugUnitTest`、`./gradlew :app:compileDebugKotlin`、`git diff --check`；发布前评估并运行 release/R8 验证，确认序列化模型在混淆后仍可解析 manifest。

## 已知取舍

- 百度网盘不是结构化 API 渠道，App 不依赖百度网盘做自动版本判断，只把它作为用户可达下载路径。
- GitHub private 仓库不适合作为客户端版本检查源，因为客户端不能内置 token；public release 仓库只暴露已经准备外发的 APK。
- 第一版展示 SHA256 摘要但不做 App 内下载校验；后续若接入直接下载，必须在下载完成后做 SHA256 校验并重新评估安装权限、文件存储和失败恢复。
- 第一版不做启动时全局检查，优先把任务绑定到“我的”页可见生命周期，降低不可见页面网络消耗。

## 开放问题

- 是否需要在后续引入更稳定的国内静态文件源，例如对象存储、Gitee Pages 或 GitCode Pages，用于镜像 `update.json`。
- 是否需要增加“忽略本版本”能力；若增加，需要定义忽略版本的本地设置和强制更新时的覆盖规则。
- 是否需要把固定发布文件夹入口放到关于页或错误弹窗外的常驻位置，方便 GitHub 长时间不可达时用户主动查看。

## 变更记录

- 2026-05-24：新增 App 自更新检查方案草案；原因是内测分发需要在 GitHub private 源码仓库之外建立可公开访问的版本检查和下载路径。
- 2026-05-24：补充 GitHub manifest 国内兜底、百度网盘固定发布文件夹、`channel` 字段、强制更新非阻断策略、固定发布流程和低焦虑失败文案；原因是需要提高中国境内网络环境下的可达性和维护稳定性。
- 2026-05-24：补充 public 仓库 README 发布页要求和失败 `reasonCode` 第一版约定；原因是需要让用户手动访问发布仓库时也能找到正确 APK，并让后续日志排查拥有稳定归因字段。
