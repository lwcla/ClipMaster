状态：已完成

# 来源 App 显示名共享方案

## 独立来源展示订阅接入（已确认、待实施，2026-10-02）

来源名称将与图标/主色一起按包名独立订阅，不再从分页 `ClipShowEntity.appName` 快照读取；候选名称使用轻量投影并等值去重。继续复用 `SourceAppData.displayName()` 和 `String?.toSourceAppDisplayName()` 的未知/空白规则，不复制格式化工具；共享卡片标题高亮读取独立状态中的名称。完整订阅和查询边界以 [来源图标主方案](source_app_icon_plan.md) 为准。当前实现仍为下方基线，本轮只改方案，产品待实施。

## 当前状态

来源 App 显示名已抽成共享扩展方法。搜索页来源筛选等持有 `SourceAppData` 的场景调用 `SourceAppData.displayName()`；剪贴结果列表等只持有拍平后 `String? appName` 的场景调用 `String?.toSourceAppDisplayName()`，统一处理空名称兜底。

## 目标

- 统一来源 App 用户可见名称的展示规则。
- 当来源 App 名称为 `null`、空串或只有空白字符时，统一显示资源化“未知”文案。
- 让包名、图标、主色等其它来源信息继续由各使用场景自行展示，不把筛选 UI 或列表 item 的布局规则混入共享方法。

## 范围

- 新增 Compose 入口 `SourceAppData.displayName()`。
- 新增 Compose 入口 `String?.toSourceAppDisplayName()`。
- 保留非 Compose 重载 `SourceAppData.displayName(context)` 和 `String?.toSourceAppDisplayName(context)`，供后台任务、普通工具函数或 ViewModel 等没有 Composition 的场景使用。
- 搜索页来源选择列表和单选来源选择器接入共享方法。
- 剪贴结果列表 item 来源 App 名称接入共享方法。

不包含：

- 修改来源 App 数据库存储结构。
- 修改来源 App 图标、主色、包名副标题或搜索匹配逻辑。

## 用户体验

- 来源 App 名称存在时，显示去除首尾空白后的真实名称。
- 来源 App 名称缺失时，显示“未知”。
- 搜索页来源下拉只显示图标与名称，不展示包名副标题；包名仍作为稳定选择身份，最新图标链路以 [来源图标方案](source_app_icon_plan.md) 为准。
- 如果已选来源包名不在当前候选列表里，搜索页来源选择器继续回退显示包名，避免隐藏已有筛选条件。

## 数据流

- 数据层继续保存原始 `SourceAppData.appName` 和 `ClipShowEntity.appName`。
- Compose UI 层只在来源实体或来源名称上调用扩展方法，扩展内部通过 `LocalContext.current` 读取当前上下文。
- 共享工具负责名称规整，并在名称缺失时从 `base_general_unknow` 字符串资源读取“未知”兜底，避免调用方重复传入 `Context` 或同一文案，也避免在 Kotlin 代码中硬编码用户可见中文。

## 涉及文件

- `base/general/src/main/java/com/cla/clip/base/general/utils/SourceAppDisplayUtils.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/clip/ClipResultList.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/search/SearchPage.kt`
- `docs/clip_result_list_plan.md`
- `docs/search_page_plan.md`
- `docs/source_app_display_plan.md`

## 实现步骤

1. 在 base general 工具层新增来源 App 显示名共享方法。
2. 搜索页来源选择和来源选择器改为调用共享方法。
3. 剪贴结果列表 item 改为调用共享方法。
4. 更新共享能力、搜索页和剪贴结果列表文档。
5. 运行 `./gradlew :app:compileDebugKotlin` 验证。

## 测试验证

- 已运行 `./gradlew :app:compileDebugKotlin`，结果通过。
- 来源 App 名称为空时，搜索页来源选择列表显示“未知”，包名仅作稳定身份，不作为副标题展示。
- 来源 App 名称为空时，搜索页单选来源选择器显示“未知”。
- 剪贴列表 item 来源 App 名称为空时，显示“未知”。

## 已知取舍

- Compose 入口不要求调用方传入 `Context`，扩展方法内部通过 `LocalContext.current` 读取默认未知文案；非 Compose 入口保留 `Context` 参数，避免后台或 ViewModel 场景误用 Composable 方法。
- 搜索页对已经不在候选列表中的已选包名继续显示包名；这是为了避免用户看不到仍然生效的筛选条件。

## 开放问题

- 暂无。后续如果详情页、通知、导出记录等场景也展示来源 App 名称，应直接接入 `SourceAppData.displayName()` 或 `String?.toSourceAppDisplayName()`。

## 变更记录

- 2026-10-02：同步已确认的独立来源名称/图标/主色订阅接入，保留既有显示名工具和未知兜底，将分页名称快照迁移列为待实施。

- 2026-10-02：同步搜索来源菜单仅显示图标与名称的现行接入，删除过期包名副标题说明，并引用图标主方案；名称兜底规则不变。

- 2026-05-31：同步搜索页来源入口命名为来源选择器；原因是搜索页筛选区已从 Chip 形态改为等宽选择器，文档需要避免保留过期 UI 形态描述。
- 2026-05-18：新增来源 App 显示名共享方案并标记为已完成；原因是来源 App 名称会在搜索筛选和剪贴 item 等多个位置展示，需要统一空名称兜底规则。
- 2026-05-18：将共享方法从外部传入未知文案改为传入 `Context` 后内部读取字符串资源；原因是“未知”是当前统一默认兜底，调用方无需重复传入同一文案。
- 2026-05-18：新增 Compose 显示名入口并让搜索页、剪贴 item 直接调用无 `Context` 版本；原因是来源 App 名称主要在 Compose UI 中展示，调用方不应重复传入当前上下文。
- 2026-05-18：将 `SourceAppData` 场景改为扩展方法 `displayName()`，并为拍平后的可空名称保留 `toSourceAppDisplayName()`；原因是来源实体自身承载展示名语义，扩展方法比工具对象调用更贴近业务模型。
