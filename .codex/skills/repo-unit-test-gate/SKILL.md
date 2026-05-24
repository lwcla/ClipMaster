---
name: repo-unit-test-gate
description: 当前仓库的单元测试门禁工作流。用于任何新增、修改、修复、重构或生成 Kotlin/Android/Compose 代码前，判断是否必须新增或更新单元测试、应运行哪些 testDebugUnitTest/编译/R8 验证，以及未补测试时如何记录原因和风险。
---

# 仓库单元测试门禁

本 skill 与 `$repo-coding-gate` 配合使用。它不要求每一行代码都新增测试，但要求每次代码改动前都做测试必要性判断，并在最终回复中报告判断结果。

本仓库采用选择性 TDD：高风险规则、纯逻辑、数据协议、状态机和 bug 修复默认先写能失败的单元测试或回归测试，再实现生产代码；纯 UI 样式、文案、薄导航接线或已被既有测试覆盖的等价重构不强制测试先行，但必须说明未走 TDD 的原因、风险和验证方式。

## 长期判断原则

1. 优先保护高风险规则，不追求第一阶段覆盖率数字。当前测试价值优先级为：备份恢复、图片下载与格式识别、磁力规范化、DAO 查询排序隔离、ViewModel 状态机。
2. 对高价值改动优先测试先行。新增或修改纯逻辑、协议、排序过滤、失败契约、ViewModel 状态流或修复历史 bug 时，默认先写能表达目标行为的失败测试，再实现或修复生产代码；如果受系统能力限制无法测试先行，必须记录原因和替代验证方式。
3. 使用短小合成 fixture。备份包、图片头字节、磁力链接、剪贴实体和时间戳都用构造数据，禁止使用真实剪贴内容、真实 URL、真实 WebDAV 地址、本机授权路径或长 JSON。
4. 涉及时间排序、恢复时间归一化、防抖、重试、延迟、过期清理和协程状态流时，优先要求生产代码暴露可注入的 clock、dispatcher 或时间来源。
5. 优先测试失败契约。短字节数组、非法 zip entry、损坏备份包、未知图片格式、URL 无后缀、重复恢复、空搜索词和未来时间戳等坏输入，要比理想正常路径更优先。
6. 不急着测试 Compose 细节。样式、间距、颜色、动画和手势观感主要靠 Preview、方案文档检查、人工验证或少量 UI test；单元测试优先覆盖 state、formatter、mapper、筛选规则和事件归约。
7. 让测试反推职责拆分。如果逻辑难以单测，先判断是否混入 `Context`、IO、系统服务、数据库和规则判断；优先拆出纯协作者或可替换依赖，不为了覆盖率硬测入口类。
8. 保留未补测试原因。允许纯 UI、文案、薄导航或已覆盖的等价重构不新增测试，也不强制 TDD，但必须说明原因、风险和已运行验证。
9. 持续维护测试债。参考 `docs/unit_test_plan.md` 的待补测试清单，后续修相关功能、重构协作者或补缺陷时优先顺手补同领域测试。

## 第二层测试规范

1. 协议类测试优先保留最小化固定 fixture。备份 JSONL、manifest、旧版本 zip、磁力 URI 等外部契约可放到 `src/test/resources`，但必须使用合成数据，禁止真实用户内容和真实地址。
2. 涉及 Room schema、备份 `schemaVersion`、序列化字段、枚举 `code`/`type`、R8 keep 或外部协议字段时，必须评估迁移和兼容性测试，不只测试当前版本输出。
3. 避免过度 mock。优先 fake repository、fake dao、fake clock、fake dispatcher 和可控内存数据源；只有交互顺序本身是目标或 fake 成本过高时才考虑 mock 框架。
4. 控制测试粒度。一个测试尽量只验证一个行为，合法归一化、非法拒绝、边界兜底、排序优先级和失败类型分开写。
5. 建立 flaky test 警觉。依赖真实时间、真实网络、真实线程调度、文件枚举顺序、系统 locale/timezone、外部服务或设备状态的测试，不应放进 JVM 单元测试层，除非已固定来源。
6. 排序和过滤优先表格化。DAO 排序、搜索筛选、折叠/回收站范围隔离、置顶优先和恢复合并优先级，用小表格数据表达输入和期望顺序。
7. bug 修复绑定回归测试。修复纯逻辑、数据协议、排序过滤、恢复合并、格式识别或失败契约问题时，默认补一个能复现旧问题的测试。
8. 暂不追求覆盖率门槛。P1/P2 稳定前不为覆盖率而写低价值测试，优先保护契约、边界和历史 bug。

## 成熟阶段测试规范

