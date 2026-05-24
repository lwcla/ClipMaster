状态：已确认

# 单元测试建设计划

## 当前状态

当前仓库已经具备基础单元测试入口：`app`、`base:general`、`base:hidden-api` 和 `shizuku` 模块都有 `src/test` 目录，并统一接入 JUnit4。`base:general` 已有 `BackupPackageIoTest` 和 `BackupRestoreTimeNormalizerTest`，覆盖备份包协议、zip entry 安全边界和恢复时间归一化，是后续测试命名、断言粒度和临时文件清理方式的主要参考。

现有测试能力仍偏少，业务规则大多依赖编译验证和人工验证。项目中已经拆出不少适合 JVM 单元测试的纯逻辑能力，例如磁力链接规范化、备份协议、图片格式识别、图片下载校验、文件名生成、搜索高亮、时间排序规则和 UI 背后的状态派生判断。后续应优先补齐这些低依赖、高回归风险的纯逻辑测试，再逐步扩展到 ViewModel 状态流、DAO 查询和 Worker 协作者。

## 目标

- 建立一套轻量、可持续的单元测试分层策略，避免一次性引入过重测试框架。
- 优先覆盖纯 Kotlin 规则、失败契约、排序过滤、数据协议和边界输入。
- 让后续重构图片下载、备份恢复、搜索、磁力、列表排序等核心能力时有快速回归保护。
- 明确哪些场景使用 JVM unit test，哪些场景必须放到 `androidTest` 或手动验证。
- 明确选择性 TDD 规则：高风险规则和 bug 修复优先测试先行；从 UI、导航或入口流程问题中抽出的纯规则、状态机、mapper、formatter、predicate 或派生状态判断也必须先测；低价值 UI/接线改动不机械强制。
- 把测试依赖、命名、目录、验证命令和隐私边界固定下来，减少后续补测试时的临时决策。
- 将单元测试判断纳入代码修改门禁，后续新增或修改代码都必须通过 `$repo-unit-test-gate` 判断是否需要补测试。

## 范围

第一阶段优先纳入：

- `base/general/backup`：备份包 IO、manifest/checksum、恢复时间归一化、恢复报告统计、文件名时间戳和备份类型识别。
- `base/general/magnet`：infoHash 规范化、magnet URI 构造、搜索词规范化、高亮 formatter、源查询格式化。
- `app/image/format`：图片文件头识别、扩展名/MIME 映射、GIF/WebP/APNG/AVIF 动画元数据读取。
- `app/image/download`：请求头构造、下载文件名、内容校验、过滤失败语义、发布结果边界模型。
- `app` 页面或 feature 中已抽出的纯 formatter、mapper、validator、predicate、派生状态判断和状态归约函数。

第二阶段再纳入：

- ViewModel 状态流：搜索页、备份恢复页、图片提取页、下载记录页等关键页面的事件到 UI state/effect 的转换。
- DAO/Room 查询：普通/折叠/回收站范围隔离、置顶优先排序、搜索筛选、恢复幂等 upsert、唯一索引约束。
- Worker 协作者：下载、备份、清理、媒体重新定位等 Worker 下沉后的纯逻辑或可替换 IO 协作者。

暂不作为单元测试主目标：

- Compose 视觉布局、手势细节和动画观感，继续以方案文档检查、Preview、人工验证和必要的 Compose UI test 兜底。
- 真实 WebView 页面加载、真实 WebDAV 服务、真实 MediaStore 发布、系统相册播放能力和 Shizuku 跨进程行为。
- 完整端到端恢复、下载或页面导航流程，这些更适合后续集成测试或手动验收脚本。

## 单元测试门禁规则

后续新增或修改代码时，必须先使用 `$repo-unit-test-gate` 做单元测试判断。判断结论必须覆盖三个问题：

1. 本次改动是否需要新增或更新单元测试。
2. 本次改动至少需要运行哪些已有测试、编译或 release/R8 验证。
3. 如果不新增测试，原因、风险和后续补测边界是什么。

