"""用合成资源保护中英文发布门禁，不依赖 Android SDK 或真实用户内容。"""

import tempfile
import unittest
from pathlib import Path

from scripts.check_localization import check_resource_pair


class LocalizationCheckTest(unittest.TestCase):
    """验证缺翻译、错误占位符和复数边界均能阻止发布。"""

    def check_pair(self, english: str, chinese: str) -> list[str]:
        """在隔离目录中创建最小资源对，返回可读诊断而不写入仓库资源。"""
        # 临时目录只承载合成 XML，在每个用例结束后由标准库清理。
        with tempfile.TemporaryDirectory() as directory:
            # 两个目录模拟默认英文与简体中文，避免测试依赖主机语言。
            default_dir = Path(directory) / "values"
            chinese_dir = Path(directory) / "values-b+zh+Hans"
            default_dir.mkdir()
            chinese_dir.mkdir()
            (default_dir / "strings.xml").write_text(f"<resources>{english}</resources>", encoding="utf-8")
            (chinese_dir / "strings.xml").write_text(f"<resources>{chinese}</resources>", encoding="utf-8")
            return check_resource_pair(default_dir, chinese_dir)

    def test_reordered_indexed_placeholders_are_valid(self):
        """不同语言可以调整语序，显式参数编号和类型应保持一致。"""
        self.assertEqual([], self.check_pair(
            '<string name="count">%1$s: %2$d</string>',
            '<string name="count">%2$d 项：%1$s</string>',
        ))

    def test_missing_translation_is_reported(self):
        """新增英文 key 后漏补中文应失败。"""
        self.assertTrue(any("missing translation" in issue for issue in self.check_pair(
            '<string name="action">Retry</string>', "")))

    def test_nontranslatable_constant_does_not_require_translation(self):
        """品牌和固定配置允许仅存在于默认目录。"""
        self.assertEqual([], self.check_pair('<string name="brand" translatable="false">Brand</string>', ""))

    def test_placeholder_type_mismatch_is_reported(self):
        """格式化整数被误改成字符串时给出可定位诊断。"""
        self.assertTrue(any("placeholder mismatch" in issue for issue in self.check_pair(
            '<string name="count">%1$d</string>', '<string name="count">%1$s</string>')))

    def test_missing_placeholder_is_reported(self):
        """翻译漏参即使 XML 合法也必须失败。"""
        self.assertTrue(any("placeholder mismatch" in issue for issue in self.check_pair(
            '<string name="count">%d</string>', '<string name="count">数量</string>')))

    def test_literal_percent_and_formatted_false_are_valid(self):
        """转义百分号与关闭格式化的正文不应被误认为参数。"""
        self.assertEqual([], self.check_pair(
            '<string name="percent">%1$d%%</string><string name="literal" formatted="false">100% done</string>',
            '<string name="percent">%1$d%%</string><string name="literal" formatted="false">完成 100%</string>',
        ))

    def test_chinese_in_default_is_reported(self):
        """默认英文资源残留中文时失败，但中文注释不参与检查。"""
        self.assertTrue(any("Chinese in default" in issue for issue in self.check_pair(
            '<!-- 中文注释允许保留 --><string name="action">重试</string>', '<string name="action">重试</string>')))

    def test_duplicate_key_is_reported(self):
        """重复 key 不能被字典覆盖后悄悄通过检查。"""
        self.assertTrue(any("duplicate" in issue for issue in self.check_pair(
            '<string name="a">A</string><string name="a">B</string>', '<string name="a">甲</string>')))

    def test_plural_categories_can_differ_between_languages(self):
        """中文只需要 other，不能强制复制英文 one 分类。"""
        self.assertEqual([], self.check_pair(
            '<plurals name="items"><item quantity="one">%d item</item><item quantity="other">%d items</item></plurals>',
            '<plurals name="items"><item quantity="other">%d 项</item></plurals>',
        ))

    def test_plural_requires_other(self):
        """缺失复数兜底分类会导致运行时失败，应在检查阶段阻止。"""
        self.assertTrue(any("missing other" in issue for issue in self.check_pair(
            '<plurals name="items"><item quantity="other">%d items</item></plurals>',
            '<plurals name="items"><item quantity="one">%d 项</item></plurals>',
        )))

    def test_array_length_and_placeholder_are_checked(self):
        """字符串数组需要维持对应索引及格式参数。"""
        self.assertTrue(any("item mismatch" in issue for issue in self.check_pair(
            '<string-array name="items"><item>One</item><item>Two</item></string-array>',
            '<string-array name="items"><item>一</item></string-array>',
        )))

    def test_translation_without_default_is_reported(self):
        """仅中文存在的孤立资源不能覆盖默认语言的完整性要求。"""
        self.assertTrue(any("missing default" in issue for issue in self.check_pair(
            "", '<string name="action">重试</string>')))

    def test_invalid_xml_is_reported(self):
        """无效 XML 返回诊断，不把解析异常伪装成无资源。"""
        self.assertTrue(any("invalid XML" in issue for issue in self.check_pair('<string name="a">', "")))


if __name__ == "__main__":
    unittest.main()
