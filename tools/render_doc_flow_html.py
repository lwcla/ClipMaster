#!/usr/bin/env python3
"""Render Markdown Mermaid flow diagrams into standalone HTML files.

The Markdown document remains the source of truth. Generated HTML is a
view-only artifact that references the local Mermaid runtime under
docs/flows/assets so it can be opened without network access.
"""

from __future__ import annotations

import argparse
import html
import re
import sys
from dataclasses import dataclass
from pathlib import Path


FLOW_TITLE_RE = re.compile(r"<!--\s*flow-title:\s*(.*?)\s*-->", re.IGNORECASE)
H1_RE = re.compile(r"^\s*#\s+(.+?)\s*$", re.MULTILINE)
MERMAID_BLOCK_RE = re.compile(
    r"```[ \t]*mermaid[^\n\r]*\r?\n(.*?)\r?\n```",
    re.IGNORECASE | re.DOTALL,
)
DIAGRAM_TITLE_RE = re.compile(r"^\s*%%\s*title:\s*(.*?)\s*$", re.IGNORECASE)
INVALID_FILE_CHARS_RE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
INFERRED_TITLE_KEEP_RE = re.compile(r"[^\w\u4e00-\u9fff-]+", re.UNICODE)

OUTPUT_DIR = Path("docs") / "flows"
MERMAID_ASSET_PATH = Path("assets") / "mermaid.min.js"
MERMAID_ASSET_VERSION = "10.9.3"
HTML_SUFFIX = ".html"


@dataclass(frozen=True)
class MermaidDiagram:
    title: str | None
    code: str


@dataclass(frozen=True)
class FlowDocument:
    source_path: Path
    title: str
    diagrams: tuple[MermaidDiagram, ...]

    @property
    def mermaid_blocks(self) -> tuple[str, ...]:
        return tuple(diagram.code for diagram in self.diagrams)


class FlowRenderError(RuntimeError):
    """Raised when a source document cannot be rendered as a flow HTML."""