选择性 TDD 判断：

- 高风险纯逻辑、数据协议、排序过滤、失败契约、ViewModel 状态流、DAO 查询和 bug 修复，默认先写能失败的单元测试或回归测试，再实现生产代码。测试应表达公开契约、输入输出、状态转移或用户可观察行为，而不是绑定私有 helper、调用顺序或临时实现细节。
- UI、导航或入口流程问题如果能抽出纯规则、状态机、mapper、formatter、predicate 或派生状态判断，应先为抽出的规则写能失败的测试，再修改生产代码；不能因为问题入口看起来是 UI 或导航，就把可单测规则归类为薄接线。
- bug 修复优先写能复现旧问题的失败测试。测试通过前不要把修复视为完成；如果旧问题无法用 JVM 单测稳定复现，应记录原因，并评估 `androidTest`、集成测试、人工验证或日志诊断是否更合适。
- 新增复杂纯逻辑时，如果难以先写测试，应先检查是否需要拆出纯协作者、fake clock、fake dispatcher 或可替换依赖；不要因为入口类难测就直接绕过测试先行。
- 纯 Compose 样式、文案、图标、Preview、没有抽出独立规则的薄导航接线、字符串资源或已被既有测试覆盖的等价重构，不强制 TDD。此类改动仍要说明不走测试先行的原因、剩余风险和已运行的编译/既有测试/人工验证。
- 选择性 TDD 的目标是保护契约和历史 bug，不是让每个小改动都先写测试；当测试成本明显高于风险时，允许记录例外并选择更贴近的验证方式。

默认需要新增或更新单元测试的改动：

- 纯 Kotlin 逻辑、字节解析、URL/字符串规范化、文件名清理、候选去重、格式识别、排序过滤。
- mapper、formatter、parser、validator、predicate、派生状态判断、comparator、builder、sniffer、reader 等职责单一的规则类或扩展属性。
- DAO 查询、Room 迁移、唯一索引、分页排序、范围隔离和恢复幂等规则。
- ViewModel 状态流、一次性事件、状态归约、重试/取消/降级分支。
- 备份协议、序列化字段、manifest/checksum、恢复报告、R8/keep 相关稳定契约。
- Worker 下沉协作者中的可测试业务规则、失败契约和资源清理策略。

可以不新增测试但必须说明原因的改动：

- 纯 Compose 样式、间距、颜色、图标、Preview 或简单文案调整。
- 很薄的导航接线、路由入口或字符串资源变更，且没有抽出新的独立规则。
- 不改变行为的等价重构，且已有测试已经覆盖核心规则。
- 依赖真实 Android runtime、WebView、MediaStore、Shizuku、系统权限或外部网络的流程，短期只适合人工验证、`androidTest` 或后续集成测试。

不新增测试不等于不验证。即使判断不补测试，也应运行受影响模块的已有 `testDebugUnitTest`、`compileDebugKotlin` 或更贴近的验证命令；无法运行时必须在最终说明中写明原因和风险。

## 分层策略

### JVM 纯单元测试

默认优先选择 JVM unit test，目录放在对应模块的 `src/test/java`，包名跟随被测类所在包。测试类命名使用 `被测类名Test`，测试方法名使用可读的行为描述，聚焦输入、输出和失败契约。

适用对象：

- 不依赖 Android `Context`、View、系统服务、Room runtime、Hilt 图或真实协程调度器的纯 Kotlin 代码。
- 字节解析、URL/字符串规范化、文件名清理、候选去重、格式识别、排序过滤、mapper、formatter、validator。
- 使用临时文件即可覆盖的 zip、JSONL、checksum、manifest 和路径安全规则。

### ViewModel 状态测试

当页面状态规则开始复杂或修复过状态回归后，再补 ViewModel 测试。ViewModel 测试应使用 fake repository、fake clock、fake dispatcher 和可控输入流，避免直接依赖真实 Room、Hilt 或 Android runtime。

