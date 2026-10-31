状态：实现中

# App 自更新检查方案

## 当前状态

2026-10-29 发布时通过 GitHub 连接核实，当前 App 源码仓库 `lwcla/ClipMaster` 的可见性为 public，归属当前登录用户；本轮不变更仓库可见性，原文 private 描述已按实际配置修正。应用内自更新检查第一版已接入“我的”页：用户可手动检查更新，页面可见时按 24 小时限频自动做一次轻量检查，检查结果只展示发布信息和外部下载入口，不在 App 内下载或安装 APK。

当前采用 GitHub + Gitee 双发布源方案：Gitee release 仓库负责中国境内优先检查和下载，GitHub public release 仓库作为国际网络和原有发布链路兜底。发行脚本仅上传 APK、更新清单和摘要文件；源码、签名文件、密钥、构建脚本、token 和内部配置不作为附件进入 public release 仓库，敏感凭据仍要求保存在本地安全位置。

## 目标与范围

### 0.9.18 版本准备与全部改动提交范围（2026-10-31，已完成）

- 按用户要求将 `versionName` 从 `0.9.17` 递增至 `0.9.18`，`versionCode` 从 `53` 递增至 `54`，提交当前全部修改并普通推送到源码仓库 `main`。既有 Actions 按版本变化执行自动构建发布，预期标签为 `v0.9.18-54`；源码推送完成不代表云端发布完成。
- 本版纳入 AdMob 广告离屏回绑顺序修复、4 项设备回归、测试专用缓存夹具及 Compose 测试依赖；完整根因、复用、测试先行证据和设备验收边界以 [广告主方案](ad_module_plan.md#备份页广告离屏回绑修复方案2026-10-31已完成) 与 [单元测试方案](unit_test_plan.md) 为准。
- 复用 app 静态版本声明、base/general 派生版本、BuildConfig、APK 命名和版本判定脚本。版本递增仅修改构建元数据，不新增组件、类、方法或独立业务规则，不新增版本常量镜像单测或采用 TDD；版本码简体中文注释同步更新。
- 本轮验证计划：运行 app、base/general、ad-api、ad-admob 的 JVM 单测，app Debug Kotlin 编译、AdMob Android 测试 Kotlin/Java 编译、Release BuildConfig 生成、8 项版本脚本测试、真实版本变化判定及 `git diff --check`。广告修复的设备与 Release/R8 验证已有主方案记录，本轮版本准备不重复设备安装与 R8；未改变生产反射、序列化或 keep 契约。
- 日志与诊断计划：沿用现有 Gradle、版本判定脚本和 Actions 输出，不新增运行时日志，不输出凭据、广告素材或用户内容。没有新增持久化数据、设置、备份协议或 dirty 状态，不调整备份恢复逻辑。
- 提交范围：全部 8 个版本、源码、测试与文档文件；起点 `5411746` 与已抓取的 `origin/main` 一致，保留用户现有修改，使用快进合并与普通推送，不改写历史。
- 本地验证：`.\gradlew.bat :base:general:testDebugUnitTest :feature:ad-api:testDebugUnitTest :feature:ad-admob:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :feature:ad-admob:compileDebugAndroidTestKotlin :feature:ad-admob:compileDebugAndroidTestJavaWithJavac :app:generateReleaseBuildConfig --offline --console=plain --no-parallel --max-workers=2 --project-cache-dir build/first-launch-validation/project-cache -I build/first-launch-validation/init.gradle` 成功（2m 41s）；app 372 项、base/general 147 项、ad-api 18 项、ad-admob 16 项，共 553 项 JVM 单测，失败、错误、跳过均为 0。`python -m unittest discover -s scripts -p test_github_release_version.py` 的 8 项测试及 `git diff --check` 通过。
- 版本与环境核对：app Debug/Release 及 base/general Debug BuildConfig 均为 `0.9.18 / 54`，真实版本比较输出 `changed=true / v0.9.18-54`。首次沙箱执行受工作区外 Gradle 缓存锁访问限制；授权缓存访问后，默认 Debug 输出又遇到 Windows 的 `classes.jar` 文件占用，复用既有隔离初始化脚本后验证成功，未修改正式构建配置或终止其他进程。保留已有 AGP/Kotlin 弃用及 Manifest 警告；本轮未重复设备测试或 R8，不将此前广告验证记录当成本轮重复执行结果。
- 推送前核对：GitHub 连接确认当前用户与仓库所有者均为 `lwcla`，具备 push 权限，默认分支为 `main`；构建临时文件未纳入提交。版本调整无额外方法、变量注释缺口或职责拆分例外，未新增运行时日志、备份字段或 keep 规则。

### 0.9.17 双源发布（2026-10-31，已完成）

- 按用户要求将 `versionName` 从 `0.9.16` 递增至 `0.9.17`，`versionCode` 从 `52` 递增至 `53`，纳入当前全部修改，先提交并普通推送源码 `main`，再用既有脚本发布 GitHub/Gitee APK、`update.json` 和 `sha256.txt`。双源标签为 `v0.9.17`；源码仓库工作流另行使用 `v0.9.17-53`，两套发布目标保持原约定。
- 本版包含首次标记缺失时重新说明且关闭联网预览、移除 APK 更新时间推断，以及我的页检查更新置底和空功能分类隐藏；完整实现、回归测试及设备验收边界以 [首次启动方案](first_launch_consent_plan.md) 和 [我的页方案](mine_page_plan.md) 为准。
- 复用 app 静态版本配置、共享模块派生版本、APK 命名及既有 Windows/Bash 双源发布链路，不新增组件、类或业务方法。版本配置中文注释同步；常量递增不新增单测或采用 TDD，现有功能回归测试随全部修改提交并重新运行。
- 验证计划：运行 app/base:general 单测、app Debug Kotlin 编译、版本判定脚本单测、真实版本比较与 `git diff --check`；脚本 dry-run 执行 Release/R8 构建，核对 APK 版本、签名连续性与摘要。推送后再真实上传，并核验两端 latest、三个附件及摘要；本轮不重复真机安装、权限及 UI 验收。
- 日志与诊断计划：沿用原 Gradle、发布脚本和 Actions 输出，不新增 App 运行时日志；凭据仅由本机发布链路读取，不输出或上传。按版本保存 APK、metadata、mapping、清单、源码提交号与验证日志至 `build/github-gitee-release/v0.9.17/`，mapping 仅本地留存。
- 规则边界：当前全部修改属于用户授权提交范围，不覆盖已有工作或改写历史；版本递增没有新增数据、反射或外部协议。首次隐私字段继续排除备份，沿用已有备份文档；本机启动兼容方式复用既有记录，仓库发布脚本保持原实现。
- 发布前验证：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin --offline --console=plain --no-parallel --max-workers=2 --project-cache-dir build/first-launch-validation/project-cache -I build/first-launch-validation/init.gradle` 成功（2m 9s）；base/general 147 项、app 372 项，共 519 项单测，失败、错误、跳过均为 0。`python -m unittest discover -s scripts -p test_github_release_version.py` 的 8 项测试通过，真实版本比较输出 `changed=true / v0.9.17-53`，`git diff --check` 通过。
- 构建与产物：原发布脚本 dry-run 的 `:app:assembleRelease`（含 lintVital 和 R8）成功（5m）；`apksigner verify --verbose --print-certs` 确认 v2 签名有效且证书与上次双源发行 0.9.12 一致，`aapt dump badging`、APK metadata 及 app/共享模块 BuildConfig 均为 `com.cla.clip.master / 0.9.17 / 53`。1216 个受控源码/构建文件摘要无变化；真实上传重新执行构建后，以最终上传附件摘要为准。
- 环境与边界：默认 Debug 输出中的 `classes.jar` 被其他进程占用导致首次验证失败，复用已有隔离目录后通过，未终止其他程序或修改正式构建配置。发布复用既有 PowerShell 7/Git Bash 兼容入口，保留原脚本和已有 Kotlin/AGP 弃用、Shizuku R8 警告；本轮不重复设备安装和 UI 验收，不新增运行时日志或备份字段。推送前远端 `main` 与起点 `1c538ac` 一致，全部 13 个版本、源码、测试及文档文件均已审阅。
- 发布结果：全部 13 个文件已提交为 `6ccc70a`，先普通推送 `origin/main` 并核对远端一致，再执行原双源脚本，正式上传返回 0。GitHub/Gitee 的 latest 均为 `v0.9.17`，均包含 `ClipMaster-v0.9.17-admob.apk`、`update.json` 和 `sha256.txt`；GitHub 三附件服务端 SHA256 与 Gitee 三附件回下载 SHA256 全部匹配本地。
- 最终产物：APK 为 5,430,826 字节，SHA256 为 `ef115fbcfa38a3647c92aefc8b0377098b4a61745f0fd3cf1c44a6dda3857479`；上传构建重打包后已重新核对 `0.9.17 / 53` 和正式签名连续性。APK、清单、metadata、mapping、源码提交号、测试/预演/上传/核验日志归档于 `build/github-gitee-release/v0.9.17/`，mapping 不上传公开发行仓库。
- 发布地址：[GitHub v0.9.17](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.17)、[Gitee v0.9.17](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.17)。源码仓库推送额外触发 [Actions 41178331957](https://github.com/lwcla/ClipMaster/actions/runs/41178331957)，双源验收时仍在运行；它的 `v0.9.17-53` 自动发布与上述已验证双源发行分开记录，不作为本次脚本发布成功的依据。

### 0.9.16 版本准备（2026-10-30，已完成）

- 按用户要求将 `versionName` 从 `0.9.15` 递增至 `0.9.16`，`versionCode` 从 `51` 递增至 `52`；纳入当前全部修改并普通推送到 `origin/main`，由既有工作流检查版本并自动构建发布，预期标签为 `v0.9.16-52`。
- 本版包含常驻隐私政策入口、浏览器打开与内置离线正文兜底、单源政策资产生成、文档链接边界测试，以及公共卡片 30% 描边和联网预览入口图标配色。功能契约和设备验收边界以 [我的页方案](mine_page_plan.md)、[导航方案](app_navigation_plan.md)、[公共卡片主方案](shared_card_component_plan.md)、[剪贴结果列表主方案](clip_result_list_plan.md) 与 [政策维护说明](../play-store/PRIVACY_SETUP.md) 为准。
- 复用 app 静态版本声明、base/general 版本派生、BuildConfig、APK 命名与版本变化判定脚本；版本调整不新增组件、类、方法或业务规则，同步版本码中文注释。按单元测试门禁无需为常量递增新增镜像单测或采用 TDD，已有功能测试随全部改动提交并复跑。
- 验证计划：执行 app/base:general 单测、app Debug Kotlin 编译、Release/R8、Debug/Release 政策资产合并、版本判定脚本单测、真实版本比较与 `git diff --check`；核对生成版本和政策资产与源文件一致。设备交互未覆盖项继续由功能主方案记录，不将构建通过等同于真机验收或云端发布完成。
- 日志与诊断计划：版本递增沿用既有 Gradle、版本判定脚本和 Actions 输出，不新增运行时日志；政策入口与阅读失败日志沿用我的页方案的低敏原因码，不输出正文、URL、凭据或用户内容。无新增持久化数据和备份契约；新导航路由沿用既有 Keep 规则和显式序列化名，本轮复验 R8。
- 当前全部工作区改动属于用户明确要求的提交范围；不丢弃已有修改、不改写历史，推送前核对远端主分支，完成后核对远端提交与本地一致。
- 本地验证：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:minifyReleaseWithR8 :app:mergeDebugAssets :app:mergeReleaseAssets --offline --console=plain --no-parallel --max-workers=2` 成功（5m 52s）；app 372 项、base/general 145 项，共 517 项 JVM 单测，失败、错误、跳过均为 0。`python -m unittest discover -s scripts -p test_github_release_version.py` 的 8 项测试及暂存区 `git diff --staged --check` 通过。
- 产物核对：app/base:general 的 Debug/Release BuildConfig 均为 `0.9.16 / 52`；真实版本变化判定为 `changed=true / v0.9.16-52`。Debug/Release 最终合并的政策资产与唯一源文件逐字节一致，未打包内部维护说明；mapping 保留于 `app/build/outputs/mapping/release/mapping.txt`。保留既有 AGP/Kotlin 弃用、Manifest 与 Shizuku 接口警告，本轮未重新安装或执行设备验收。
- 提交前边界：远端 `main` 与本地起点 `5d3687e` 一致；GitHub 连接确认当前用户和仓库所有者均为 `lwcla`，具有 push 权限，全部 20 个版本、源码、测试与文档文件已审阅。首次沙箱构建因工作区外缓存锁权限失败，批准缓存访问后验证完成；版本调整无新增运行时日志或备份字段，无缺少中文注释或临时拆分类例外。云端自动发布结果需在推送后单独查看，不能由本地构建成功推定。

### 0.9.15 版本递增与全部改动推送（2026-10-30，已完成）

- 按用户要求将 `versionName` 从 `0.9.14` 递增至 `0.9.15`，`versionCode` 从 `50` 递增至 `51`，提交当前全部修改并普通推送到源码仓库 `main`，由既有工作流执行版本判定及自动构建发布；预期标签为 `v0.9.15-51`，不另行手动发布或改写历史。
- 本版纳入 Shizuku、通知、联网预览三项说明默认单行及动画展开、仅右侧开关执行业务动作、读屏语义与对应设备回归测试，以及冗余 Shizuku 关闭指引移除。完整共享契约、复用与设备验收记录以 [共享表单主方案](shared_form_components_plan.md)、[我的页方案](mine_page_plan.md) 和 [首次启动方案](first_launch_consent_plan.md) 为准。
- 版本调整复用 app 版本声明、base/general 的版本派生、BuildConfig 和 APK 命名，不新增组件、类、方法或独立规则，中文版本注释同步更新；无需新增版本专用单测或采用 TDD。已有 UI 改动复用 `SettingSwitchEntryCard` 骨架，新展开组件与设备测试一并纳入，不改业务授权或存储流程。
- 验证计划：运行 app/base:general 的 `testDebugUnitTest`、app `compileDebugKotlin`、Android 测试 Kotlin/Java 编译、Release BuildConfig 生成、版本脚本单测、真实版本变化判定与 `git diff --check`。本轮不重复设备测试或本地 Release/R8，既有九项设备测试结果和未人工验收项以共享主方案为准；推送完成不等于云端发布完成。
- 日志与诊断计划：沿用既有版本判定、Gradle 和 Actions 诊断，版本元数据及纯阅读交互不增加运行时日志；不输出凭据或用户内容。无备份数据、外部协议或反射契约变化，不新增备份或 keep 规则。
- 本地验证：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestJavaWithJavac :app:generateReleaseBuildConfig --offline --console=plain --no-parallel --max-workers=2` 成功；app 368 项、base/general 145 项，共 513 项 JVM 测试，失败、错误、跳过均为 0。`python -m unittest discover -s scripts -p test_github_release_version.py` 的 8 项测试及 `git diff --check` 通过。app Debug/Release 和共享模块 Debug BuildConfig 均为 `0.9.15 / 51`，真实版本判定输出 `changed=true / v0.9.15-51`。保留既有 AGP/Kotlin 废弃警告。
- 推送前核对：远端 `main` 与本轮起点 `f94698b` 一致；GitHub 连接确认当前用户和仓库所有者均为 `lwcla`，具有 push 权限。提交范围为全部 13 个版本、UI、测试及文档文件。云端 Release 构建、发布完成及 APK 安装不属于本轮本地验证结论。

### 0.9.14 版本递增与全部改动提交（2026-10-30，已完成）

- 按用户要求将 `versionName` 从 `0.9.13` 递增至 `0.9.14`，`versionCode` 从 `49` 递增至 `50`，将工作区全部已有代码、测试、工具及对应方案文档纳入本地提交。
- 本版包含首次启动说明与独立联网预览设置、本地备份仅发布 ZIP、同名目录保护及失败原因展示、剪贴卡片默认色与来源色边框统一为 30% 不透明度。功能设计和验证边界以 [首次启动方案](first_launch_consent_plan.md)、[备份主方案](webdav_backup_plan.md) 及相关页面、共享组件文档为准，不重复维护功能细节。
- 复用 app 版本声明、共享模块版本派生、BuildConfig 与 APK 命名规则；版本递增不新增组件、类、方法或独立业务规则，不新增专用单测或采用 TDD，中文配置注释同步更新。已有功能测试一并提交并重新验证。
- 验证计划：运行 app/base:general 单测、app Debug Kotlin 和 Android 测试编译、Release BuildConfig 生成、版本判定脚本单测与 `git diff --check`。本轮不执行设备安装、实际权限交互或 Release/R8；已有功能的设备验证记录以各主方案为准，不将测试编译等同于设备执行。
- 日志与诊断计划：版本元数据沿用既有构建及版本判定输出，不新增 App 运行时日志；首次说明、预览与备份的低敏日志按各自主方案执行，不输出凭据、剪贴内容或授权 URI。版本调整本身不改变备份 schema、设置白名单或 keep 契约。
- 提交范围为本地全部现有修改；本轮不推送、不创建标签、不上传 APK，自动发布须在后续推送后由既有工作流触发。
- 验证结果：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:generateReleaseBuildConfig --offline --console=plain --no-parallel --max-workers=2` 成功；app 368 项、base/general 145 项，共 513 项单测，失败、错误、跳过均为 0。补充 `:app:compileDebugAndroidTestJavaWithJavac`（相同离线参数）成功，覆盖新增 Java 测试装配工厂。
- 版本核对：app Debug/Release 与 base/general Debug BuildConfig 均为 `0.9.14 / 50`；`python -m unittest discover -s scripts -p test_github_release_version.py` 的 8 项测试通过，真实工作区版本判定为 `changed=true / v0.9.14-50`；`python tools/render_doc_flow_html.py docs/webdav_backup_plan.md --check` 和 `git diff --check` 通过。首次 Gradle 沙箱执行因工作区外缓存锁文件访问受限失败，获准访问现有缓存后验证完成；保留已有 AGP/Kotlin 废弃警告，未改构建环境或业务实现。

### 0.9.13 源码仓库自动发布验收（2026-10-29，实现中）

- 按用户要求将 `versionName` 从 `0.9.12` 改为 `0.9.13`，`versionCode` 从 `48` 改为 `49`，将版本修改与已经验证的版本触发工作流、判定脚本及测试一起提交并推送 `main`。预期发布标签为 `v0.9.13-49`，目标为当前源码仓库 `lwcla/ClipMaster` 的 Releases。
- 复用现有版本元数据、共享模块版本派生、正式签名、AdMob 参数和 APK 命名，不引入新的 App 类、备份字段、协议或 keep 规则。版本常量本身无独立业务逻辑，不新增专用单测；沿用版本门禁的 8 项回归测试，执行相关 Python 测试、Debug Kotlin 编译、Release BuildConfig 生成和 `git diff --check`。
- 日志与诊断沿用下节自动发布计划；不输出签名/广告配置或令牌。推送前核实 GitHub 登录用户与仓库所有者为 `lwcla`，具备 push 权限。推送后观察 Actions 的版本判定、Release/R8 构建、签名校验与上传结果；云端完成前不宣称 APK 已发布。本次不调用既有双源发布脚本，不改变 App 内更新地址。
- 待验收：工作流首次运行的 Secrets 可用性、构建与发布权限、Release 附件及目标提交；APK 安装和广告展示仍属于真机验收。
- 推送前验证：17 项 Python 测试通过，版本门禁对本轮修改输出 `changed=true / v0.9.13-49`；`.\gradlew.bat :app:compileDebugKotlin :app:generateReleaseBuildConfig --offline --console=plain --no-parallel --max-workers=2` 成功，生成的 Release BuildConfig 为 `0.9.13 / 49`。远端 main 与本地起点 `42e16dd` 一致，当前源码仓库尚无 Release；待提交推送后核验云端产物。既有 AGP 废弃选项警告保留。
- 已创建提交 `4c0cad4`（发布：升级至 0.9.13 并启用版本变更自动发行），普通推送 `main` 成功。对应 [Actions 运行 40904721405](https://github.com/lwcla/ClipMaster/actions/runs/40904721405) 的 `check-version` 成功，已进入构建 job；SDK、正式签名和 AdMob 配置恢复成功，当前等待 Release 构建与发布结果。

### 修改版本后自动发布到源码仓库（2026-10-29，本地实现已完成）

- 用户确认的触发条件：推送到 `main` 后，比较本次 push 的 `before` 与 `after` 提交中 `app/build.gradle.kts` 的 `versionName` 和 `versionCode`。任一字段变化才构建正式签名 AdMob APK；普通代码、注释和其他 Gradle 配置变化只执行轻量检查，不打包、不发布。一次 push 包含多个提交时比较两端，只发布最终版本；本地 commit 尚未 push 不会触发。
- 首次创建分支、没有 before 提交时跳过，避免把初始化仓库视为版本升级。版本配置必须继续使用可直接读取的静态字符串/整数；缺失、重复、动态表达式或不安全标签字符均报错终止，不能默认为需要发布。
- 使用固定标签 `v<versionName>-<versionCode>`（例如 `v0.9.13-49`），使仅修改 versionCode 也能区分版本。工作流不自动递增 App 版本，不覆盖已有 Release 或附件；同版本重跑若已发布则由 GitHub 拒绝重复创建，需要核对已有发布结果。
- 发布目标是运行工作流的源码仓库 Releases，使用本仓库 `GITHUB_TOKEN` 与 `contents: write`，标签明确指向实际构建提交。App 的双源更新地址和 `update.json` 契约保持原状；此发布不是原有 `ClipMaster-Releases`/Gitee 双源发行，不生成应用内更新清单。
- 复用现有 `assembleRelease`、APK 命名、R8、签名属性读取与 AdMob Gradle 开关。需要三个仓库 Secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_PROPERTIES_BASE64`、`ADMOB_PROPERTIES_BASE64`；分别恢复 `keystore.jks`、`keystore.properties`、只含广告参数的 `local.properties`。签名属性中的 `storeFile` 继续使用 `keystore.jks`，不复制 Windows SDK 路径到 Linux。
- 原有 `scripts/publish_github_release.sh` 面向双源发行、构建后读取 APK 元数据并生成更新清单，不适合在构建前判断版本变化；本次新增 `scripts/github_release_version.py` 只负责版本解析、比较与 Actions 输出，发布仍用 GitHub CLI，不新增网络 SDK 或 App 类。
- 验证计划与实现顺序：先用合成 Gradle 文本为版本变化/不变/无效配置写失败测试，再实现纯比较函数及 Git 读取入口；随后改 `.github/workflows/build-apk.yml` 的检查 job、受控构建和发布步骤；最后运行 Python 单测、真实本地提交的只读比较、YAML/嵌入 Bash 语法检查与 `git diff --check`。不修改 Kotlin、Android 资源或混淆规则，不重复运行 Android 全量测试；云端 Secrets、签名/R8 构建和实际发布留待首次版本推送验证。
- 后续验收：确认云端三个 Secrets 已配置，修改版本后一次真实 push 能产生附件；普通提交应显示 build job 跳过。跨版本构建允许并行，不用取消旧构建的 concurrency；Releases 的 Latest 排序使用 GitHub 默认行为。
- 边界约束：`v<versionName>-<versionCode>` 标签由此工作流专用，不应手工预建或移动；GitHub CLI 的 `--target` 只控制新标签，已有同名标签会被沿用。强推后若 before 提交已不在可获取历史中，版本检查安全失败，不回退为盲目发布。正常版本升级应沿主分支提交历史向前推进。
- 本地验证：版本解析/比较新增 8 项测试采用先失败后实现，连同既有文档工具测试共 17 项通过；补测并修复了静态声明后再次动态赋值的拒绝边界。使用真实 `0.9.11 → 0.9.12` 提交验证 changed=true，相同提交、全零基线、非法 SHA 的 CLI 输出/退出码均符合预期。YAML、权限、job 依赖/条件检查与全部 7 段 Bash `-n` 通过；Bash 初次受沙箱命名对象权限限制，改为沙箱外只读语法检查后通过。`git diff --check` 通过。未推送、未触发真实签名构建或 GitHub Release，不能将这些检查视为云端发布成功。

#### 自动发布日志与诊断计划

- INFO：版本检查只输出 `changed`、包含版本名/版本码的标签和低敏 reasonCode；未变更或无基线明确记录跳过原因。
- ERROR：版本无法解析、Git 提交读取失败以稳定 reasonCode 报错；Secret 缺失、Base64 无效、签名校验失败或 GitHub 发布失败沿用现有工具的非零退出码与 Actions 阶段名定位并终止流水线，不输出文件内容或 Secret 值。
- Secrets 只在恢复步骤注入环境，GitHub token 只在发布步骤注入；上传白名单限定 APK 和独立 mapping artifact，签名、local.properties、日志或整个工作目录不作为 Release 附件。构建及发布状态由 Actions 日志留存，不新增 App 运行时日志。

### 0.9.12 双源发布（2026-10-29，已完成）

- 按用户要求将 `versionName` 从 `0.9.11` 递增至 `0.9.12`，`versionCode` 从 `47` 递增至 `48`；纳入当前全部右滑菜单、提示、交互测试和共享文档改动，先提交并推送源码 `main`，再使用原脚本向 GitHub/Gitee 发布签名 APK、`update.json` 和 `sha256.txt`。
- 本版右滑菜单图标常驻，继续/松手提示固定排在菜单右侧；拖动及离场动画期间禁用菜单操作，保持原有 80% 基准、回拉取消及业务回调。实现与极窄窗口/大字体可读性取舍以 [共享列表方案](clip_result_list_plan.md#swipe-menu-hint-layout-plan) 为准，不整体重构手势状态机。
- 复用 app 版本配置、共享模块版本派生、APK 命名、Windows/Bash 发布脚本及既有 PowerShell 7 本机兼容入口，保持 domestic 分发、internal 更新渠道和 AdMob 配置。版本元数据无独立业务规则，不新增单测或采用 TDD；现有功能改动已更新 Android 交互回归，保留阈值纯规则单测。
- 验证计划：运行 app/base:general 单测、Debug Kotlin 和两模块 Android 测试编译、脚本 dry-run（含 Release/R8）、APK 版本/签名连续性及 `git diff --check`；上传后核验双端 latest 和三个附件摘要。设备交互、覆盖安装与视觉验收另行执行，测试编译不替代真机运行结果。
- 日志与诊断计划：沿用现有构建、发布及共享列表诊断，不新增高频手势或 App 运行时日志。凭据只由本机脚本读取，不写入输出或公开附件；按版本将 APK、清单、checksum、metadata、mapping、源码提交号及验证日志归档至 `build/github-gitee-release/v0.9.12/`，mapping 仅本地保存。
- 规则边界：版本配置简体中文注释同步更新；本次无数据库、备份、序列化或反射契约变化，无需新增备份或 keep 规则。仅复用既有本机启动兼容措施，仓库发布脚本保持不变。
- 变更记录：2026-10-29，按用户要求准备 `0.9.12 / 48` 双源发布，明确全部当前改动的提交范围、验证步骤、日志与设备验收边界。
- 发布前验证：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :base:general:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :app:compileDebugKotlin --offline --console=plain --no-parallel --max-workers=2` 成功，app 347 项、base 138 项共 485 项单测通过，失败/错误/跳过均为 0。原脚本 dry-run 的 `:app:assembleRelease`（含 R8、lintVital）成功；`apksigner verify --verbose --print-certs` 确认签名有效且与 0.9.11 相同，`aapt dump badging`、metadata、Release BuildConfig 和清单均为 `com.cla.clip.master / 0.9.12 / 48`，渠道 domestic/internal。预演 APK 为 5,390,286 字节，SHA256 为 `0d58dac3fad4e4bf37e6498fea0178698bcc0a4c6c7d9f7923e0caf32fcb350e`，真实发布再次构建后以最终附件摘要为准。
- 提交与环境记录：现有功能、交互回归和共享方案已提交为 `7d81318`；版本配置与发布记录单独提交。首次验证及直接重试因 Windows 占用 `classes.jar` 失败，确认两个 Gradle daemon 均空闲并重启后同一命令通过。源码摘要与版本修改后的快照一致，`git diff --check` 通过；既有 Kotlin 弃用及 Shizuku R8 告警保留。Android 测试仅编译，未执行真机交互、覆盖安装和 Preview 渲染。
- 变更记录：2026-10-29，完成 485 项单测、正式构建预演、版本及签名连续性验证，记录文件锁恢复过程；下一步先推送源码，再执行双源发行和附件核验。
- 变更记录：2026-10-29，自动审批首次因远端归属未核实拒绝推送；使用 GitHub 连接核实登录用户与仓库所有者均为 `lwcla`、具备 admin/push 权限、默认分支为 `main`，并据 API 返回的 public 可见性修正文档顶部过期描述，仓库可见性未作变更。
- 发布结果：功能提交 `7d81318`、版本提交 `c7119a0` 已完成；推送时远端新增仅含 Actions 构建配置的 `0721e92`，普通合并为 `3546814` 后已推送 `origin/main` 并核对远端一致，未改写历史。合并前后本地发布源码摘要一致。补充账号与仓库归属证据后自动审批放行，随后原脚本真实发布返回 0。
- 双源核验：GitHub/Gitee latest 均为 `v0.9.12`，各含 `ClipMaster-v0.9.12-admob.apk`、`update.json` 和 `sha256.txt`；GitHub 三附件服务端 SHA256 与 Gitee 三附件回下载 SHA256 全部匹配本地。最终 APK 为 5,390,286 字节，SHA256 为 `3f64b20476630adf7078e9519914891054d950611c2ce65c32f89e4188aeac2c`，重新核对 `0.9.12 / 48` 和签名连续性通过，以此最终摘要作为发布验收值。
- 发布地址：[GitHub v0.9.12](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.12)、[Gitee v0.9.12](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.12)。最终制品、metadata、mapping、源码提交号和验证日志已归档到 `build/github-gitee-release/v0.9.12/`；mapping 仅本地留存。设备交互、覆盖安装与视觉验收仍待执行。
- 变更记录：2026-10-29，完成 `0.9.12 / 48` 双源发行与全部附件摘要核验，补录安全合并、审批核实、最终摘要和本地归档，发布小节更新为已完成。

### 0.9.11 双源发布（2026-10-29，已完成）

- 按用户要求将 `versionName` 从 `0.9.10` 递增至 `0.9.11`，`versionCode` 从 `46` 递增至 `47`；纳入当前全部右滑交互、测试和对应文档改动，先提交并推送源码 `main`，再使用原脚本向 GitHub/Gitee 发布正式签名 APK、`update.json` 和 `sha256.txt`。
- 本版统一折叠、取消折叠和回收站彻底删除的 80% 基准阈值、菜单后保护距离及继续/松手两阶段提示，保留回拉取消和离场后执行。共享规则、组件拆分、注释、测试与设备验收边界以 [共享列表方案](clip_result_list_plan.md#swipe-threshold-plan) 及各页面接入摘要为准。
- 复用现有版本配置、APK 命名、双端发布脚本及本机 PowerShell 7/Git Bash 兼容入口，沿用 domestic 分发、internal 更新渠道和 AdMob 配置。版本元数据没有新的独立业务规则，不新增单测或采用 TDD；功能改动已包含阈值规则单测和 Android 指针交互测试。
- 发布验证：重新运行 app/base:general 单测、Debug Kotlin 和 app/base:general Android 测试编译，执行脚本 dry-run（含 `:app:assembleRelease`、R8）、APK 版本/签名/SHA256 和 `git diff --check`；上传后核验两端 latest 及三个附件摘要。当前设备交互测试、真机覆盖安装和视觉验收仍待实际设备执行，不以编译结果代替运行结果。
- 日志与诊断计划：沿用原发布日志、构建诊断及共享列表已有日志边界，不新增高频手势或 App 运行时日志；凭据仅供原脚本本机读取，不输出到日志或公开产物。APK、最终清单、metadata、mapping、源码提交号和验证日志按版本保存在 `build/github-gitee-release/v0.9.11/`，mapping 仅本地留存。
- 规则边界：版本配置中文注释同步更新；本次无新增数据库、备份、序列化或反射契约，不调整备份或 keep 规则。沿用已记录的 Windows 启动兼容措施，仓库发布脚本保持原实现。
- 变更记录：2026-10-29，按用户要求准备 `0.9.11 / 47` 双源发布，记录全部现有右滑改动的提交范围、验证计划和日志边界。
- 发布前验证：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :base:general:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :app:compileDebugKotlin --offline --console=plain --no-parallel --max-workers=2` 成功，app 347 项、base 138 项，共 485 项单测通过，无失败、错误或跳过。原脚本 dry-run 的 `:app:assembleRelease`（含 R8、lintVital）成功，耗时 2 分 23 秒；`apksigner verify --verbose --print-certs` 确认 v2 签名有效且与 0.9.10 一致，`aapt dump badging`、metadata、Release BuildConfig 和预演清单均为 `com.cla.clip.master / 0.9.11 / 47`，渠道 domestic/internal。预演 APK 为 5,373,902 字节，SHA256 为 `e762c37730f853c95b7dd1df312d8bba7575871eefcc617bb2848c95fb5a4756`；真实上传若再次打包，以最终附件摘要为准。
- 提交与环境记录：现有右滑功能、测试及五份方案文档归入 `ba945b1`，版本配置与本记录单独提交；源码构建期间内容摘要一致，工作区与暂存区 `git diff --check` 通过。首次 Gradle 沙箱访问 E 盘缓存锁失败，获准使用本机缓存后同一命令成功；无连接设备，Android 测试仅编译，既有 Kotlin 弃用提示与 Shizuku R8 告警保留。
- 变更记录：2026-10-29，完成 485 项单测、正式构建预演、版本及签名连续性检查；补录功能提交和预演摘要，后续按源码先推送、APK 再发布的顺序执行。
- 发布结果：功能提交 `ba945b1` 和版本提交 `7b6d5a8` 已先推送 `origin/main` 并核对远端一致，随后原脚本真实发布返回 0。GitHub/Gitee latest 均为 `v0.9.11`，两端均包含 `ClipMaster-v0.9.11-admob.apk`、`update.json` 和 `sha256.txt`；GitHub 三附件服务端 SHA256 与 Gitee 三附件回下载 SHA256 全部匹配本地。最终 APK 为 5,373,902 字节，SHA256 为 `b97ede4a8a26a49a35fea67a09d4f671d89c2be237633cfdfb3225c83c64792f`，已重新核对 `0.9.11 / 47` 版本和签名连续性，发布验收以最终摘要为准。
- 发布地址：[GitHub v0.9.11](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.11)、[Gitee v0.9.11](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.11)。最终 APK、清单、校验文件、metadata、mapping、源码提交号及测试/构建/上传/核验日志已归档到 `build/github-gitee-release/v0.9.11/`，mapping 仅本地留存。发布前后源码摘要一致；初始快照包含的 `.kotlin/sessions/*.salive` 为构建完成后自动清理的临时会话标记，排除此项后源码核验通过。
- 变更记录：2026-10-29，完成 `0.9.11 / 47` 双源发行和六项附件摘要核验，补录最终摘要、源码提交及本地归档，发布小节更新为已完成；设备交互、覆盖安装和视觉验收仍待执行。

### 0.9.10 双源发布（2026-10-29，已完成）

- 按用户要求将 `versionName` 从 `0.9.9` 递增至 `0.9.10`，`versionCode` 从 `45` 递增至 `46`；用户已确认纳入全部现有改动，先提交并推送源码 `main`，再使用原脚本发布 GitHub/Gitee 正式签名 APK、`update.json` 和 `sha256.txt`。
- 本版包含七字段多选、完整短语搜索与高亮、固定同行筛选及菜单宽度修正；功能实现、测试先行和设备验收边界以 [搜索页方案](search_page_plan.md)、[共享列表方案](clip_result_list_plan.md) 和 [共享表单方案](shared_form_components_plan.md) 为准，不重复维护细节。
- 复用现有版本配置、APK 命名、双端发布脚本与本机 PowerShell 7/Git Bash 兼容入口，沿用 domestic 分发、internal 更新渠道及 AdMob 配置。本轮版本递增不新增组件、类、方法或外部协议，版本配置简体中文注释同步更新。
- 单元测试门禁：版本元数据无独立业务规则，不新增单测或采用 TDD；现有功能改动已有对应测试，本轮重新运行 app/base:general 单测、Debug Kotlin 与 Room 仪器测试编译，执行脚本 dry-run（含 `:app:assembleRelease`、R8）、APK 版本/签名/SHA256 和 `git diff --check`，上传后核验双端 latest 与三项附件摘要。
- 日志与诊断计划：沿用原发布日志和构建诊断，不增加 App 运行时日志；凭据只由原脚本在本机读取，不写入日志、文档或公开产物。按版本保留 APK、最终清单、metadata、mapping 与验证日志，mapping 仅保存在本地。
- 规则边界：无数据库、备份或反射契约变化，不调整备份与 keep 规则；沿用已记录的本机启动兼容措施，仓库发布脚本保持原有实现。真机覆盖安装、Room 仪器测试实际执行、页面视觉/交互与大数据搜索性能仍需设备验收。
- 发布前验证：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :base:general:compileDebugAndroidTestKotlin :app:compileDebugKotlin --offline --console=plain --no-parallel --max-workers=2` 成功，app 342 项、base 138 项，共 480 项测试通过，失败/错误/跳过均为 0。原脚本 dry-run 的 `:app:assembleRelease`（含 R8、lintVital）成功，耗时 1 分 41 秒；`apksigner verify --verbose --print-certs` 确认 v2 签名有效且与 0.9.9 一致，`aapt dump badging`、metadata、Release BuildConfig 与预演清单均为 `com.cla.clip.master / 0.9.10 / 46`，渠道 domestic/internal。预演 APK 为 5,388,994 字节，SHA256 为 `2fbe097b0dbf0a89367bed672de4f39df9860c12ea9cac16d2c3b62b8b165534`；真实上传若再次打包，以最终附件摘要为准。暂存及工作区 `git diff --check` 通过，保留既有 Shizuku Kotlin/R8 告警。
- 变更记录：2026-10-29，按用户要求准备 `0.9.10 / 46` 双源发布；确认纳入全部现有搜索改动，补录发布范围、验证步骤及日志边界。
- 发布结果：功能提交 `671f5b0`、版本提交 `c335070` 已先推送 `origin/main` 并核对远端一致，随后原脚本真实上传返回 0。GitHub/Gitee latest 均为 `v0.9.10`，均包含 `ClipMaster-v0.9.10-admob.apk`、`update.json` 和 `sha256.txt`；GitHub 三附件服务端 SHA256 与 Gitee 三附件回下载 SHA256 全部匹配本地。真实发布再次打包后的最终 APK 为 5,388,994 字节，SHA256 为 `d0e948000e33eb6af93d4cf4421e33a80b05befc95f04ebc3f207f168306bafd`，已重新核对版本及签名连续性，发布验收以最终摘要为准。
- 发布地址：[GitHub v0.9.10](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.10)、[Gitee v0.9.10](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.10)。最终 APK、清单、校验文件、metadata、mapping、源码提交号及测试/构建/上传/核验日志已归档到 `build/github-gitee-release/v0.9.10/`；mapping 仅保存在本地。
- 变更记录：2026-10-29，完成 `0.9.10 / 46` 双源发行与全部附件摘要核验；补录最终摘要、源码提交和本地归档位置，发布小节更新为已完成，保留设备与性能验收边界。

### 0.9.9 双源发布（2026-10-29，已完成）

- 按用户要求将 `versionName` 从 `0.9.8` 递增至 `0.9.9`，`versionCode` 从 `44` 递增至 `45`；先提交并推送源码主分支，再使用原脚本发布 GitHub/Gitee 正式签名 APK、`update.json` 和 `sha256.txt`。用户已确认纳入当前全部改动：搜索字段筛选、来源名称查询与分页会话、结果/历史红色加粗高亮、公共卡片描边及隐私文档；实现细节仍以各功能主方案为准。
- 复用现有版本配置、APK 命名、双端发布脚本及此前验证的 PowerShell 7/Git Bash 本机兼容入口，保留 domestic 分发、internal 更新渠道及 AdMob 配置。本轮版本递增不新增组件、类、方法或协议。
- 单元测试门禁：版本元数据没有独立业务规则，不新增单测或采用 TDD；已有功能改动使用现有测试验证。运行 app/base:general 单测、Debug Kotlin 与 Room 仪器测试编译，原脚本 dry-run（含 `:app:assembleRelease`、R8）、APK 版本/签名/SHA256 和 `git diff --check`；上传后核对两端 latest 及三项附件摘要。
- 日志与诊断计划：沿用原发布日志和构建诊断，不增加 App 运行时日志；凭据由原脚本在本机读取，不写入日志、文档或公开产物。按版本保留 APK、最终清单、metadata、mapping 与发布核验日志，mapping 仅保存在本地。
- 版本配置简体中文注释同步更新；版本递增不影响数据库、备份或反射契约。沿用已记录的本机启动兼容措施，不修改仓库发布脚本；真机覆盖安装、Room 仪器测试实际执行与页面视觉/交互不属于本轮自动发布验证。
- 发布前验证：`.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :base:general:compileDebugAndroidTestKotlin :app:compileDebugKotlin --offline --console=plain --no-parallel --max-workers=2` 成功，app 337 项、base 132 项共 469 项测试通过，失败/错误/跳过均为 0。原脚本 dry-run 的 `:app:assembleRelease`（含 R8、lintVital）成功，耗时 3 分 48 秒；`apksigner verify --verbose --print-certs` 确认 v2 签名有效且与 0.9.8 一致，`aapt dump badging`、metadata、Release BuildConfig 和预演清单均确认为 `com.cla.clip.master / 0.9.9 / 45`。渠道为 domestic/internal，APK 5,386,482 字节，SHA256 为 `b9247ab957c79c84a6956caead49e83211645a7a3d3ca62880da13825e36d2df`。
- 提交范围：功能及测试归入 `1e2bb52`，隐私政策归入 `d2b3cd9`，版本配置与本记录独立提交；用户确认纳入全部已有改动。隐私 HTML 静态校验通过，政策网站未部署。发布前 Git 范围及差异检查通过，既有 Shizuku Kotlin/R8 告警未导致构建失败。
- 发布结果：源码版本提交 `7f2b183` 已先推送 `origin/main` 并核对远端提交一致，随后执行原脚本真实上传，返回 0。GitHub/Gitee latest 均为 `v0.9.9`，均包含 `ClipMaster-v0.9.9-admob.apk`、`update.json` 和 `sha256.txt`；GitHub 三附件服务端 SHA256 及 Gitee 三附件回下载 SHA256 全部与本地一致。真实发布再次打包后的最终 APK 为 5,386,482 字节，SHA256 为 `98a0a478d132506b6420c10f3bdb569ee310af19fcc4aad1f1c344466ccedc0c`，与预演摘要不同，已重新核对最终 APK 的 `0.9.9 / 45` 版本和签名连续性，发布验收以最终摘要为准。
- 发布地址：[GitHub v0.9.9](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.9)、[Gitee v0.9.9](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.9)。最终 APK、更新清单、校验文件、metadata、mapping、源码提交号及测试/构建/上传/核验日志已归档到 `build/github-gitee-release/v0.9.9/`，mapping 仅保存在本地。
- 变更记录：2026-10-29，按用户要求完成 `0.9.9 / 45` 双源发布；纳入已确认的全部已有改动，完成 469 项单测、正式构建、版本/签名与双端附件核验，保留真机覆盖安装、Room 仪器执行和 UI 验收边界。

### 0.9.8 双源发布（2026-10-26，已完成）

- 按用户要求将 `versionName` 从 `0.9.7` 递增至 `0.9.8`，`versionCode` 从 `43` 递增至 `44`，发布 `bcb2f03` 已提交的广告正文检查移除实现；搜索、共享列表和详情不再根据剪贴关键词切换广告资格。
- 复用现有版本配置、APK 命名、Windows/Bash 发布脚本及上一版已验证的本机 PowerShell 7 兼容入口，向既定 GitHub/Gitee Release 仓库发布正式签名 APK、`update.json` 和 `sha256.txt`；沿用 domestic 分发、internal 更新渠道和 AdMob 开关，不新增组件、类、方法或协议。
- 单元测试门禁：本轮只改版本元数据，无新增业务规则，不新增单测或采用 TDD。沿用本次广告改动已通过的 392 项单测，版本更新后运行 app 单测，执行原发布脚本 dry-run（含 `:app:assembleRelease`、R8）、APK 版本/签名/SHA256 核验及 `git diff --check`，再执行真实双源上传与远端附件验证。
- 日志与诊断计划：继续使用原发布日志与构建诊断，不新增 App 运行时日志；凭据仅由原脚本在本机读取，不写入输出或公开产物。按版本保留 APK、最终清单、metadata、mapping 和日志，mapping 只保存在本地。
- 版本配置中文注释同步更新，无数据库、备份或反射契约变化，无需调整备份和 keep 规则；保留原有 Windows 登录 Bash 启动兼容方式，仓库发布脚本不变。设备覆盖安装及应用内更新交互不在本次自动发布验证范围内。
- 验证结果：`.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --no-parallel --max-workers=2` 成功，311 项测试全部通过，失败/错误/跳过均为 0；原发布脚本 `:app:assembleRelease` 含 Kotlin 编译、R8、资源压缩与 lintVital 成功（预演 4m43s）。`apksigner verify --verbose --print-certs` 确认 v2 签名有效，证书与 `0.9.7` 一致；`aapt dump badging`、构建 metadata 与 Release BuildConfig 均确认为 `com.cla.clip.master / 0.9.8 / 44`，`git diff --check` 通过。
- 发布结果：GitHub/Gitee latest 均指向 `v0.9.8`，均包含 `ClipMaster-v0.9.8-admob.apk`、`update.json` 和 `sha256.txt`；GitHub 三附件服务端摘要及 Gitee 三附件回下载 SHA256 均与本地一致。APK 为 5,368,030 字节，SHA256 为 `23ba4d68a4dc2eca0cfcf71e5c0d2b3349de9f830d7a08dc1b2d9fe856c9b57a`。
- 发布地址：[GitHub v0.9.8](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.8)、[Gitee v0.9.8](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.8)。本版 APK、最终清单、校验文件、metadata、mapping 与预演/发布/核验日志归档到 `build/github-gitee-release/v0.9.8/`；mapping 仅保存在本地。
- 环境例外：首次单测因沙箱无法访问 E 盘 Gradle 缓存锁而失败，执行权限放行后同一命令成功；沿用既有 PowerShell 7/Bash 本机兼容入口，预演和真实发布均返回 0。既有 Gradle/Kotlin 弃用提示和 Shizuku R8 接口警告保留，未导致构建失败。
- 变更记录：2026-10-26，按用户要求完成 `0.9.8 / 44` 双源发布，补录 311 项 app 单测、正式构建、签名一致性及远端附件摘要验证结果；未执行设备覆盖安装或应用内更新交互验收。

### 0.9.7 双源发布（2026-10-26，已完成）

- 按用户要求将 `versionName` 从 `0.9.6` 递增至 `0.9.7`，`versionCode` 从 `42` 递增至 `43`，发布已提交的首页列表广告移除实现；详情、搜索、折叠等其他位置的广告保持现有接入。
- 复用版本配置、APK 命名、Windows/Bash 发布脚本和上一版验证过的本机启动兼容入口，向既定 GitHub/Gitee Release 仓库发布正式签名 APK、`update.json` 和 `sha256.txt`；沿用现有 domestic 分发、internal 更新渠道和 AdMob 开关，不新增组件、类、方法或协议。
- 单元测试门禁：仅递增版本元数据，无新增业务规则，不新增单测或采用 TDD；执行既有 app 单测、发布脚本 dry-run（含 `:app:assembleRelease` 和 R8）、APK 版本/签名/SHA256 核验及 `git diff --check`，再执行真实上传和两端附件验证。
- 日志与诊断计划：继续使用现有发布日志和构建诊断，不新增 App 运行时日志。凭据仅由原脚本在本机读取，不写入日志或公开产物；按版本保留 APK、最终清单、metadata、mapping 和发布日志，mapping 不上传公开仓库。
- 版本配置中文注释同步更新，无数据库、备份协议或反射契约变化，无需调整备份或 keep 规则。沿用上一版已记录的 Windows 登录 Bash 退出码兼容方式，不在本轮修改仓库发布脚本；设备覆盖安装及应用内更新交互不在本次自动发布验收中。
- 验证结果：`.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --no-parallel --max-workers=2` 成功，76 个测试套件共 316 项测试，失败/错误/跳过均为 0；原发布脚本 `:app:assembleRelease` 含 Kotlin 编译、R8、资源压缩与 lintVital 成功（预演 4m44s）。`apksigner verify --verbose` 确认 v2 签名有效，证书与 `0.9.6` 一致；`aapt dump badging` 与 Release BuildConfig 均确认为 `com.cla.clip.master / 0.9.7 / 43`，`git diff --check` 通过。
- 发布结果：GitHub/Gitee latest 均指向 `v0.9.7`，均包含 `ClipMaster-v0.9.7-admob.apk`、`update.json` 和 `sha256.txt`；GitHub 三附件服务端摘要及 Gitee 三附件回下载 SHA256 均与本地一致。APK 为 5,368,034 字节，SHA256 为 `b6f114b2ceb879478c439fbd7c6262f4d1c873b4821b0d77938ef37e739b8ee4`。
- 发布地址：[GitHub v0.9.7](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.7)、[Gitee v0.9.7](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.7)。本版 APK、最终清单、校验文件、metadata、mapping 与发布日志归档到 `build/github-gitee-release/v0.9.7/`；mapping 仅保存在本地。
- 环境例外：首次使用 Windows PowerShell 5.1 `-File` 读取 UTF-8 本地兼容入口时发生解析错误，尚未开始上传；切换已安装的 PowerShell 7 后，同一入口的预演和真实发布均成功，未更改仓库发布脚本。既有 Gradle 弃用提示和 Shizuku R8 接口警告保留，未导致构建失败。
- 变更记录：2026-10-26，按用户要求完成 `0.9.7 / 43` 双源发布，补录 316 项单测、正式构建、签名一致性和远端附件摘要验证结果；未执行设备覆盖安装或应用内更新交互验收。

### 0.9.6 双源发布（2026-10-26，已完成）

- 按用户要求将 `versionName` 从 `0.9.5` 递增至 `0.9.6`，`versionCode` 从 `41` 递增至 `42`，发布当前已移除下载历史、精简备份恢复的实现；Android 系统自动备份保持原状。
- 复用现有 BuildConfig、版本比较、APK 命名及 Windows/Bash 双端发布脚本，向既定 GitHub、Gitee Release 仓库上传正式签名 APK、`update.json` 和 `sha256.txt`；沿用当前渠道与广告开关，无新增组件、类、方法或协议。
- 单元测试门禁：仅调整版本元数据，无新增业务规则，不新增单测或采用 TDD；沿用上一轮已通过的 483 项单测，本轮通过脚本 dry-run 执行 Release/R8 构建，并核对 APK 版本、签名与 SHA256，再执行真实上传和远端附件核验。
- 日志与诊断沿用本文发布流程，不新增运行时日志；版本配置中文注释同步更新。无持久化结构、备份协议或反射契约变更，不调整备份与 keep 规则。按版本保留 mapping 和发布清单，凭据由原脚本在本机读取，不写入发布产物或日志。
- 验证结果：原发布脚本 `:app:assembleRelease`（含 Kotlin 编译、Release/R8、资源压缩与 lintVital）成功；`apksigner verify --verbose` 确认正式 APK 的 v2 签名有效，`aapt dump badging` 与 Release BuildConfig 均确认为 `com.cla.clip.master / 0.9.6 / 42`，渠道 `domestic`、更新清单 `internal`；`git diff --check` 通过。
- 发布结果：GitHub 与 Gitee 的 latest 均指向 `v0.9.6`，都包含 `ClipMaster-v0.9.6-admob.apk`、`update.json` 和 `sha256.txt`；GitHub 三附件的服务端摘要、Gitee 三附件回下载 SHA256 全部与本地一致。APK 为 5,368,030 字节，SHA256 为 `dd611214fb6f485326e9987e651d3eedbdc779e273851f4014ff8f3ff4ecc1b4`。
- 发布地址：[GitHub v0.9.6](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.6)、[Gitee v0.9.6](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.6)。APK、最终清单、校验文件、构建 metadata、mapping 与发布日志保留于本地 `build/github-gitee-release/v0.9.6/`，mapping 不上传公开仓库。
- 环境例外：Windows 原包装层的 `bash -l <script>` 再次在 dry-run 成功后返回 1；沿用 0.9.4 发布时已验证的内存兼容方式，改为登录 Bash 启动子 shell 执行原脚本，预演和真实双端发布均返回 0。仓库发布脚本未修改，仍需后续单独修复双击入口的退出码问题。
- 本轮未执行设备覆盖安装或应用内更新交互验收；已有 Gradle 弃用提示和 Shizuku `OnOpNotedListener` R8 警告未导致构建失败。

### 0.9.5 源码版本准备（2026-10-17，已完成）

- 本次提交保留工作区已有的 `versionName=0.9.5`、`versionCode=41`，同步版本码中文注释；上一版 `0.9.4 / 40` 的发布结果仍为历史记录。
- 复用既有 BuildConfig、版本比较、APK 命名和备份应用版本元数据，无新增组件、类、方法、数据协议或反混淆规则。本次 Git 提交与推送不代表已经发布 `v0.9.5` Release。
- 测试门禁：版本配置仅改变元数据，不新增单测或采用 TDD；运行 `.\gradlew.bat :app:testDebugUnitTest :base:general:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:minifyReleaseWithR8 --offline --console=plain --no-parallel --max-workers=2` 成功，app 332 项、base:general 121 项测试全部通过，无失败、错误或跳过。Debug/Release BuildConfig 均确认为 `0.9.5 / 41`；备份流程图同步检查与 `git diff --check` 通过。保留既有 Gradle/Kotlin 弃用提示和 Shizuku R8 接口警告。
- 日志与诊断沿用本文既有计划，不新增运行时日志；备份 schema、恢复规则和外部协议不受版本元数据影响。真机覆盖安装、WebView/媒体交互及后台网络验收仍需单独完成。

### 0.9.4 双源发布（2026-10-16，已完成）

- 按用户要求把 `versionName` 从 `0.9.3` 更新为 `0.9.4`，`versionCode` 从 `39` 单调递增至 `40`，基于当前工作区构建正式签名 APK。
- 复用现有 BuildConfig、APK 命名和 `scripts/publish_github_release_windows.cmd` / `scripts/publish_github_release.sh`，同步发布到既定 GitHub、Gitee Release 仓库；继续使用当前 domestic 渠道和广告开关，不新增组件、类、方法或外部协议。
- 测试门禁：本次只改版本元数据，没有新增业务规则，不新增单测、不采用 TDD；运行既有 `:app:testDebugUnitTest`、发布脚本 dry-run（含 `:app:assembleRelease` 和 R8）、APK 版本/签名/SHA256 核对与 `git diff --check`，再执行真实双端上传并核对附件。
- 日志与诊断沿用下方既有发布计划，不新增运行时日志；凭据仅供现有脚本读取，不写入文档或发布产物。无备份 schema、恢复规则、序列化或反射契约变化，不调整备份与 keep 规则。
- 验证结果：`.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --no-parallel --max-workers=2` 成功，67 个测试套件共 304 项测试全部通过，无失败、错误或跳过；原发布脚本的 `:app:assembleRelease`（含 R8、资源压缩与 lintVital）成功。`apksigner verify --verbose` 和 `aapt dump badging` 确认 APK 签名有效、包名 `com.cla.clip.master`、版本 `0.9.4 / 40`；`git diff --check` 通过。
- 发布结果：2026-10-16 已上传 GitHub 与 Gitee 的 `v0.9.4`，两端 latest 均指向该版本，均包含 `ClipMaster-v0.9.4-admob.apk`、`update.json` 和 `sha256.txt`。APK 为 5,510,174 字节，SHA256 为 `6c98f3dfaec198871b433a0d081859ad738ca39eff258187c79038b00327d561`；三个附件的 GitHub 服务端摘要与本地一致，三个 Gitee 附件回下载后的 SHA256 也与本地一致。
- 发布地址：[GitHub v0.9.4](https://github.com/clip-master-2/ClipMaster-Releases/releases/tag/v0.9.4)、[Gitee v0.9.4](https://gitee.com/clip-master-2/clip-master-releases/releases/tag/v0.9.4)。当前 APK 延续 AdMob 开关，文件名保留 `-admob`；mapping 与最终清单按版本保留在本地 `build/github-gitee-release/v0.9.4/`，mapping 不上传公开仓库。
- 环境例外：Windows 沙箱拒绝 Git Bash 创建 MSYS 系统对象，本轮经执行权限放行后构建与上传。该机 `bash -l <script>` 在自检和 dry-run 成功后仍返回 1；对照验证 `bash -l -c 'bash "$@"' clipmaster-release <script>` 返回 0，因此本轮只在内存调整 Windows 包装层的 Bash 启动参数，并显式选择已安装 Python，继续执行原 Bash 发布脚本及原有编码兼容/日志逻辑；dry-run 和真实双端发布最终均返回 0，仓库脚本未修改。后续维护 Windows 双击入口时应为此环境补回归验证。
- 本轮未做真机覆盖安装和应用内更新交互验收；既有 Gradle 弃用提示和 Shizuku `OnOpNotedListener` R8 警告仍保留，未导致构建失败。

### 上架前默认渠道恢复（2026-10-07，已完成）

- 当前尚未正式上架 Google Play，仓库默认 `appDistribution=domestic`；debug/release 均生成 `IS_PLAY_DISTRIBUTION=false`，手动检查走 Gitee 优先、GitHub 兜底，自动检查继续受“我的”页可见生命周期和 24 小时限频约束。
- 仅切换构建渠道，复用 `AppUpdateConfigFactory` 的源排序、`AppUpdateChecker` 的失败回退和 `MineVm` 的已有入口；不新增组件、类、方法或更新状态规则。AdMob debug/release 开关保持现有值，发行渠道与广告开关独立。
- 真正上架后再将 Play 分发构建切换为 `appDistribution=play`（或显式传入 `-PappDistribution=play`），由已有 Play 分支打开商店并关闭外部自动检查；不根据广告审核状态、语言或设备自动切换。
- 测试门禁：仅修改 Gradle 默认参数，不新增 JVM 单测、不采用测试先行；已有 parser/checker 测试保护源解析与失败回退，通过宿主单测、Kotlin 编译和 debug/release BuildConfig 核对验证接线。设备点击与真实网络可达性仍需人工验收；显式 `-P` 参数可覆盖仓库默认值，验收应核对实际构建渠道。
- 不新增日志：现有检查开始、HTTP、解析和源降级日志已覆盖恢复后的链路，沿用下方日志与脱敏契约。没有持久化数据、备份协议或反射/序列化契约变更，本轮不调整备份或 keep 规则，不重复执行 release/R8。
- 验证结果：执行 `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:generateReleaseBuildConfig :app:assembleDebug --offline --console=plain --no-parallel --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'` 成功，宿主 284 项单测全部通过，无失败、错误或跳过。debug/release 的 `IS_PLAY_DISTRIBUTION=false` 已核对，debug APK 已生成；`git diff --check` 通过。未安装设备或验证真实网络请求，未运行 release/R8；既有 Gradle、WebView 与 Shizuku 警告未在本轮处理。

### 自更新范围

- 新增轻量版本检查能力，优先从 Gitee release API 读取最新 Release 并定位 `update.json` 附件，失败后再从 GitHub public release 仓库读取 `update.json`。
- 在“我的”页提供“检查更新”入口，展示当前版本、检查状态、可用新版本和下载入口。
- Gitee 和 GitHub 同步承载 `update.json`、APK 与 `sha256.txt`；App 端按 Gitee 优先、GitHub 兜底的顺序检查，下载入口同时展示两端地址。
- 第一版只通过外部浏览器打开下载链接，不在 App 内直接下载、校验、安装 APK，不申请 `REQUEST_INSTALL_PACKAGES`。
- 第一版不做灰度、人群控制、后台静默下载、断点续传、启动页强弹窗或应用内安装器唤起。

## 用户体验

- “我的”页列表最后一项为“检查更新”，不单独分类，默认展示当前版本号；入口排列以 [我的页方案](mine_page_plan.md) 为准。
- 用户点击入口后立即检查更新，手动检查不受 24 小时限频影响。
- “我的”页可见时允许做一次轻量自动检查，但需要通过本地设置记录上次检查时间并按 24 小时限频；页面不可见时不启动检查任务。
- 检查到新版本时弹出更新提示，展示版本号、发布时间、更新日志、SHA256 摘要、Gitee 下载入口和 GitHub 下载入口。
- Gitee 下载入口标记为中国境内推荐；GitHub 入口作为备用下载路径。
- 因 Gitee Release 的 APK 下载在部分浏览器中可能保存为 `.apk.zip`，更新弹窗用一行弱提示说明“改回 `.apk` 后安装”，避免用户误以为需要解压。
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
- 本地设置只保存设备态：上次检查时间由 `AppUpdateCheckStore` 挂起读写。读取失败不当作首次检查；该状态不进入 Room，不纳入 WebDAV 备份恢复，也不触发 backup dirty。

## 发布流程

1. 使用 release 签名构建 APK，并确认 `versionCode` 单调递增；打包产物文件名应自动带上当前 `versionName`，例如 `ClipMaster-v0.4.1.apk`。
2. 推荐使用 `scripts/publish_github_release.sh` 自动完成 GitHub + Gitee 双端发布：脚本会执行 `:app:assembleRelease`，读取 release APK metadata，计算 APK SHA256，生成 `build/github-gitee-release/update.json` 与 `build/github-gitee-release/sha256.txt`，按 `v<versionName>` 创建或复用两端 Release，并上传 APK、`sha256.txt` 和 `update.json`。
3. Windows 11 设备可直接双击 `scripts/publish_github_release_windows.cmd` 执行真实发布；该双击入口保持批处理头 ASCII 内容并先切换到 UTF-8 代码页，避免 `cmd.exe` 把中文输出显示成乱码，同时在同一个文件内嵌 PowerShell 包装层，结束后暂停窗口方便查看结果。PowerShell 包装层会把完整输出写入 `build/github-gitee-release/windows-release.log`，便于失败后回看具体原因。需要先 dry-run 时，可从 PowerShell 运行 `cmd /c scripts\publish_github_release_windows.cmd --dry-run`。内嵌 PowerShell 包装层只查找 Git Bash、补齐 Bash 可见的 `python3` 兼容入口，强制 Windows Python 使用 UTF-8 stdout/stderr，并过滤 stdout 中的 CR 字符，避免 Bash 把 APK 文件名读成 `.apk\r` 或让 Gitee 表单字段里的中文变成系统代码页字节，然后以登录 Bash 环境直接调用 `scripts/publish_github_release.sh`；如果本机缺少 Git Bash 或 Python 3，会在构建和上传前失败。
4. 脚本默认先上传 Gitee APK，读取 Gitee API 返回的 `browser_download_url` 后重新生成最终 `update.json`，再把同一份 manifest 同步到 Gitee 和 GitHub，避免两个源的下载链接不一致。
5. 脚本使用 GitHub token 和 Gitee token 调用 REST API。日常发布推荐在用户级 `~/.gradle/gradle.properties` 中配置 `githubToken=<token>` 与 `giteeToken=<token>`，这样不需要每次输入，也不会随项目源码上传；脚本也兼容读取项目 `local.properties`。禁止把真实 token 放进项目 `gradle.properties`，因为该文件会进入源码仓库。临时覆盖可使用 `GITHUB_TOKEN` / `GITEE_TOKEN` 环境变量，且环境变量优先级最高。token 不写入仓库、不写入生成文件、不打印到日志。
6. 如需先检查生成内容，可运行 `scripts/publish_github_release.sh --dry-run`，Windows 11 可运行 `cmd /c scripts\publish_github_release_windows.cmd --dry-run`；该模式只构建 APK 和生成发布文件，不调用 GitHub/Gitee API。如需临时单端发布，可用 `--skip-github` 或 `--skip-gitee`。
7. 用旧版本 App 手动执行一次“检查更新”，验证 Gitee release API 可解析 `update.json` 附件、GitHub manifest 兜底可用、弹窗文案正确、Gitee 和 GitHub 下载入口均可打开。
8. 更新 public 仓库 README 的最新版、历史版本、Gitee/GitHub Release 入口和 SHA256 说明，确保用户手动访问时也能找到正确 APK。
9. 发布完成后在方案文档变更记录或 release note 中记录版本、发布时间、主要变更和验证结果。

## 日志与诊断计划

- 检查开始：`INFO`，记录触发方式、当前 versionCode、是否强制检查、是否命中限频，不记录完整 manifest URL。
- HTTP 结果：`INFO` 或 `WARN`，记录状态码、耗时、是否可重试、reasonCode，不记录响应体和完整 URL 查询串。
- 解析结果：`INFO`，记录目标 versionCode、channel、是否有更新、是否 forceUpdate，不记录更新日志全文。
- 降级路径：`WARN`，单个更新源不可达、Gitee Release 中缺少 `update.json` 附件或解析失败时记录 `sourceId`、reasonCode 和状态码；下一个源可用时继续检查，全部失败时让 UI 展示发布页入口。
- 打开外部链接：`INFO`，只记录 linkType，例如 `github`、`gitee`、`releasePage`，不记录完整下载 URL、token 或用户可恢复凭据。
- 高频自动检查按 24 小时限频，不输出重复成功日志，避免噪声。
- 发布脚本日志：仅输出构建阶段、生成文件路径、tag、repo、附件名称和最终 release 地址；Windows 包装层只额外输出选中的 Bash、Python、原脚本路径和 `build/github-gitee-release/windows-release.log` 完整运行日志，便于定位本机环境问题；禁止输出 `GITHUB_TOKEN`、`githubToken`、签名密码、完整构建环境变量或响应体中的敏感字段。GitHub/Gitee API 失败时输出 HTTP 状态码和低敏 `message`/`errors` 摘要，其中 GitHub 422 会额外提示 release/tag、同名附件、token 权限和仓库状态等常见排查方向；GitHub JSON 请求体通过临时 UTF-8 文件传给 `curl --data-binary @file`，避免 Windows Git Bash 把中文 release body 经命令行参数转码后触发 GitHub `Problems parsing JSON`；Gitee 按 tag 查询 Release 返回 JSON `null` 时应视为 Release 不存在并继续创建；Gitee multipart 表单字段值通过临时 UTF-8 文件传给 `curl -F name=<file`，避免 Windows Git Bash 把 release body 中文参数转成本机代码页后触发 Gitee `invalid byte sequence in UTF-8`；curl 遇到 DNS、连接、超时或 TLS 握手等连接层错误时按 `CURL_RETRY_COUNT`、`CURL_RETRY_DELAY_SECONDS` 和 `CURL_CONNECT_TIMEOUT_SECONDS` 做有限重试，仍失败时保留原 curl 退出码；如果 Gitee 已完成但 GitHub 因网络失败，重新执行脚本会复用已有 Release 并替换同名附件，或可临时使用 `--skip-github` 完成单端 Gitee 发布；Gitee 返回空响应、HTML 登录页或其它非 JSON 响应时，脚本应输出“响应体为空/响应体不是 JSON”的可读诊断，不暴露 Python traceback；日志不得输出 token 或完整敏感配置。

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
- 发布脚本验证：运行 `bash -n scripts/publish_github_release.sh`、`scripts/publish_github_release.sh --help`、`scripts/publish_github_release.sh --dry-run`、`scripts/publish_github_release.sh --self-test-json-errors`、`cmd /c scripts\publish_github_release_windows.cmd --self-test-json-errors`、`cmd /c scripts\publish_github_release_windows.cmd --help` 和 `git diff --check`，确认脚本语法、token 配置说明、Windows 11 环境适配、双击入口转调、release APK 构建、`update.json`/`sha256.txt` 生成、发布路径推导和非 JSON API 响应诊断正确；使用无效临时 token 做受控失败验证时，应能看到 GitHub/Gitee 返回的状态码与 `message`，但不能输出 token。真实上传需要在用户级 `~/.gradle/gradle.properties` 配置有效 `githubToken` 与 `giteeToken`，或通过 `GITHUB_TOKEN` / `GITEE_TOKEN` 临时覆盖后再执行非 dry-run。

当前已新增 `AppUpdateManifestParserTest` 和 `AppUpdateCheckerTest`，覆盖 parser/checker 第一版核心契约；本次实现已运行 `./gradlew :app:testDebugUnitTest --tests '*AppUpdate*'`，结果通过。完整 app 单元测试、编译和 diff 检查以任务最终验证结果为准。

## 已知取舍

- GitHub private 仓库不适合作为客户端版本检查源，因为客户端不能内置 token；public release 仓库只暴露已经准备外发的 APK。
- 第一版展示 SHA256 摘要但不做 App 内下载校验；后续若接入直接下载，必须在下载完成后做 SHA256 校验并重新评估安装权限、文件存储和失败恢复。
- Gitee Release 下载 APK 时存在浏览器把文件保存为 `.apk.zip` 的平台侧问题；当前先在弹窗中保留短提示，不在客户端引导解压，也不改变发布脚本的附件上传契约。
- 第一版不做启动时全局检查，优先把任务绑定到“我的”页可见生命周期，降低不可见页面网络消耗。
- `update.json` 解析 DTO 使用 `@Serializable` 和 `@SerialName` 固定外部字段名；0.9.4 发布已通过 release/R8 构建与远端清单一致性核验，混淆包中的运行时解析和旧版 App 更新交互仍需真机验收。

## 开放问题

- 是否需要在后续引入更稳定的国内静态文件源，例如对象存储、Gitee Pages 或 GitCode Pages，用于镜像 `update.json`，以减少 Gitee Release API 二段拉取复杂度。
- 是否需要增加“忽略本版本”能力；若增加，需要定义忽略版本的本地设置和强制更新时的覆盖规则。
- 是否需要把发布页入口放到关于页或错误弹窗外的常驻位置，方便两个自动源都不可达时用户主动查看。

## 变更记录

- 2026-10-31：完成 `0.9.18 / 54` 本地版本准备、553 项 JVM 单测、8 项版本脚本测试、Debug 和设备测试源码编译及版本核对，记录文件占用下复用隔离目录的验证方式；全部改动按用户要求纳入主分支提交推送，云端发布结果另行验收。

- 2026-10-31：按用户“版本号+1、提交当前所有修改并推送主分支”的要求准备 `0.9.18 / 54`，纳入广告离屏回绑修复及全部对应测试与文档，记录复用、验证和自动发布边界。

- 2026-10-31：完成 `0.9.17 / 53` 全部修改提交推送、519 项 JVM 单测、8 项版本脚本测试、Release/R8 及原脚本双源发行；两端 latest 和六项附件摘要核验通过，补录最终签名/摘要、源码提交与本地归档，发布小节标记已完成。保留默认 Debug 文件占用的隔离验证取舍及设备未重复验收边界。

- 2026-10-31：按用户要求准备 `0.9.17 / 53`，将首次说明修复、检查更新入口置底及全部既有修改纳入主分支提交，记录先推送源码再脚本双源发布的流程、复用、验证与日志边界。

- 2026-10-31：检查更新入口移至我的页列表末尾，取消其独立分类；仅调整 UI 编排，检查协议、日志、备份与混淆边界不变，验证记录见我的页方案。

- 2026-10-30：按用户“版本号+1、提交所有修改并推送主分支”的要求准备 `0.9.16 / 52`，纳入隐私政策入口、离线阅读、卡片样式及全部对应文档测试，记录复用、验证计划和自动发布边界。
- 2026-10-30：完成 517 项 JVM 单测、8 项版本判定测试、Debug 编译、Release/R8、资产一致性及派生版本核对，版本准备标记已完成；全部 20 个文件普通提交推送，设备待验收项与既有构建警告按功能主方案保留。

- 2026-10-30：按用户“版本号+1、提交所有修改并推送主分支”的要求准备 `0.9.15 / 51`，将说明展开及对应测试、文档全部纳入本轮提交范围，记录验证计划、复用和自动发布边界。
- 2026-10-30：完成 513 项 JVM 单测、8 项版本脚本测试、Kotlin/Android 测试编译及生成版本核对，按全部 13 个文件的范围完成版本准备，普通提交推送沿用现有 `origin/main`；设备与云端发布验收边界见本版记录。
- 2026-10-30：按用户“版本号+1、提交所有修改”的要求准备 `0.9.14 / 50`，记录全部工作区改动的提交范围、复用决策、验证计划和本地提交边界；功能细节继续引用既有主方案。
- 2026-10-30：完成 513 项 JVM 单测、8 项版本脚本单测、Debug Kotlin 与 Android 测试 Kotlin/Java 编译、BuildConfig 版本及备份 HTML 同步核对，记录验证结果并将本轮版本递增状态更新为已完成；设备交互及 Release/R8 未在本轮重复运行。
- 2026-10-29：按用户明确指令准备 `0.9.13 / 49`，将版本变更与版本门禁一起推送，触发当前源码仓库自动 Release；原因：实际验证“修改版本并推送后自动构建发布”的完整链路。

- 2026-10-29：按用户更正，将源码仓库自动发行限定为 `main` push 前后版本字段发生变化；规划版本比较脚本、回归测试和带广告 Release 工作流，复用既有签名及构建配置。原因：普通代码提交不应自动创建 Release，版本提交应自动完成构建和 APK 附件上传。
- 2026-10-29：完成版本判定 job、静态版本解析脚本与当前仓库 Release 上传步骤，保留用户同步到本地的正式广告包配置；17 项 Python 测试、真实提交 CLI 检查、YAML/权限检查、7 段 Bash 语法及差异检查通过。原因：落实“仅版本变化自动发布”的最终范围；云端 Secrets、Release/R8 和真实上传仍待版本推送验收。
- 2026-10-29：独立只读审查无必改项，复跑 8 项版本测试及差异检查通过；补充专用标签命名空间和强推丢失 before 提交的边界，避免把常规 push 验证解释为任意历史改写或手工标签场景均已覆盖。

- 2026-10-26（已完成）：按用户要求发布 `0.9.6 / 42`，原脚本完成 GitHub/Gitee 双端上传，两端 latest、六项附件摘要、APK 签名与版本核验通过；保存本地 mapping 和发布记录，沿用已记录的本机 Bash 启动兼容方式。本轮仅修改版本配置与方案文档，无新增业务逻辑、单测或运行时日志，设备验收未执行。

- 2026-10-17：按用户提交并推送主分支的要求，纳入已有 `0.9.5 / 41` 版本准备并同步中文注释，补充当前源码版本、验证范围及尚未发布 Release 的边界；原因是工作区版本已更新，而注释和版本方案仍停在上一版。

- 2026-10-16（已完成）：按用户要求将版本从 `0.9.3 / 39` 升至 `0.9.4 / 40`，基于当前工作区构建正式签名 AdMob APK 并通过原发布脚本上传 GitHub、Gitee；304 项宿主单测、Release/R8、签名、版本、GitHub 三附件服务端摘要及 Gitee 三附件回下载 SHA256 均通过，两个 latest 均为 `v0.9.4`。复用既有能力，无新增类/方法/测试或运行时日志，版本配置简体中文注释已同步；记录本机 Git Bash 启动方式的临时兼容措施、mapping 留存和未做真机验收的边界。

- 2026-10-11：按用户要求提交并推送已有版本准备改动，`versionCode` 从 37 增至 38，`versionName` 从 0.7.3 更新为 0.8.3，并同步版本码中文注释。复用现有 BuildConfig、版本比较、备份应用版本元数据和 APK 命名规则，无新增组件、类、方法或协议字段。仅改变构建元数据，无新的独立业务规则，不新增单测或采用 TDD。执行 `.\gradlew.bat :app:generateDebugBuildConfig :app:generateReleaseBuildConfig :app:processDebugMainManifest --offline --console=plain --no-parallel --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'` 成功，Release BuildConfig 核对为 `38 / 0.8.3`，`git diff --check` 通过；Debug BuildConfig 与 Manifest 产物在收尾核对时缺失，不能确认其最终产物。完整 Debug Kotlin 编译未通过：首次因 `shizuku` 的 `R.jar` 缺失失败，重跑 `shizuku` 与 `base:hidden-api` 的资源生成后，AAPT 又因无法写入资源输出目录中的 `R.txt` 失败；没有扩大源码修改范围，发布前需要补验完整编译及生成产物。沿用既有日志与诊断计划，不新增运行时日志；没有备份 schema、恢复规则或反射/序列化契约变化，不重复运行业务单测或 release/R8。源码版本提交不代表公开 Release 已发布，设备升级与真实网络验证留待发布验收。

- 2026-10-07（已完成）：按用户要求提交已有 `0.7.2` 版本准备改动，`versionCode` 从 35 增至 36，`versionName` 从 0.7.1 更新为 0.7.2，并修正版本码中文注释。复用现有 BuildConfig、版本比较、备份中的应用版本元数据和 APK 命名，无新增组件、类、方法或协议字段；默认 domestic 及双发布源流程保持现状。仅改变构建元数据，无新的纯逻辑，测试门禁判断不新增单测或走 TDD；执行 `.\gradlew.bat :app:compileDebugKotlin :app:generateReleaseBuildConfig :app:processDebugMainManifest --offline --console=plain --no-parallel --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'` 成功，debug/release BuildConfig 和 debug Manifest 均核对为 `36 / 0.7.2`，两种变体渠道标记均为 false，`git diff --check` 通过。不重复运行业务单测或 release/R8。日志与诊断沿用既有计划，不新增运行时日志，无备份 schema/恢复规则或反混淆契约变化。源码版本提交不代表公开 Release 已发布，真机更新与网络验证留待发布验收。

- 2026-10-07：恢复上架前默认 `domestic` 渠道，继续 Gitee 优先、GitHub 兜底；原因是当前尚未真正上架 Google Play，商店更新分支留待正式上架后显式切换。构建参数说明和广告主文档同步渠道差异，不复制更新实现。

- 2026-10-07：按用户提交要求纳入已有版本号改动，`versionCode` 从 34 增至 35，`versionName` 从 0.7.0 更新为 0.7.1；复用现有版本比较、BuildConfig 与 APK 文件命名，无新增组件、方法、数据协议或运行时日志。仅为源码版本准备，不表示公开 Release 已发布。此改动只有版本元数据，无可独立测试的新规则，故不新增单测或走 TDD；通过 Debug Kotlin 编译、Manifest/BuildConfig 生成及版本字段核对验证，不重复运行业务单测或 Release/R8。已提交的前一轮诊断增强仍需实际后台留证。

- 2026-09-29：自升级上次检查时间改由独立 `AppUpdateCheckStore` 挂起读写，读取失败不当作首次检查；原因是宿主设置改为 DataStore。

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
- 2026-05-30：新增 Windows 11 PowerShell 发布包装入口和可双击 `.cmd` 启动器，并将 `.cmd` 收敛为 ASCII + UTF-8 代码页入口；原因是原始 Bash 脚本在 Windows 设备上需要 Git Bash 登录环境、`shasum` 和可用 `python3` 命令名，包装层负责环境适配、完整日志、Windows Python UTF-8 输出、CRLF 输出过滤和稳定退出码，双击入口负责免命令行启动且避免 `cmd.exe` 中文乱码，并继续复用原发布脚本。
- 2026-05-30：Gitee multipart 表单字段改为临时 UTF-8 文件传值；原因是 Windows Git Bash 调用 `curl -F body=中文` 时可能在 argv 边界发生代码页转换，导致 Gitee 创建 Release 返回 `invalid byte sequence in UTF-8`。
- 2026-05-31：将 Windows 发布入口合并为单个 `publish_github_release_windows.cmd`；原因是双击入口和 PowerShell 包装层可以通过批处理头 + 内嵌 PowerShell 正文共存，减少用户侧需要识别的脚本数量，同时保留 UTF-8、日志、Git Bash 查找和 `python3` shim 逻辑。
- 2026-05-31：为发布脚本的 GitHub/Gitee API 和附件上传请求增加 curl 连接层重试；原因是 Windows 设备访问 `api.github.com` 可能出现 `SSL_ERROR_SYSCALL` 等短暂 TLS/网络错误，脚本应先自动重试，失败后仍保留原退出码和可重跑的发布状态。
- 2026-05-31：GitHub JSON API 请求体改为临时 UTF-8 文件传输；原因是 Windows Git Bash 通过 `curl --data` 传递中文 release body 时可能发生命令行编码转换，导致 GitHub 返回 `Problems parsing JSON`。
- 2026-05-31：更新弹窗新增 `.apk.zip` 短提示；原因是 Gitee Release APK 在部分浏览器下载后会被追加 `.zip` 后缀，需要让用户知道改回 `.apk` 即可安装，同时避免冗长说明干扰更新流程。


## 2026-09-18 AdMob 接入变更（实现中）

显式 Play 构建关闭自动外部版本检查，手动更新入口跳转当前包名的 Google Play 商店页面；上架前默认使用 `domestic`，维持 Gitee 优先、GitHub 兜底，最终默认渠道以上方 2026-10-07 说明为准。

共享广告的隐私、生命周期、资源、布局及低敏日志契约以 [广告主方案](ad_module_plan.md) 为准；本页验证重点为业务交互不回归及广告异步加载时的内容锚点保护。

变更记录：2026-09-19，按实际接入更新页面差异与边界；集成编译/测试结果见主方案。