def read_markdown(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise FlowRenderError(f"源文档不存在：{path}") from exc


def extract_flow_title(markdown: str) -> str | None:
    match = FLOW_TITLE_RE.search(markdown)
    if not match:
        return None
    title = match.group(1).strip()
    return title or None


def extract_h1_title(markdown: str) -> str | None:
    match = H1_RE.search(markdown)
    if not match:
        return None
    title = re.sub(r"[`*_#]+", "", match.group(1)).strip()
    return title or None


def normalize_explicit_title(title: str) -> str:
    normalized = INVALID_FILE_CHARS_RE.sub("", title).strip()
    normalized = re.sub(r"\s+", "", normalized)
    normalized = normalized.strip(".")
    if not normalized:
        raise FlowRenderError("flow-title 为空或只包含非法文件名字符")
    return normalized


def infer_flow_title(markdown: str, source_path: Path) -> str:
    raw_title = extract_h1_title(markdown) or source_path.stem
    title = raw_title.replace("方案文档", "").replace("设计文档", "")
    title = title.replace("方案", "").replace("计划", "")
    title = INVALID_FILE_CHARS_RE.sub("", title)
    title = INFERRED_TITLE_KEEP_RE.sub("", title)
    title = title.strip("._-")
    if not title:
        title = source_path.stem
    if not title.endswith("流程"):
        title += "流程"
    return title


def resolve_flow_title(markdown: str, source_path: Path) -> str:
    explicit = extract_flow_title(markdown)
    if explicit:
        return normalize_explicit_title(explicit)
    return normalize_explicit_title(infer_flow_title(markdown, source_path))


def parse_mermaid_diagram(raw_block: str) -> MermaidDiagram:
    lines = raw_block.strip().splitlines()
    title: str | None = None
    if lines:
        title_match = DIAGRAM_TITLE_RE.match(lines[0])
        if title_match:
            title = title_match.group(1).strip() or None
            lines = lines[1:]
    code = "\n".join(lines).strip()
    if not code:
        raise FlowRenderError("mermaid 代码块没有可渲染内容")
    return MermaidDiagram(title=title, code=code)


def extract_mermaid_diagrams(markdown: str) -> tuple[MermaidDiagram, ...]:
    diagrams = tuple(
        parse_mermaid_diagram(block)
        for block in MERMAID_BLOCK_RE.findall(markdown)
        if block.strip()
    )
    if not diagrams:
        raise FlowRenderError("未找到 mermaid 代码块，请先在源文档中添加 ```mermaid 流程图")
    return diagrams


def extract_mermaid_blocks(markdown: str) -> tuple[str, ...]:
    return tuple(diagram.code for diagram in extract_mermaid_diagrams(markdown))


def parse_flow_document(source_path: Path) -> FlowDocument:
    markdown = read_markdown(source_path)
    return FlowDocument(
        source_path=source_path,
        title=resolve_flow_title(markdown, source_path),
        diagrams=extract_mermaid_diagrams(markdown),
    )


def output_path_for(flow: FlowDocument, repo_root: Path) -> Path:
    return repo_root / OUTPUT_DIR / f"{flow.title}{HTML_SUFFIX}"


def ensure_mermaid_asset(repo_root: Path) -> Path:
    asset_path = repo_root / OUTPUT_DIR / MERMAID_ASSET_PATH
    if not asset_path.is_file():
        raise FlowRenderError(f"缺少离线 Mermaid 资源：{asset_path}")
    return asset_path


def source_display_path(source_path: Path, repo_root: Path) -> str:
    try:
        return source_path.resolve().relative_to(repo_root.resolve()).as_posix()
    except ValueError:
        return source_path.as_posix()


def render_mermaid_sections(diagrams: tuple[MermaidDiagram, ...]) -> str:
    sections: list[str] = []
    for index, diagram in enumerate(diagrams, start=1):
        if diagram.title:
            heading = f"<h2>{html.escape(diagram.title)}</h2>\n"
        elif len(diagrams) == 1:
            heading = ""
        else:
            heading = f"<h2>流程图 {index}</h2>\n"
        sections.append(
            f"{heading}<section class=\"diagram-card\">"
            f"<pre class=\"mermaid\">{html.escape(diagram.code)}</pre>"
            "</section>"
        )
    return "\n".join(sections)


def build_html(flow: FlowDocument, repo_root: Path) -> str:
    source_path = source_display_path(flow.source_path, repo_root)
    title = html.escape(flow.title)
    source = html.escape(source_path)
    diagrams = render_mermaid_sections(flow.diagrams)
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <style>
    :root {{
      color-scheme: light dark;
      --bg: #f6f7f9;
      --surface: #ffffff;
      --text: #17202a;
      --muted: #5e6a75;
      --border: #d8dee8;
      --unit: #dff5e7;
      --todo: #fff1cc;
      --manual: #e8f1ff;
      --compile: #efe8ff;
    }}
    @media (prefers-color-scheme: dark) {{
      :root {{
        --bg: #111418;
        --surface: #1b2027;
        --text: #edf1f7;
        --muted: #aab4c0;
        --border: #35404d;
        --unit: #173d2a;
        --todo: #4a3711;
        --manual: #19314f;
        --compile: #30244b;
      }}
    }}
    body {{
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
      line-height: 1.6;
    }}
    header, main {{
      max-width: 1180px;
      margin: 0 auto;
      padding: 24px;
    }}
    header {{
      padding-top: 32px;
    }}
    h1 {{
      margin: 0 0 8px;
      font-size: 28px;
      letter-spacing: 0;
    }}
    .source, .notice {{
      margin: 4px 0;
      color: var(--muted);
      font-size: 14px;
    }}
    .legend {{
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      padding: 14px 16px;
      margin-top: 18px;
      border: 1px solid var(--border);
      border-radius: 8px;
      background: var(--surface);
    }}
    .badge {{
      display: inline-flex;
      align-items: center;
      min-height: 28px;
      padding: 0 10px;
      border: 1px solid var(--border);
      border-radius: 999px;
      font-size: 14px;
      color: var(--text);
    }}
    .unit {{ background: var(--unit); }}
    .todo {{ background: var(--todo); }}
    .manual {{ background: var(--manual); }}
    .compile {{ background: var(--compile); }}
    .diagram-card {{
      overflow: auto;
      padding: 24px;
      border: 1px solid var(--border);
      border-radius: 8px;
      background: var(--surface);
    }}
    .mermaid {{
      min-width: 720px;
      margin: 0;
    }}
  </style>
  <script src="{MERMAID_ASSET_PATH.as_posix()}"></script>
  <script>
    window.addEventListener("DOMContentLoaded", async () => {{
      mermaid.initialize({{
        startOnLoad: false,
        securityLevel: "strict",
        theme: "default",
        flowchart: {{ htmlLabels: true, curve: "basis" }}
      }});
      await mermaid.run({{ querySelector: ".mermaid" }});
    }});
  </script>
</head>
<body>
  <header>
    <h1>{title}</h1>
    <p class="source">源文档：{source}</p>
    <p class="notice">此文件由脚本生成，请修改源 Markdown 后重新生成。</p>
    <div class="legend" aria-label="测试覆盖图例">
      <span class="badge unit">已单测：已有明确单元测试覆盖</span>
      <span class="badge todo">待补测：适合后续补充单元测试</span>
      <span class="badge manual">人工验证：依赖系统、权限、真实服务或视觉确认</span>
      <span class="badge compile">编译验证：通过编译或既有验证保护</span>
    </div>
  </header>
  <main>
{diagrams}
  </main>
</body>
</html>
"""


def render_to_output(source_path: Path, repo_root: Path, check: bool = False) -> Path:
    flow = parse_flow_document(source_path)
    ensure_mermaid_asset(repo_root)
    output_path = output_path_for(flow, repo_root)
    html_text = build_html(flow, repo_root)
    if check:
        if not output_path.is_file():
            raise FlowRenderError(f"生成文件不存在：{output_path}")
        current = output_path.read_text(encoding="utf-8")
        if current != html_text:
            raise FlowRenderError(f"生成文件已过期，请重新运行：{output_path}")
        return output_path
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(html_text, encoding="utf-8", newline="\n")
    return output_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="生成方案文档 Mermaid 流程图 HTML")
    parser.add_argument("source", type=Path, help="源 Markdown 方案文档路径")
    parser.add_argument("--check", action="store_true", help="只检查生成文件是否与源 Markdown 同步")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    repo_root = Path.cwd()
    source_path = args.source if args.source.is_absolute() else repo_root / args.source
    try:
        output_path = render_to_output(source_path, repo_root, check=args.check)
    except FlowRenderError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 2 if args.check else 1
    action = "检查通过" if args.check else "已生成"
    print(f"{action}：{output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