建议新增依赖时机：

- 需要测试 `StateFlow`、`SharedFlow` 或一次性事件时，再在版本目录中加入 `kotlinx-coroutines-test` 和 Turbine。
- 暂不默认引入 MockK；如果 fake 对象已经难以表达调用契约，再评估是否引入 mock 框架。

### DAO 与 Room 测试

DAO 查询、迁移和唯一索引测试应作为第二阶段推进。优先覆盖用户数据隔离和排序规则，因为这些最容易在 UI 看起来正常时悄悄回归。

建议新增依赖时机：

- 需要内存 Room 数据库、迁移校验或 DAO 查询验证时，再加入 `androidx.room:room-testing`。
- 简单 DAO 查询可优先放在 `androidTest`；如果后续引入 Robolectric，必须先评估构建耗时和 Android Gradle Plugin 兼容性。

### Worker 协作者测试

Worker 本体保留少量集成验证即可。核心规则应尽量下沉到可单测协作者，例如请求头构造、文件名、下载校验、发布前格式识别、备份保留清理策略、媒体匹配规则。这样测试可以避开 WorkManager、通知、权限和系统服务。

## 优先级计划

### P0：建立基线

1. 清点现有 `ExampleUnitTest`，确认是否保留、删除或替换为真实业务测试。
2. 运行并记录当前基线命令：`./gradlew :base:general:testDebugUnitTest`、`./gradlew :app:testDebugUnitTest`。
3. 后续每个新增测试 PR 至少运行受影响模块的 `testDebugUnitTest` 和 `git diff --check`。
4. 将 `$repo-unit-test-gate` 作为后续代码修改前的固定判断步骤，并在最终回复中报告判断结果。

### P1：纯逻辑高收益测试

优先新增以下测试：

- `MagnetInfoHashNormalizerTest`：大小写、空白、非法字符、长度、btih 前缀和归一化输出。
- `MagnetUriBuilderTest`：infoHash、displayName、tracker 参数编码和非法输入失败契约。
- `MagnetSearchHighlightFormatterTest`：多词命中、大小写、重复词、空关键词和不截断命中词。
- `ImageFormatSnifferTest`：JPEG、PNG、GIF、WebP、APNG、AVIF、未知头和短字节数组。
- `ImageAnimationMetadataReaderTest`：GIF/WebP/APNG 动画标记、时长兜底和解析失败返回语义。
- `ImageDownloadValidatorTest`：过小文件、非图片响应、真实文件头与响应头不一致、可恢复/不可恢复失败原因。
- `ImageDownloadFileNamesTest`：URL 无后缀、query 参数、非法文件名字符、重复候选和 MIME 到扩展名映射。

### P2：数据协议与恢复规则

继续扩展备份测试：

- v1/v2/v3 协议兼容读取。
- 缺失可选 JSONL 文件时的兼容行为。
- 恢复报告新增/更新/跳过统计。
- 来源 App、链接预览、搜索历史、磁力历史和下载记录的幂等恢复。
- 普通备份与历史 safety 文件识别、排序和隐藏规则。
- manifest checksum、schemaVersion、dataFormat 和应用 id 校验失败。

### P3：DAO/Room 查询测试

优先覆盖：

- 普通列表不包含折叠和回收站数据。
- 折叠列表按置顶优先，再按 `folded_at` 倒序。
- 回收站按删除时间展示，且不污染普通列表和折叠列表。
- 搜索页在普通范围、折叠范围、回收站范围的隔离。
- 磁力历史和磁力下载记录的唯一索引与倒序分页。
- 备份恢复 upsert 后重复恢复不产生重复数据。

### P4：ViewModel 与状态流测试

优先覆盖近期高风险状态机：

- `BackupRestoreVm`：读取中、预览、恢复中、完成、失败、取消返回确认和一次性请求消费。
- `SearchViewModel`：关键词、防抖后的查询触发、筛选条件、历史写入和清空。
- `ImageExtractVm`：候选实时合并、选择状态保留、重新提取会话隔离、确认下载失败后保留候选。
- `DownloadHistoryVm`：视频/图片/磁力 Tab、多选、删除确认和分页刷新触发。

