状态：已完成

# 方案文档 HTML 流程图规范

## 当前状态

仓库中的需求和方案文档主要集中在 `docs/`，其中 `webdav_backup_plan.md`、`magnet_search_plan.md`、`image_extract_plan.md` 和 `unit_test_plan.md` 等文档已经较长。纯文字适合记录完整设计、取舍和变更历史，但不利于快速查看主流程、关键状态和测试覆盖缺口。

本轮先完成 WebDAV + 本地自动备份恢复方案的单文档流程图试点。Markdown 仍是事实来源，HTML 只是由脚本生成的查看产物。

## 目标

- 为长方案文档提供可离线打开的 HTML 流程图。
- 用简体中文 HTML 文件名提升人工查找效率。
- 在流程节点中标记 `已单测`、`待补测`、`人工验证`、`编译验证`，让测试覆盖状态一眼可见。
- 通过 `--check` 防止生成 HTML 与源 Markdown 漂移。

## 范围

- 首批只覆盖 `docs/webdav_backup_plan.md`。
- 只生成单文档流程图，不生成全局文档总览图。
- 不从自然语言自动猜业务流程，避免误画恢复、备份、权限和回滚边界。
- 不改变 Android、Kotlin、Compose、Room、导航或运行时代码。

## 用户体验

- 生成文件统一放在 `docs/flows/`。
- Mermaid 离线资源统一放在 `docs/flows/assets/`。
- HTML 文件使用简体中文命名，例如 `WebDAV本地自动备份恢复流程.html`。
- HTML 顶部固定展示源文档路径、生成文件提示和测试覆盖图例。
- 颜色只做辅助，节点和图例必须保留文字标签，避免只靠颜色判断测试状态。

## 数据流

1. 维护者在源 Markdown 中添加 `<!-- flow-title: 简体中文流程名 -->`。
2. 维护者在 `## 流程图` 章节添加一个或多个 Mermaid 代码块；图块第一行可用 `%% title: 备份流程` 指定 HTML 小标题。
3. `tools/render_doc_flow_html.py` 读取源文档，优先使用 `flow-title` 生成中文文件名。
4. 脚本提取 Mermaid 代码块，生成引用本地 Mermaid 资源的 HTML。
5. `--check` 模式重新渲染内存中的 HTML，并与已生成文件逐字比较；不同步时返回失败。

## 流程图约定

- Mermaid 是流程图事实来源，HTML 是生成产物，禁止手改 HTML。
- 多个 Mermaid 图块默认作为同一文档的多个内容块渲染；建议用 `%% title: ...` 给每个图块写清中文标题。若某个子流程是独立页面、独立 ViewModel 或独立 Worker，优先拆成单独内容块，只在相关内容块中保留入口和事件回显边界。
- 流程图默认使用自上而下方向 `flowchart TD`，优先降低横向宽度和缩放压力；只有流程天然强调左右对照或泳道关系时，才考虑 `flowchart LR`。
- 备份、恢复、本地媒体关联、下载、扫描、导入、导出、清理等独立业务阶段优先拆成不同图块，不放进同一张大图。
- 独立页面、独立 `ViewModel`、独立 `Worker`、独立状态机或独立事件流，优先单独成图；上游图只保留“入口”和“回显/结果”边界。
- 跨流程交互只画稳定边界，例如备份产物、列表条目、Route 参数、请求流、事件流、summary 回显；不要把下游内部细节塞进上游流程。
- 一张图如果线条过密、节点太多、需要缩放或横向滚动明显，应继续拆分；可读性优先于完整性，完整规则保留在正文。
- 每个图块只放入口、状态、关键副作用、失败/回滚边界和测试覆盖，不承载完整规则细节。
- `已单测` 只用于已有明确单元测试覆盖的节点。
- `待补测` 用于适合后续补单元测试但尚未覆盖的纯逻辑、解析、映射、恢复规则或失败契约。
- `人工验证` 用于依赖 UI、系统文件选择器、权限、MediaStore、WebDAV 真实服务或视觉检查的节点。
- `编译验证` 用于薄接线、导航、页面状态连接或已经通过编译保护但不适合单独单测的节点。
- 生成或调整 HTML 流程图时应使用 `$repo-doc-flow-html`，并继续遵守 `$repo-docs-logs`、`$repo-unit-test-gate` 和最终自检规则。

## 涉及文件

- `docs/doc_flow_diagram_plan.md`
- `docs/webdav_backup_plan.md`
- `docs/flows/WebDAV本地自动备份恢复流程.html`
- `docs/flows/assets/mermaid.min.js`
- `docs/flows/assets/mermaid.LICENSE.txt`
- `tools/render_doc_flow_html.py`
- `tools/test_render_doc_flow_html.py`
- `.codex/skills/repo-doc-flow-html/SKILL.md`
- `.codex/skills/repo-doc-flow-html/agents/openai.yaml`

