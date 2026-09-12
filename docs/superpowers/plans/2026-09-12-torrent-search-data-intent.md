# TorrentSearch 数据与 Intent 接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不进入完整页面实现的前提下，接通 TorrentSearch 外部输入、独立 Room v1 与 feature-local DataStore，为后续页面、备份和安全协议提供稳定数据入口。

**Architecture:** `TorrentSearchActivity` 只把 Android `Intent` 转成无 Android 依赖的输入快照；纯 resolver 负责动作优先级、来源 code 与现有 512 字符规范化。独立 Room 保存书签、搜索历史和已查看记录，Repository 隐藏实体与 DAO；Preferences DataStore 保存 Provider 开关、安全模式和排序稳定 code。数据库、DataStore 和 Repository 都由 feature 自己的 Hilt 模块提供，不进入 API 模块或宿主主库。

**Tech Stack:** Kotlin、Android Intent、Jetpack Room 2.8.4、Preferences DataStore 1.2.1、Hilt、Coroutines Flow、JUnit 4

**Spec:** `docs/magnet_search_plan.md`

## Global Constraints

- minSdk 24、compileSdk 36；功能关闭构建不得引用实现模块类型。
- Room 文件固定为 `torrent_search_feature.db`，初始 schemaVersion 为 1；DataStore 文件固定为 `torrent_search_settings.preferences_pb`。
- Provider、分类、排序和启动来源只持久化稳定 code/ID，不依赖枚举 `name`、`ordinal` 或类名。
- 书签以规范化 infoHash 去重，搜索历史以规范化查询去重，已查看以 infoHash 去重。
- 本批不得记录查询、书签内容、magnet、URL、Cookie、API Key 或数据库正文。
- 书签、历史、已查看与非敏感设置后续进入自定义备份；安全模式确认状态、Provider 本机选择及整个数据库/DataStore 先由系统 Auto Backup 排除，任务 7 再接白名单导出。
- 新增或修改的每个 Kotlin 方法和变量声明必须有贴近的简体中文注释。

---

### Task 1: 外部 Intent 解析与 Activity 消费

**Files:**
- Create: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/input/TorrentSearchIntentResolver.kt`
- Modify: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/TorrentSearchActivity.kt`
- Test: `feature/torrent-search/src/test/java/com/cla/clip/feature/torrent/search/input/TorrentSearchIntentResolverTest.kt`

**Interfaces:**
- Consumes: `TorrentSearchInputNormalizer.normalize(String?)`、`TorrentSearchLaunchSource.fromCode(String?)`。
- Produces: `TorrentSearchIntentPayload`、`TorrentSearchLaunchRequest`、`TorrentSearchIntentResolver.resolve(payload)`；Activity 的 `onCreate` 与 `onNewIntent` 都更新同一启动快照。

- [x] **Step 1: 写 resolver 失败测试**

  覆盖显式 extra、`ACTION_SEARCH/SearchManager.QUERY`、`ACTION_SEND/EXTRA_TEXT`、`ACTION_PROCESS_TEXT/EXTRA_PROCESS_TEXT`、非 `text/plain` 拒绝、控制字符清洗和超限只预填。

- [x] **Step 2: 运行定向测试并确认 RED**

  Run: `./gradlew :feature:torrent-search:testDebugUnitTest --tests '*TorrentSearchIntentResolverTest' -PenableMagnetFeature=true --no-daemon`

- [x] **Step 3: 实现纯 resolver 与薄 Android 适配**

  系统 action 决定稳定启动来源并优先读取对应系统 extra；未知/显式启动才读取 feature extra。Activity 不启动网络、不写历史，只保存供后续搜索页消费的最新快照。

- [x] **Step 4: 复跑定向测试并确认 GREEN**

### Task 2: Room v1、DAO 与 Repository

**Files:**
- Create: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/data/local/TorrentSearchEntities.kt`
- Create: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/data/local/TorrentSearchDaos.kt`
- Create: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/data/local/TorrentSearchDatabase.kt`
- Create: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/data/model/TorrentSearchStoredModels.kt`
- Create: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/data/repository/TorrentSearchRepositories.kt`
- Create: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/di/TorrentSearchDataModule.kt`
- Modify: `feature/torrent-search/build.gradle.kts`
- Create: `feature/torrent-search/schemas/com.cla.clip.feature.torrent.search.data.local.TorrentSearchDatabase/1.json`
- Test: `feature/torrent-search/src/test/java/com/cla/clip/feature/torrent/search/data/repository/TorrentSearchRepositoriesTest.kt`

**Interfaces:**
- Consumes: `Category.fromCode`、`ProviderId` 和已有输入规范化器。
- Produces: `BookmarkRepository`、`TorrentSearchHistoryRepository`、`ViewedTorrentRepository` 及对应 Flow/写入/删除/清理契约。

- [x] **Step 1: 写 Repository 失败测试**

  使用内存 fake DAO 验证书签按 infoHash 替换、历史清洗并拒绝超限输入、已查看重复刷新时间；测试只使用合成 hash 与文本。