## 长期维护建议

### 1. 先定测试价值优先级

不以覆盖率数字作为第一阶段目标，先覆盖最怕回归、最影响用户数据安全和行为稳定的规则。当前优先级为：备份恢复、图片下载与格式识别、磁力规范化、DAO 查询排序隔离、ViewModel 状态机。

### 2. 建立固定测试夹具习惯

备份包、图片头字节、磁力链接、剪贴实体和时间戳都应使用短小合成数据。测试 fixture 不使用真实剪贴内容、真实 URL、真实 WebDAV 地址、本机授权路径或长 JSON，避免测试失败时泄露隐私或让失败信息难以阅读。

### 3. 引入 fake clock 和 fake dispatcher 思维

涉及时间排序、恢复时间归一化、防抖、重试、延迟、过期清理和协程状态流时，不依赖真实时间和真实调度器。后续新增同类逻辑时，优先把 clock、dispatcher 或时间来源设计成可注入依赖，保证测试可重复。

### 4. 优先测试失败契约

新增测试不只覆盖正常路径，更要覆盖边界和坏输入，例如短字节数组、非法 zip entry、损坏备份包、未知图片格式、URL 无后缀、重复恢复、空搜索词和未来时间戳。失败契约需要明确是返回 `null`、返回 sealed result、抛出特定异常还是降级到兜底值。

### 5. 不急着测试 Compose 细节

Compose 的样式、间距、颜色、动画和手势观感不作为第一阶段单元测试重点。优先测试 UI 背后的 state、formatter、mapper、筛选规则和事件归约；视觉和手势继续依赖 Preview、方案文档检查、人工验证或后续少量 Compose UI test。

### 6. 让新增测试反推职责拆分

如果某段逻辑很难测试，通常说明它混入了 `Context`、IO、系统服务、数据库和规则判断。不要为了覆盖率硬测入口类，先拆出纯协作者或可替换依赖，再测试协作者，让单元测试成为推动架构收敛的信号。

### 7. 保留未补测试原因

不要求每次改动都新增测试，但每次都必须有判断。最终说明应明确写出是否补测试、未补原因和风险，例如“本次仅调整文案/样式/薄导航，未新增单元测试；已运行对应编译或既有测试验证”。这样避免门禁变成只看有没有新增测试文件的形式主义。

### 8. 维护待补测试清单

当前阶段不要求一次性补齐所有测试，但应持续维护待补测试清单。后续修相关功能、重构协作者或补缺陷时，优先顺手补清单中同领域的测试，避免测试债长期只停留在口头约定。

初始待补测试清单：

- `ImageFormatSnifferTest`
- `ImageAnimationMetadataReaderTest`
- `ImageDownloadValidatorTest`
- `ImageDownloadFileNamesTest`
- `MagnetInfoHashNormalizerTest`
- `MagnetUriBuilderTest`
- `MagnetSearchHighlightFormatterTest`
- `BackupSnapshotRestorerTest`
- `BackupPackageIoTest` 协议兼容扩展用例
- `ClipDaoRangeQueryTest`
- `SearchViewModelTest`
- `BackupRestoreVmTest`

## 第二层测试规范

### 1. 给协议类测试保留固定 fixture

备份 JSONL、manifest、旧版本 zip、磁力 URI 等外部契约应逐步保留少量最小化 fixture 到 `src/test/resources`。固定 fixture 能保护协议兼容性，比每次手写对象更容易发现旧数据读取、字段缺失或序列化格式变化，但 fixture 必须是合成数据，禁止包含真实用户内容、真实账号、真实 URL、真实 WebDAV 地址或完整业务数据导出。

### 2. 补迁移和兼容性测试意识