## 实现步骤

1. 新增流程图规范文档。
2. 在 WebDAV 方案文档中新增 `flow-title` 和 `## 流程图`。
3. 新增 Python 渲染脚本和单元测试。
4. 下载固定版本 Mermaid 到本地 assets，确保 HTML 离线可打开。
5. 新增 `$repo-doc-flow-html` 项目 skill，沉淀生成和调整流程图的长期工作流。
6. 生成中文 HTML，并运行生成同步检查。

## 测试验证

- 运行 `python -m unittest tools.test_render_doc_flow_html`，覆盖 Mermaid 提取、多图块、图块标题、缺少图块报错、HTML 转义、`flow-title` 优先级、中文文件名、统一输出目录、离线 assets 引用和 `--check` 漂移检测。
- 运行 `python tools/render_doc_flow_html.py docs/webdav_backup_plan.md`，生成 WebDAV 试点 HTML。
- 运行 `python tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md`，确认生成文件与源文档同步。
- 运行 `git diff --check`，检查空白和冲突标记。
- 本次不运行 Android 编译，因为未修改 Kotlin、Compose、资源、Room、导航或运行时代码。

## 验证记录

- 2026-05-24：已运行 `python -m unittest tools.test_render_doc_flow_html`，结果通过；确认流程图渲染脚本的 Mermaid 提取、中文文件名、HTML 转义、离线资源检查和漂移检测可用。
- 2026-05-24：已运行 `python tools/render_doc_flow_html.py docs/webdav_backup_plan.md` 与 `python tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md`，结果通过；确认 WebDAV 试点 HTML 已生成并与源 Markdown 同步。
- 2026-05-24：已运行 `python -m unittest tools.test_render_doc_flow_html`，结果通过；确认多个 Mermaid 图块可通过 `%% title: ...` 在 HTML 中展示中文标题。
- 2026-05-24：已运行 `python tools/render_doc_flow_html.py docs/webdav_backup_plan.md` 与 `python tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md`，结果通过；确认 WebDAV 试点可拆成备份、恢复、本地媒体关联三个 HTML 内容块。
- 2026-05-24：已运行 `python tools/render_doc_flow_html.py docs/webdav_backup_plan.md` 与 `python tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md`，结果通过；确认 WebDAV 试点三个流程图已按自上而下方向重新生成。
- 2026-05-24：已运行 `python tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md`、`python -m unittest tools.test_render_doc_flow_html`、`git diff --check` 和 `$repo-doc-flow-html` frontmatter 静态检查，结果通过；确认新增 skill 后，现有生成器、WebDAV HTML 和 skill 元数据仍可用。

## 日志与诊断计划

本工具不新增运行时日志。脚本只在命令行输出低敏信息：生成文件路径、检查通过提示、缺少 Mermaid 代码块、缺少离线 Mermaid 资源或生成产物过期等错误原因。

脚本不得输出文档正文、剪贴内容、搜索词全文、URL 查询串、WebDAV 地址、密码、本地授权 URI、完整 JSON/HTML 或备份内容。单元测试使用合成 Markdown，不包含真实用户数据。

## 已知取舍

- 试点阶段不做全仓批量生成，避免把尚未人工校准的流程图误提交。
- Mermaid 资源以固定版本 `10.9.3` 提交到仓库，换取离线可读性和稳定渲染；后续升级需单独验证生成 HTML。
- `--check` 使用完整 HTML 文本比较，简单直接；未来如果模板频繁变化，再评估结构化哈希或索引清单。

## 开放问题

- 是否需要在试点稳定后新增全局 `docs/flows/index.html`。
- 是否需要为复杂文档支持多个命名流程图文件。
- 是否需要在提交前固定运行流程图 `--check`。

## 变更记录

- 2026-05-24：新增方案文档 HTML 流程图规范并完成 WebDAV 试点落地；原因是长需求文档纯文字阅读成本较高，需要通过可离线打开的中文 HTML 流程图展示主流程和测试覆盖状态。
- 2026-05-24：补充 Mermaid 图块标题约定；原因是单份方案文档可能拆成多个流程图内容块，需要在生成 HTML 时用中文标题区分备份流程、恢复流程等内容。
- 2026-05-24：补充独立子流程拆分建议；原因是 WebDAV 试点中本地媒体关联拥有独立页面、ViewModel 和事件回显链路，单独成图比放在恢复流程内更易阅读。
- 2026-05-24：补充流程图默认使用 `flowchart TD` 的约定；原因是左到右布局在屏幕宽度受限时容易压缩字体，影响方案文档 HTML 的可读性。
- 2026-05-24：补充方案文档 HTML 流程图生成规则，并新增 `$repo-doc-flow-html` 项目 skill；原因是后续生成或调整流程图时需要固定图块拆分、交互边界、默认方向、测试覆盖徽标、离线生成和同步检查工作流。