1. 分层运行测试。本地改动跑受影响模块；提交前跑核心模块 `testDebugUnitTest`；发布前叠加编译、必要的 `androidTest` 和 release/R8，避免小改强制全量导致测试被绕过。
2. 复杂实体使用 test builder。字段多的剪贴实体、备份模型、磁力记录和下载记录，优先用测试专用 builder 或 fixture factory，只显式设置与行为相关的字段。
3. parser/sniffer 可用参数化测试。图片头识别、infoHash 校验、URL 规范化、文件名清理和 MIME/扩展名映射等输入输出表清晰的纯函数，可用参数化或表格循环断言。
4. 测试也要评审可读性。测试命名、setup 和断言必须清楚，避免断言过宽、准备数据过重或一个测试揉多个行为。
5. 避免测试私有实现细节。优先测公开契约和可观察结果；只有调用顺序、重试次数、幂等次数或副作用边界本身是业务契约时，才测交互细节。
6. 慢测试需要隔离。Room、Robolectric、迁移、文件包 fixture、大型 zip/JSONL 和集成型测试增多时，应通过目录、命名、Gradle task 或标签区分快速 JVM 单测。
7. 测试命名体现业务意图。使用 `validateRejectsUnsafeZipEntryPath`、`restoreKeepsPastBackupTimesWhenRemoteClockIsAhead` 这类能说明行为风险的名称，避免 `timeTest`、`invalidInputThrows` 这类含糊名称。
8. 周期性清理失效测试。需求变化、协议废弃或行为调整后，测试要同步更新或删除；删除旧测试时在方案文档或变更记录中说明新行为的覆盖方式。

## 测试工程化规范

1. 统一测试工具包。fake clock、fake dispatcher、fixture factory、临时文件工具和断言扩展只放在 `src/test` 或测试资源中，不能进入生产代码。
2. 统一 fake 命名和位置。使用 `FakeClipRepository`、`FakeBackupRepository`、`FakeClock` 等清晰命名，放在对应测试包下的 `testing`、`fake` 或同等测试专用目录。
3. 为 sealed result 和 failure 建断言 helper。下载校验、备份恢复、图片过滤等逻辑可用 `assertFailureReason(...)`、`assertRecoverableFailure(...)` 等 helper 提升可读性，但不得暴露生产私有实现。
4. CI 分阶段接入。先跑最快 JVM 单测，稳定后加入 Room/迁移测试，最后再考虑 UI test、R8 或更重集成验证，避免反馈链路过慢。
5. PR/提交检查清单保留测试结论。提交说明或最终回复应包含“单元测试检查：新增/更新/不需要，原因：...，已运行：...”这类结构。
6. 测试数据隐私扫描。`src/test/resources` 和 fixture 增多后，先靠 review，必要时自动扫描真实 WebDAV 地址、Token、Cookie、完整 URL 查询串、真实剪贴内容、本机授权 URI 和完整备份内容。
7. 测试失败信息要可行动。断言失败消息应指出协议字段、排序规则、reasonCode、状态分支或失败契约，而不是只留下低信息量的 `expected true`。
8. 用失败案例反推测试缺口。线上、手动验证或用户反馈出现 bug 后，判断能否用单元测试防住；能则补回归测试，不能则记录为何依赖集成/人工/系统验证或日志诊断。

## 测试维护收敛规范

1. 记录测试存在原因。历史 bug 回归、协议兼容、迁移和安全边界测试，应在测试名或一行注释中说明保护的风险。
2. 避免测试工具膨胀。fake、builder、fixture factory 和 assert helper 要小而清楚，只服务稳定测试契约，不发展成万能测试框架。
3. 定期审查测试债清单。移除已覆盖、已废弃或风险降低的条目，补入新出现的高风险协议、排序、解析、恢复或状态机问题。
4. 核心协议保留反向兼容样本。备份 `schemaVersion`、旧字段缺失、旧枚举 `code`/`type`、旧 manifest、旧 JSONL 和磁力 URI 等，用少量合成样本验证旧数据可读、可降级或按契约拒绝。
5. 失败测试优先修复。测试失败或 flaky 时优先修稳定性、fixture 或生产代码；确需临时跳过时，记录原因、影响范围、恢复条件和后续清理位置。

## 执行顺序

1. 先确认本次改动类型：
   - 新增或修改纯逻辑、解析、格式识别、排序过滤、mapper、formatter、parser、validator、comparator、builder、sniffer、reader；
   - 新增或修改 DAO、Room 查询、迁移、唯一索引、分页、范围隔离；
   - 新增或修改 ViewModel 状态流、一次性事件、状态归约、重试/取消/降级分支；
   - 新增或修改备份协议、序列化字段、manifest/checksum、恢复报告、R8/keep 稳定契约；
   - 新增或修改 Worker 下沉协作者、失败契约、资源清理、重试或保留策略；
   - 仅调整 UI 样式、文案、图标、Preview、薄导航接线或无行为变化的等价重构。