涉及 Room schema、备份 `schemaVersion`、序列化字段、枚举 `code`/`type`、R8 keep 契约或外部协议字段时，不只测试当前版本输出，还要测试旧数据能否读取、新旧版本是否兼容、缺失可选字段是否能降级，以及非法字段是否按失败契约处理。

### 3. 避免过度 mock

优先使用 fake repository、fake dao、fake clock、fake dispatcher 和可控内存数据源。Mock 框架只在 fake 难以表达调用契约、交互顺序确实是被测目标，或引入 fake 的维护成本明显更高时再评估。默认不要为了验证一次调用而让测试绑定内部实现细节。

### 4. 控制测试粒度

一个测试尽量只验证一个行为。合法输入归一化、非法输入拒绝、边界输入兜底、排序优先级和失败异常类型应分开测试；测试名要能直接说明行为，失败时不需要读完整准备代码也能定位问题。

### 5. 建立 flaky test 规则

任何依赖真实时间、真实网络、真实线程调度、文件系统枚举顺序、系统 locale/timezone、外部服务响应或设备状态的测试，都视为潜在 flaky。能固定就固定，不能固定就不要放进 JVM 单元测试层，应改为 fake、`androidTest`、集成测试或人工验证。

### 6. 排序和过滤使用表格化测试

DAO 排序、搜索筛选、折叠/回收站范围隔离、置顶优先、恢复合并优先级等规则，应优先用小表格数据表达输入和期望顺序。测试数据只保留影响规则判断的字段，避免大段实体构造掩盖真正断言。

### 7. bug 修复绑定回归测试

后续凡是修复已经出现过的纯逻辑、数据协议、排序过滤、恢复合并、格式识别或失败契约问题，默认补一个回归测试。测试名应体现旧问题或用户可观察行为，例如 `normalizeKeepsPastBackupTimesWhenRemoteClockIsAhead`，确保同类问题不会在重构中再次出现。

### 8. 暂不追求覆盖率门槛

在 P1/P2 测试稳定之前，不设置硬性覆盖率百分比。过早追覆盖率容易产生低价值测试，增加维护成本。当前原则是测试优先保护契约、边界和历史 bug，不为覆盖率而覆盖率。

## 成熟阶段测试规范

### 1. 分层运行测试

本地日常改动优先运行受影响模块的测试和编译；提交前运行 `:app:testDebugUnitTest` 或 `:base:general:testDebugUnitTest` 等核心模块测试；发布前再叠加编译、必要的 `androidTest` 和 release/R8 验证。测试门禁要保证反馈足够快，避免每次小改都强制全量运行导致测试被绕过。

### 2. 给复杂实体做 test builder

`ClipCaptureEntity`、备份协议模型、磁力记录、下载记录等字段较多的对象，可以逐步建立测试专用 builder 或 fixture factory。测试只显式设置与当前行为相关的字段，其他字段使用安全默认值，避免每个测试都堆大量无关参数。

### 3. parser/sniffer 使用参数化测试

图片头识别、infoHash 校验、URL 规范化、文件名清理、MIME/扩展名映射等输入输出表清晰的逻辑，后续可考虑使用 JUnit 参数化测试或本地表格循环断言。参数化只适合简单纯函数，不用于状态机或多步骤副作用流程。

### 4. 测试也要评审可读性

测试代码和生产代码一样需要可读性。看不懂、断言过宽、准备数据过重、命名含糊或把多个行为揉在一起的测试，都应在评审中要求收敛。测试失败时应能快速看出保护的业务契约，而不是先读一大段无关 setup。

### 5. 避免测试私有实现细节

优先测试公开契约和可观察结果，不为了当前内部调用了某个 helper 就断言调用顺序或私有实现。只有调用顺序、幂等次数、重试次数或副作用边界本身是业务契约时，才测试交互细节。

### 6. 给慢测试贴标签或隔离

Room、Robolectric、迁移、文件包 fixture、大量 zip/JSONL 和集成型测试可能比普通 JVM 单测慢。后续如果这类测试增多，应和快速 JVM 单测区分，必要时通过目录、命名、Gradle task 或标签隔离，避免拖慢日常反馈。