- [x] **Step 2: 运行定向测试并确认 RED**

  Run: `./gradlew :feature:torrent-search:testDebugUnitTest --tests '*TorrentSearchRepositoriesTest' -PenableMagnetFeature=true --no-daemon`

- [x] **Step 3: 实现 Room schema 与 Repository**

  三张表分别使用 `info_hash`、`normalized_query`、`info_hash` 稳定唯一键；DAO 提供按时间倒序 Flow 及单项/清理写入，任务 7 再按实际备份管线补充批量恢复与 high-water mark 分页接口。Repository 负责实体映射、输入边界与低敏操作日志，异常保持传播。

- [x] **Step 4: 生成并检查 Room v1 schema**

  Run: `./gradlew :feature:torrent-search:compileDebugKotlin -PenableMagnetFeature=true --no-daemon`
  Expected: `feature/torrent-search/schemas/com.cla.clip.feature.torrent.search.data.local.TorrentSearchDatabase/1.json` 生成且三个唯一键、索引和列名与方案一致。

- [x] **Step 5: 复跑 Repository 定向测试并确认 GREEN**

### Task 3: Preferences DataStore 设置仓库

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `feature/torrent-search/build.gradle.kts`
- Create: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/settings/TorrentSearchSettingsRepository.kt`
- Modify: `feature/torrent-search/src/main/java/com/cla/clip/feature/torrent/search/di/TorrentSearchDataModule.kt`
- Test: `feature/torrent-search/src/test/java/com/cla/clip/feature/torrent/search/settings/TorrentSearchSettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `ProviderId`、`SortCriteria.fromCode`、`SortOrder.fromCode`。
- Produces: `TorrentSearchSettings` Flow，以及 Provider 开关、安全模式、排序条件、排序方向的原子更新方法。

- [x] **Step 1: 写 DataStore Repository 失败测试**

  使用真实 Preferences 对象和可控 fake DataStore 验证默认值、未知稳定 code 回退、Provider ID 去重/拒绝非法值及更新后的 Flow 快照。

- [x] **Step 2: 运行定向测试并确认 RED**

  Run: `./gradlew :feature:torrent-search:testDebugUnitTest --tests '*TorrentSearchSettingsRepositoryTest' -PenableMagnetFeature=true --no-daemon`

- [x] **Step 3: 实现设置映射与 DataStore 提供者**

  数据损坏以安全默认值降级；只保存稳定 code/ID，不保存 Provider 展示名、URL、Cookie、API Key、SAF URI 或搜索内容。写入日志只包含设置 key 类别和成功/失败状态，不包含值集合。

- [x] **Step 4: 复跑设置定向测试并确认 GREEN**

### Task 4: 文档、备份边界与组合验证

**Files:**
- Modify: `docs/magnet_search_plan.md`
- Modify: `docs/superpowers/plans/2026-08-30-torrent-search-integration.md`
- Modify: `docs/webdav_backup_plan.md`
- Modify: `feature/torrent-search/THIRD_PARTY_DEPENDENCIES.md`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`

**Interfaces:**
- Consumes: Tasks 1-3 的最终文件与验证结果。
- Produces: 与代码一致的 05B 状态、日志/隐私边界、备份纳入/排除决策和依赖记录。

- [x] **Step 1: 更新方案与变更记录**

  明确本批只建立数据和输入基础设施，不自动搜索、不写历史、不接完整页面；记录数据库/DataStore 的自定义备份白名单方向，并精确落地系统 Auto Backup 与设备迁移的整文件排除。

- [x] **Step 2: 运行组合门禁**

  Run: `./gradlew :feature:torrent-search:testDebugUnitTest :feature:torrent-search:compileDebugKotlin :app:compileDebugKotlin -PenableMagnetFeature=true --no-daemon`

- [x] **Step 3: 运行格式检查**

  Run: `git diff --check`

- [x] **Step 4: 检查未提交范围**

  只报告本批新增文件及原有 04E7/04F/05A 改动，不暂存、不提交、不覆盖用户改动。

## 完成记录

- 2026-09-12：三组定向测试均经历缺类型/缺实现 RED 并转为 GREEN；Room v1 schema 已生成并核对三张表的稳定主键与时间索引。
- 2026-09-12：最终组合门禁 `BUILD SUCCESSFUL in 2m 9s`，JUnit XML 为 503 tests / 65 suites、0 failures/errors/skipped；模块 Kotlin、中文注释门禁、系统备份资源合并及启用功能的 App Kotlin 编译通过。
- 2026-09-12：`git diff --check` 无错误，仅报告 Windows 工作树 LF/CRLF 转换提示；工作区保持未暂存、未提交，原有 04E7/04F/05A 改动未被覆盖。
- 2026-09-12：后续普通化 TorrentSearch 模块构建时移除专属中文注释/发布扫描任务和任务 fixture；本计划的可重复组合命令改用标准模块单测、编译与 App 启用路径编译，历史完成记录仍保留当时门禁事实。
- 未在本批执行：关闭功能编译、release/R8、API 24/36 设备与真实联网；这些属于任务 8 发布门禁。任务 7 仍需补自定义白名单备份、dirty 标记、批量/分页 DAO、幂等恢复计数和报告。
