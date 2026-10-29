"""用合成配置保护版本发布门禁，避免普通提交误发或版本提交漏发。"""

import unittest

from scripts.github_release_version import release_outputs


def gradle_source(name: str = "0.9.12", code: int = 48) -> str:
    """生成当前仓库静态版本配置的最小样本，不使用真实签名或广告参数。"""
    return f'android {{\n defaultConfig {{\n versionCode = {code}\n versionName = "{name}"\n }}\n}}\n'


class GithubReleaseVersionTest(unittest.TestCase):
    """验证是否发布的业务结果、标签稳定性与无效配置的失败契约。"""

    def test_unchanged_version_skips_release(self):
        """相同版本不能因另一次代码提交重复发布。"""
        self.assertEqual(release_outputs(gradle_source(), gradle_source()), {
            "changed": "false", "version_name": "0.9.12", "version_code": "48",
            "tag": "v0.9.12-48", "reason": "version_unchanged",
        })

    def test_version_name_change_publishes(self):
        """版本名变化必须触发，并输出可供发布步骤使用的安全标签。"""
        self.assertEqual(release_outputs(gradle_source(), gradle_source("0.9.13")), {
            "changed": "true", "version_name": "0.9.13", "version_code": "48",
            "tag": "v0.9.13-48", "reason": "version_changed",
        })

    def test_version_code_change_publishes_with_distinct_tag(self):
        """仅版本码变化也不能与上一版共用标签。"""
        self.assertEqual(release_outputs(gradle_source(), gradle_source(code=49)), {
            "changed": "true", "version_name": "0.9.12", "version_code": "49",
            "tag": "v0.9.12-49", "reason": "version_changed",
        })

    def test_unrelated_gradle_change_skips_release(self):
        """编译 SDK 等配置变化不能被误判为版本变化。"""
        self.assertEqual(
            release_outputs(gradle_source(), gradle_source() + "compileSdk = 36\n").get("changed"),
            "false",
        )

    def test_comments_and_formatting_do_not_change_version(self):
        """注释里的历史版本与行内注释不能干扰实际静态版本。"""
        # 带注释的样本包含虚假历史版本、CRLF 与等价空白，用于防止文本 diff 误触发。
        reformatted = '/*\nversionName = "old"\nversionCode = 1\n*/\r\nversionName="0.9.12" // 名称\r\nversionCode=48 // 版本码\r\n'
        self.assertEqual(release_outputs(gradle_source(), reformatted).get("changed"), "false")

    def test_missing_baseline_skips_initial_branch_push(self):
        """没有 push 前基线时不把首次建仓当成升级发布。"""
        self.assertEqual(release_outputs(None, gradle_source()), {
            "changed": "false", "version_name": "0.9.12", "version_code": "48",
            "tag": "v0.9.12-48", "reason": "missing_baseline",
        })

    def test_invalid_current_version_fails_closed(self):
        """缺失、重复、动态值和危险标签均应报错，不能继续发布。"""
        # 各样本独立覆盖解析边界，避免非法配置被降级为一次有效升级。
        invalid_sources = [
            'versionCode = 49\n',
            gradle_source() + 'versionName = "0.9.13"\n',
            gradle_source() + 'versionName = calculateName()\n',
            gradle_source() + 'versionCode = calculateCode()\n',
            'versionCode = nextCode()\nversionName = "0.9.13"\n',
            gradle_source("../bad"),
            gradle_source("0.9.13\ninjected=true"),
            gradle_source(code=0),
        ]
        # 当前样本只包含合成配置，子测试失败不暴露实际构建参数。
        for source in invalid_sources:
            with self.subTest(source=source):
                with self.assertRaises(ValueError):
                    release_outputs(gradle_source(), source)

    def test_invalid_baseline_does_not_force_publish(self):
        """无法解析旧配置时应要求修复门禁，而不是猜测发生了版本升级。"""
        with self.assertRaises(ValueError):
            release_outputs("versionCode = unknown\n", gradle_source())


if __name__ == "__main__":
    unittest.main()