### 7. 测试命名体现业务意图

测试名应描述被保护的业务行为，而不是只写技术现象。例如 `validateRejectsUnsafeZipEntryPath` 比 `invalidInputThrows` 更好，`restoreKeepsPastBackupTimesWhenRemoteClockIsAhead` 比 `timeTest` 更好。命名应帮助后续维护者从失败报告直接理解风险。

### 8. 周期性清理失效测试

需求变化、协议废弃或行为明确调整后，测试也要同步更新或删除。禁止让旧测试继续保护已经废弃的行为；如果删除测试是因为行为废弃，应在对应方案文档或变更记录中说明新行为以哪个测试或验证方式覆盖。

## 测试工程化规范

### 1. 统一测试工具包

后续可以在各模块 `src/test` 下沉淀测试专用 helper，例如 fake clock、fake dispatcher、fixture factory、临时文件工具和断言扩展。测试工具只能放在测试源码集或测试资源中，不能进入生产代码，也不能让生产代码为了测试 helper 反向依赖测试目录。

### 2. 统一 fake 命名和位置

测试 fake 应使用清晰命名，例如 `FakeClipRepository`、`FakeBackupRepository`、`FakeClock`、`FakeImagePublisher`。放置位置优先选择对应测试包下的 `testing`、`fake` 或同等测试专用目录，避免每个测试类里临时复制一份相似 fake。

### 3. 给 sealed result 和 failure 建断言 helper

下载校验、备份恢复、图片过滤、媒体重新定位等返回 sealed result 或 failure reason 的逻辑，可以逐步建立测试断言 helper，例如 `assertFailureReason(...)`、`assertRecoverableFailure(...)`、`assertSkippedBecause(...)`。断言 helper 只表达稳定测试契约，不暴露生产私有实现细节。

### 4. CI 分阶段接入

CI 应先接入最快的 JVM 单元测试，稳定后再加入 Room/迁移测试，最后再考虑 UI test、R8 或更重的集成验证。CI 测试分层应与本地验证策略一致，避免一开始就把反馈链路拖得过慢。

### 5. PR/提交检查清单

后续 PR 模板或提交说明可以固定包含一行测试检查，例如：`单元测试检查：新增/更新/不需要，原因：...，已运行：...`。即使当前没有正式 PR 模板，最终回复和提交正文也应保持同样信息结构。

### 6. 测试数据隐私扫描

如果后续 `src/test/resources` 或测试 fixture 增多，应先通过 review 约束，必要时再补简单自动化扫描，检查是否出现真实 WebDAV 地址、Token、Cookie、完整 URL 查询串、真实剪贴内容、本机授权 URI 或完整备份内容。

### 7. 测试失败信息要可行动

断言失败消息应说明哪个协议字段、排序规则、reasonCode、状态分支或失败契约不符合预期，避免只得到 `expected true` 这类低信息量结果。测试名、断言消息和 fixture 命名应共同帮助维护者快速定位。

### 8. 用失败案例反推测试缺口

每次线上、手动验证或用户反馈暴露 bug 后，都应追问这个问题能否用单元测试防住。能防住就补回归测试；不能防住就记录为什么必须依赖集成测试、人工验证、系统能力验证或日志诊断。

## 测试维护收敛规范

### 1. 记录测试存在原因

历史 bug 回归测试、协议兼容测试、迁移测试和安全边界测试应在测试名或一行注释中说明保护的风险，例如保护旧备份可读、阻止 zip slip、保留远端时钟偏快时的排序语义。注释只写维护者需要知道的风险，不重复描述代码做了什么。

### 2. 避免测试工具膨胀

fake、builder、fixture factory 和 assert helper 应保持小而清楚，只服务稳定测试契约。不要把测试工具扩展成另一套复杂框架，也不要为了复用测试 setup 把不同业务语义强行合并到万能 helper 中。