2. 按“默认需要测试”的规则判断是否新增或更新单元测试，并进一步判断是否需要选择性 TDD。
3. 如果属于高风险纯逻辑、协议、排序过滤、失败契约、ViewModel 状态流或 bug 修复，优先写失败测试或回归测试；测试应表达公开契约和目标行为，避免为了测试先行绑定私有实现细节。
4. 如果需要测试，优先选择最轻的测试层：
   - 纯 Kotlin 规则优先放到对应模块 `src/test/java`；
   - Flow 或 ViewModel 状态测试再考虑 `kotlinx-coroutines-test` 和 Turbine；
   - DAO/Room 查询测试再考虑 `androidTest`、`room-testing` 或后续 Robolectric；
   - Worker 本体少测，优先测可替换协作者。
5. 如果判断不新增测试或不走测试先行，必须记录原因：
   - 纯 UI 样式或文案，不改变行为；
   - 很薄的导航接线，核心逻辑已被调用方或被调方覆盖；
   - 既有测试已覆盖本次等价重构；
   - 依赖真实 Android runtime、WebView、MediaStore、Shizuku、权限或外部网络，短期更适合人工验证、`androidTest` 或后续集成测试。
6. 确定验证命令：
   - 文档或规则改动至少运行 `git diff --check`；
   - 纯 JVM 测试改动运行受影响模块的 `testDebugUnitTest`；
   - Kotlin/Compose/Android 生产代码改动运行受影响模块的 `compileDebugKotlin`；
   - 涉及序列化、Room schema、导航参数、Intent/通知/WebView JS 协议、keep/R8 规则时，评估是否运行 `./gradlew :app:minifyReleaseWithR8`。
7. 在最终回复中报告：
   - 是否新增或更新测试；
   - 是否采用测试先行；如未采用，说明原因和风险；
   - 未补测试的原因和风险；
   - 已运行的测试/编译/R8 命令；
   - 如果验证无法运行，说明原因、风险和后续补验方向。

## 默认需要新增或更新测试

出现以下改动时，默认必须补充或更新单元测试，除非有明确例外原因：

- 字节解析、URL 规范化、文件名清理、候选去重、格式识别、排序过滤和失败契约。
- mapper、formatter、parser、validator、comparator、builder、sniffer、reader 等可独立测试的规则类。
- 备份包 IO、manifest/checksum、schemaVersion、dataFormat、恢复合并、恢复报告和幂等规则。
- 磁力 infoHash、magnet URI、搜索词规范化、高亮、FTS 查询格式化和合法性校验。
- 图片格式识别、动图元数据读取、下载校验、文件名生成、请求头构造和发布前判断。
- DAO 查询范围隔离、排序、分页、唯一索引和迁移。
- ViewModel 状态机、一次性事件、输入防抖、重试、取消、降级和错误分支。
- Worker 中已下沉为协作者的业务规则、资源清理、保留策略和可恢复/不可恢复失败判断。

## 可以不新增测试的常见情况

以下情况可以不新增测试，但仍要运行既有验证并说明原因：

- 仅修改 Compose 样式、间距、颜色、图标、Preview 或简单文案。
- 仅新增字符串资源、无行为变化的页面标题或提示文案。
- 很薄的导航入口、路由参数透传或按钮接线，且核心逻辑在目标页面或协作者已有覆盖。
- 不改变行为的文件拆分、重命名或等价重构，且已有测试覆盖核心规则。
- 当前逻辑依赖真实系统能力，无法用 JVM 单元测试可靠覆盖；此时需要说明是否改用 `androidTest`、人工验证或后续集成测试。

## 推荐命令

- `./gradlew :base:general:testDebugUnitTest`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :base:general:compileDebugKotlin`
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:minifyReleaseWithR8`
- `git diff --check`

只运行与改动最贴近的命令，不为了形式跑无关模块。无法运行时，最终回复必须写明原因和风险。

## 文档要求

- 单元测试策略主文档是 `docs/unit_test_plan.md`。
- 如果新增测试依赖、改变测试分层策略、调整必测范围或新增长期例外，必须同步更新 `docs/unit_test_plan.md`。
- 如果某个功能方案已经列出测试验证点，新增或修改测试时优先更新对应功能方案文档的“测试验证”或“变更记录”。
- 如果不补测试是临时例外，必须在方案文档“后续待办/开放问题”或最终说明中记录后续收敛方向。

## 隐私与日志边界

- 测试 fixture 禁止包含真实剪贴内容、完整搜索词、完整 magnet URI、完整 URL 查询串、WebDAV 地址、密码、Cookie、Token、本地授权 URI、完整 JSON/HTML 或备份内容。
- 单元测试建设本身不新增运行时日志；测试失败应通过断言、fixture 和 Gradle 测试报告定位。
- 如果测试暴露生产代码缺少关键诊断日志，先更新对应方案文档的日志与诊断计划，再按功能改动单独实现。
