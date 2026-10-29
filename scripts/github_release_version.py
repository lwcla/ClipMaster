"""比较 push 前后的静态 App 版本，为 GitHub Actions 提供发布门禁。"""

import argparse
from pathlib import Path
import re
import subprocess


def parse_version(source: str) -> tuple[str, int]:
    """读取仓库约定的唯一静态版本声明；动态值或不安全标签以 ValueError 拒绝。"""
    # 注释可能保留旧版本示例；保留普通字符串，移除行注释和块注释再匹配声明。
    uncommented = re.sub(
        r'("(?:\\.|[^"\\])*")|//[^\n]*|/\*[\s\S]*?\*/',
        # 字符串 token 原样保留，注释替为空格，防止相邻 token 被意外拼接。
        lambda match: match.group(1) or " ",
        source,
    )
    # names 收集字面量版本名；codes 收集整数字面量版本码，重复声明不猜测生效顺序。
    names = re.findall(r'^[ \t]*versionName[ \t]*=[ \t]*"([^"\r\n]*)"[ \t]*\r?$', uncommented, re.MULTILINE)
    codes = re.findall(r'^[ \t]*versionCode[ \t]*=[ \t]*([0-9]+)[ \t]*\r?$', uncommented, re.MULTILINE)
    # 额外统计所有赋值入口，防止后续动态赋值覆盖静态值却被字面量匹配忽略。
    declarations = re.findall(r'^[ \t]*(versionName|versionCode)[ \t]*=', uncommented, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1 or len(declarations) != 2:
        raise ValueError("invalid_version_declaration")
    # name 必须可安全用于 Git 标签/Actions 输出；code 必须是 Android 的正整数版本码。
    name, code = names[0], int(codes[0])
    if not re.fullmatch(r"[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*", name) or code <= 0:
        raise ValueError("invalid_version_value")
    return name, code


def release_outputs(before: str | None, after: str) -> dict[str, str]:
    """根据旧/新 Gradle 文本生成发布判定；无效配置必须抛出 ValueError。"""
    # 当前版本用于比较和生成固定发布身份，不把运行次数当成 App 版本。
    name, code = parse_version(after)
    # 无基线表示首次建分支；其他情况必须成功解析旧版本才能判断是否变化。
    changed = before is not None and parse_version(before) != (name, code)
    return {
        "changed": str(changed).lower(),
        "version_name": name,
        "version_code": str(code),
        "tag": f"v{name}-{code}",
        "reason": "missing_baseline" if before is None else (
            "version_changed" if changed else "version_unchanged"
        ),
    }


def read_gradle_at_commit(commit: str) -> str:
    """只读指定提交的版本文件；非法 SHA 或不可读取提交直接终止，不回退到 HEAD。"""
    if not re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        raise ValueError("invalid_commit_sha")
    return subprocess.run(
        ["git", "show", f"{commit}:app/build.gradle.kts"],
        check=True, capture_output=True, text=True, encoding="utf-8",
    ).stdout


def main() -> int:
    """读取 push 两端的 Git 文件并写 Actions 输出；失败只打印低敏分类并返回非零码。"""
    # 参数分别固定比较基线、构建提交与 Actions 输出文件，避免依赖可变分支名。
    parser = argparse.ArgumentParser(description="检查 App 版本是否变化")
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    parser.add_argument("--output", type=Path, required=True)
    # args 只承载提交标识和输出位置，不包含签名或广告配置。
    args = parser.parse_args()
    try:
        # GitHub 首次创建分支时 before 为全零；此时跳过自动发布。
        before = None if args.before == "0" * 40 else read_gradle_at_commit(args.before)
        # 输出白名单全部由静态版本生成，禁止将原始 Gradle 文件写入日志或 Actions 输出。
        outputs = release_outputs(before, read_gradle_at_commit(args.after))
        # Actions 输出采用受控的单行键值，供下游 job 决定是否构建。
        with args.output.open("a", encoding="utf-8", newline="\n") as output_file:
            # key 为固定字段名，value 为经过约束的版本或布尔状态。
            for key, value in outputs.items():
                output_file.write(f"{key}={value}\n")
        print(f"版本检查：changed={outputs['changed']} tag={outputs['tag']} reasonCode={outputs['reason']}")
        return 0
    except ValueError as error:
        # 纯解析异常仅携带本文件定义的稳定原因码，不附带输入正文。
        print(f"::error::版本配置检查失败 reasonCode={error}")
    except subprocess.CalledProcessError:
        print("::error::无法读取版本提交 reasonCode=git_read_failed")
    except OSError:
        print("::error::无法读写版本检查文件 reasonCode=io_failed")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
