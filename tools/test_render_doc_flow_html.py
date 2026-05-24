import tempfile
import unittest
from pathlib import Path

from tools import render_doc_flow_html as renderer


class RenderDocFlowHtmlTest(unittest.TestCase):
    def test_extracts_single_mermaid_block(self):
        markdown = """# 示例方案

```mermaid
flowchart TD
  A --> B
```
"""

        blocks = renderer.extract_mermaid_blocks(markdown)

        self.assertEqual(("flowchart TD\n  A --> B",), blocks)

    def test_mermaid_title_comment_becomes_diagram_title(self):
        markdown = """# 示例方案

```mermaid
%% title: 备份流程
flowchart TD
  A --> B
```
"""

        diagrams = renderer.extract_mermaid_diagrams(markdown)
        html_text = renderer.build_html(
            renderer.FlowDocument(
                source_path=Path("docs") / "source.md",
                title="测试流程",
                diagrams=diagrams,
            ),
            Path.cwd(),
        )

        self.assertEqual("备份流程", diagrams[0].title)
        self.assertEqual("flowchart TD\n  A --> B", diagrams[0].code)
        self.assertIn("<h2>备份流程</h2>", html_text)

    def test_extracts_multiple_mermaid_blocks(self):
        markdown = """# 示例方案

```mermaid
flowchart TD
  A --> B
```

```mermaid
sequenceDiagram
  A->>B: ok
```
"""

        blocks = renderer.extract_mermaid_blocks(markdown)

        self.assertEqual(2, len(blocks))
        self.assertIn("sequenceDiagram", blocks[1])

    def test_missing_mermaid_block_fails(self):
        with self.assertRaises(renderer.FlowRenderError):
            renderer.extract_mermaid_blocks("# 示例方案\n\n没有图")

    def test_flow_title_overrides_h1_and_generates_chinese_file_name(self):
        markdown = """<!-- flow-title: WebDAV本地自动备份恢复流程 -->

# 另一个标题

```mermaid
flowchart TD
  A --> B
```
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "webdav_backup_plan.md"
            path.write_text(markdown, encoding="utf-8")

            flow = renderer.parse_flow_document(path)
            output_path = renderer.output_path_for(flow, Path(temp_dir))

        self.assertEqual("WebDAV本地自动备份恢复流程", flow.title)
        self.assertEqual(Path(temp_dir) / "docs" / "flows" / "WebDAV本地自动备份恢复流程.html", output_path)

    def test_infers_title_from_h1_when_flow_title_missing(self):
        markdown = """# WebDAV + 本地自动备份恢复方案

```mermaid
flowchart TD
  A --> B
```
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "webdav_backup_plan.md"
            path.write_text(markdown, encoding="utf-8")

            flow = renderer.parse_flow_document(path)

        self.assertEqual("WebDAV本地自动备份恢复流程", flow.title)

    def test_html_escapes_source_and_mermaid_text(self):
        flow = renderer.FlowDocument(
            source_path=Path("docs") / "unsafe<&>.md",
            title="测试流程",
            diagrams=(renderer.MermaidDiagram(title=None, code="flowchart TD\n  A[\"<危险>\"] --> B"),),
        )

        html_text = renderer.build_html(flow, Path.cwd())

        self.assertIn("unsafe&lt;&amp;&gt;.md", html_text)
        self.assertIn("&lt;危险&gt;", html_text)
        self.assertIn("assets/mermaid.min.js", html_text)
        self.assertIn("已单测：已有明确单元测试覆盖", html_text)

    def test_render_to_output_and_check_detects_drift(self):
        markdown = """<!-- flow-title: 测试流程 -->

# 测试方案

```mermaid
flowchart TD
  A --> B
```
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "docs" / "source.md"
            asset = root / "docs" / "flows" / "assets" / "mermaid.min.js"
            source.parent.mkdir(parents=True)
            asset.parent.mkdir(parents=True)
            source.write_text(markdown, encoding="utf-8")
            asset.write_text("window.mermaid = { initialize(){} };", encoding="utf-8")

            output = renderer.render_to_output(source, root)
            checked = renderer.render_to_output(source, root, check=True)
            output.write_text(output.read_text(encoding="utf-8") + "\n漂移", encoding="utf-8")

            with self.assertRaises(renderer.FlowRenderError):
                renderer.render_to_output(source, root, check=True)

        self.assertEqual(output, checked)
        self.assertEqual("测试流程.html", output.name)

    def test_missing_offline_asset_fails(self):
        markdown = """<!-- flow-title: 测试流程 -->

# 测试方案

```mermaid
flowchart TD
  A --> B
```
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "docs" / "source.md"
            source.parent.mkdir(parents=True)
            source.write_text(markdown, encoding="utf-8")

            with self.assertRaises(renderer.FlowRenderError):
                renderer.render_to_output(source, root)


if __name__ == "__main__":
    unittest.main()
