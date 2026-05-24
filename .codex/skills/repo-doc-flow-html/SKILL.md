---
name: repo-doc-flow-html
description: 当前仓库的方案文档 HTML 流程图生成工作流。用于新增、更新、拆分或重新生成 docs 下方案文档的 Mermaid 流程图和 docs/flows 中文 HTML，尤其关注图块拆分、交互边界、测试覆盖徽标、离线资源、中文命名和生成产物同步检查。
---

# 仓库方案文档 HTML 流程图

当用户要求把 `docs/` 下方案文档生成或更新 HTML 流程图，或反馈流程图不清晰、过宽、内容块划分不合理时，配合 `$repo-docs-logs` 和 `$repo-coding-gate` 使用本 skill。

## 执行流程

1. 先阅读或检查 `docs/doc_flow_diagram_plan.md`，以其中规则为准。
2. 修改源 Markdown，而不是手改 `docs/flows/*.html`；HTML 只能由脚本生成。
3. 在源文档使用 `<!-- flow-title: 简体中文流程名 -->` 固定中文文件名。
4. 在 `## 流程图` 下维护一个或多个 Mermaid 代码块；每个图块第一行优先写 `%% title: 中文标题`。
5. 更新源文档的验证记录和变更记录，说明为什么调整图块结构。
6. 运行 `python tools/render_doc_flow_html.py <源文档>` 重新生成 HTML。
7. 运行 `python tools/render_doc_flow_html.py --check <源文档>` 检查生成产物同步。
8. 若修改了生成器脚本或标题解析等纯逻辑，运行 `python -m unittest tools.test_render_doc_flow_html`。
9. 运行 `git diff --check`。

## 图块拆分规则

- 默认拆成多个小图，而不是把整份方案塞进一张图。
- 备份、恢复、本地媒体关联、下载、扫描、导入、导出、清理等独立业务阶段优先单独成图。
- 独立页面、独立 `ViewModel`、独立 `Worker`、独立状态机或独立事件流，优先单独成图。
- 上游图只画下游入口和回显边界，不展开下游内部细节。
- 跨流程交互只画稳定边界，例如备份产物、列表条目、Route 参数、请求流、事件流、summary 回显。
- 一张图如果线条过密、需要缩放、横向滚动明显或节点文字变小，应继续拆分。

## Mermaid 规则

- 默认使用 `flowchart TD`，降低横向宽度和缩放压力。
- 只有流程天然强调左右对照、输入输出并列或泳道关系时，才使用 `flowchart LR`，并在变更记录说明原因。
- 节点文字使用简体中文，保留必要的类名、Route、ViewModel、Worker、Flow、reasonCode 等稳定标识。
- 流程节点只放入口、状态、关键副作用、失败/回滚边界和测试覆盖状态。
- 测试覆盖状态使用统一短标签：`已单测`、`待补测`、`人工验证`、`编译验证`。
- 颜色只能作为辅助，节点必须保留文字标签。

## 文件和命令

- 规则文档：`docs/doc_flow_diagram_plan.md`
- 生成脚本：`tools/render_doc_flow_html.py`
- 脚本测试：`tools/test_render_doc_flow_html.py`
- 输出目录：`docs/flows/`
- 离线资源：`docs/flows/assets/`

推荐命令：

```powershell
python tools/render_doc_flow_html.py docs/webdav_backup_plan.md
python tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md
python -m unittest tools.test_render_doc_flow_html
git diff --check
```

## 日志与隐私

本工作流不新增 App 运行时日志。脚本只输出低敏路径和错误原因，不输出文档正文、剪贴内容、搜索词全文、完整 URL、WebDAV 地址、密码、本地授权 URI、完整 JSON/HTML 或备份内容。