### 3. 定期审查测试债清单

`docs/unit_test_plan.md` 中的待补测试清单需要定期审查。已经被真实测试覆盖、需求废弃或风险降低的条目应移除；新出现的高风险协议、排序、解析、恢复或状态机问题应补入清单，避免测试债长期失真。

### 4. 核心协议保留反向兼容样本

备份 `schemaVersion`、旧字段缺失、旧枚举 `code`/`type`、旧 manifest、旧 JSONL 和磁力 URI 等核心协议，应保留少量合成反向兼容样本。样本目标是验证旧数据仍可读、可降级或按明确失败契约拒绝，不追求覆盖所有历史数据组合。

### 5. 失败测试优先修复

单元测试失败或 flaky 时，优先修复测试稳定性、测试 fixture 或生产代码问题，不要默认跳过或禁用。确实需要临时跳过时，必须记录跳过原因、影响范围、恢复条件和后续清理位置，避免跳过长期沉淀。

## 测试数据与命名约定

- 测试 fixture 使用短小的合成数据，不复制真实剪贴内容、真实 WebDAV 地址、真实 URL 查询串、Cookie、Token、密码或本机文件路径。
- 测试方法优先表达行为，例如 `normalizeRejectsInvalidInfoHash`、`validateRejectsUnsafeZipEntryPath`。
- 临时文件必须放在测试临时目录中，测试结束后清理；zip slip、非法路径和损坏文件应显式覆盖。
- 时间相关测试必须使用固定时间戳、fake clock 或明确时区，避免依赖当前日期。
- 随机 id、taskId、traceId 如无必要不使用真实随机；需要时固定种子或固定字符串。

## 涉及文件

计划文档：

- `docs/unit_test_plan.md`
- `AGENTS.md`
- `.codex/skills/repo-unit-test-gate/SKILL.md`
- `.codex/skills/repo-coding-gate/SKILL.md`

优先新增测试目录：

- `base/general/src/test/java/com/cla/clip/base/general/backup`
- `base/general/src/test/java/com/cla/clip/base/general/magnet`
- `app/src/test/java/com/cla/clip/master/image/format`
- `app/src/test/java/com/cla/clip/master/image/download`

可能调整的配置文件：

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `base/general/build.gradle.kts`

只有当进入 ViewModel、Flow 或 Room 测试阶段确实需要新依赖时，才更新 Gradle 配置。

## 验证命令

文档或测试计划调整：

- `git diff --check`

新增纯 JVM 测试：

- `./gradlew :base:general:testDebugUnitTest`
- `./gradlew :app:testDebugUnitTest`

涉及 Kotlin 生产代码改动：

- `./gradlew :base:general:compileDebugKotlin`
- `./gradlew :app:compileDebugKotlin`

涉及 Room schema、迁移、序列化协议或 release 混淆契约：

- 先运行受影响模块的 `testDebugUnitTest` 和 `compileDebugKotlin`。
- 如涉及 `@Keep`、序列化字段、枚举协议、导航参数或 R8 规则，再运行 `./gradlew :app:minifyReleaseWithR8`。

## 日志与诊断计划

单元测试建设本身不新增运行时日志。测试失败应通过断言名称、失败消息、固定 fixture 和 Gradle 测试报告定位，不在生产代码里为了测试额外打印日志。

后续如果测试覆盖的是已有日志脱敏行为，只断言低敏结构化字段，例如数量、reasonCode、状态码、布尔值、耗时是否存在；禁止在测试 fixture 或失败消息中输出真实剪贴内容、完整搜索词、完整 magnet URI、完整 URL 查询串、WebDAV 地址、密码、Cookie、Token、本地授权 URI、完整 JSON/HTML 或备份内容。

如果某个新测试暴露生产代码缺少关键诊断日志，应回到对应功能方案文档补充日志与诊断计划，再按功能改动单独实现，避免把日志变更混进纯测试补齐任务。

## 已知取舍

- 第一阶段继续沿用 JUnit4，避免在测试基线尚未成型时切换 Kotest 或引入复杂断言框架。
- 暂不默认引入 MockK、Robolectric、Compose UI test 扩展依赖；这些依赖按场景需要再加。
- 暂不追求覆盖率数字门槛，优先覆盖高风险规则和失败契约；待测试体系稳定后再考虑模块级覆盖率目标。
- DAO/Room 和 Worker 端到端测试放到第二阶段，先通过协作者单测降低整体成本。

## 开放问题

- 是否需要把测试依赖集中沉淀为 Gradle convention，避免多模块重复声明。
- 是否需要删除各模块默认生成的 `ExampleUnitTest`，还是在新增真实业务测试时逐步替换。
- DAO 测试优先采用 `androidTest` 还是引入 Robolectric，需要在第一批 Room 测试前确认。
- ViewModel 测试是否引入 Turbine，取决于第一批状态流测试的复杂度。
- 是否需要在 CI 或本地提交前固定运行 `:base:general:testDebugUnitTest` 和 `:app:testDebugUnitTest`。

## 变更记录

- 2026-05-24：补充“可从 UI/导航问题中抽出的纯规则、状态机、mapper、formatter、predicate 或派生状态判断必须先写失败测试”的选择性 TDD 细则，并同步更新 `AGENTS.md` 与 `$repo-unit-test-gate`；原因是避免把可单测的状态派生规则误归类为薄导航接线。
- 2026-05-24：补充选择性 TDD 规则，并同步要求更新 `AGENTS.md` 与 `$repo-unit-test-gate`；原因是明确高风险逻辑和 bug 修复默认测试先行，纯 UI、文案、薄导航和已覆盖等价重构不机械强制 TDD，但必须记录原因、风险和验证方式。
- 2026-05-23：补充测试维护收敛规范，并同步要求更新 `AGENTS.md` 与 `$repo-unit-test-gate`；原因是将测试存在原因、测试工具复杂度控制、测试债清单审查、核心协议反向兼容样本和失败测试优先修复纳入后续维护标准。
- 2026-05-23：补充测试工程化规范，并同步要求更新 `AGENTS.md` 与 `$repo-unit-test-gate`；原因是将测试工具包、fake 命名位置、sealed result/failure 断言 helper、CI 分阶段、PR/提交检查清单、测试数据隐私扫描、可行动失败信息和失败案例反推测试缺口纳入后续测试工程维护标准。
- 2026-05-23：补充成熟阶段测试规范，并同步要求更新 `AGENTS.md` 与 `$repo-unit-test-gate`；原因是将分层运行、test builder、参数化测试、测试可读性、避免私有实现断言、慢测试隔离、业务化命名和失效测试清理纳入后续测试体系演进标准。
- 2026-05-23：补充第二层测试规范，并同步要求更新 `AGENTS.md` 与 `$repo-unit-test-gate`；原因是将协议 fixture、迁移兼容、避免过度 mock、测试粒度、flaky 规则、表格化排序过滤测试、bug 回归测试和覆盖率取舍固化为后续测试维护标准。
- 2026-05-23：同步将单元测试长期维护建议补充到 `AGENTS.md` 和 `$repo-unit-test-gate`；原因是后续代码生成与修改时需要在硬规则和 skill 执行流程中直接看到同一套判断标准。
- 2026-05-23：补充单元测试长期维护建议和初始待补测试清单；原因是将测试价值优先级、fixture 习惯、fake time/dispatcher、失败契约、Compose 测试边界、职责拆分、未补测试说明和测试债管理固定到文档。
- 2026-05-23：将状态更新为已确认，并把“新增或修改代码必须经过 `$repo-unit-test-gate` 判断是否需要单元测试”的要求纳入本计划；原因是需要把测试检查从建议提升为后续代码修改门禁。
- 2026-05-23：新增单元测试建设计划草案；原因是需要在正式补测试前统一测试分层、优先级、依赖引入时机、验证命令和隐私日志边界。
