"""检查发布范围内的默认英文与简体中文资源；失败仅输出路径和 key，不输出文案。"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Java Formatter 的参数语法；忽略显示宽度但保留参数编号和类型，允许翻译调整语序。
FORMAT_ARGUMENT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?([tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaA%n])")
# 中文汉字检测用于发现默认英文中的漏译，不检查注释或品牌等非翻译资源。
CHINESE_TEXT = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
# 只检查用户可见文本类型，颜色、尺寸和主题不属于翻译契约。
TEXT_TYPES = {"string", "plurals", "string-array"}


def placeholders(text: str) -> set[tuple[int, str]]:
    """提取参数编号与类型；允许显式编号重复引用，不把百分号与换行算作参数。"""
    # result 保存参数契约，implicit_index 记录未编号参数顺序，previous_index 支持 Java 的 < 复用。
    result = set()
    implicit_index = 0
    previous_index = 0
    # match 为单个格式化 token，其三组分别是编号、标志和类型。
    for match in FORMAT_ARGUMENT.finditer(text):
        # conversion 是转换类型，百分号和换行不会消费调用方参数。
        conversion = match.group(3)
        if conversion in {"%", "n"}:
            continue
        if match.group(1):
            # index 保持 1 起始的 Java 参数编号。
            index = int(match.group(1))
        elif "<" in match.group(2):
            index = previous_index
        else:
            implicit_index += 1
            index = implicit_index
        result.add((index, conversion.lower()))
        previous_index = index
    return result


def read_resources(directory: Path) -> tuple[dict, list[str]]:
    """读取一个语言目录的所有 XML，收集文本资源并报告重复或无效结构。"""
    # resources 按类型/key 索引；issues 保留所有错误，避免一次只暴露一个遗漏。
    resources = {}
    issues = []
    # path 按文件名排序，保证本地和 CI 的诊断顺序一致。
    for path in sorted(directory.glob("*.xml")):
        try:
            # root 是已解析的资源容器，ElementTree 默认不包含注释节点。
            root = ET.parse(path).getroot()
        except ET.ParseError:
            issues.append(f"{path}: invalid XML")
            continue
        # element 只处理可翻译资源；非文本 Android 资源由 aapt 校验。
        for element in root:
            if element.tag not in TEXT_TYPES:
                continue
            # key 组合类型和名字，避免不同资源类型的同名条目互相覆盖。
            key = (element.tag, element.get("name", ""))
            if key in resources:
                issues.append(f"{path}: duplicate {key}")
            # variants 保存字符串本身、复数分类或数组位置各自的完整文本。
            variants = {}
            if element.tag == "string":
                variants["text"] = "".join(element.itertext())
            else:
                # position 对应数组索引，item 为单个复数/数组子项。
                for position, item in enumerate(element.findall("item")):
                    # variant 在复数中使用 quantity，数组中使用稳定位置。
                    variant = item.get("quantity", "") if element.tag == "plurals" else str(position)
                    if variant in variants:
                        issues.append(f"{path}: duplicate item {key}/{variant}")
                    variants[variant] = "".join(item.itertext())
                if element.tag == "plurals" and "other" not in variants:
                    issues.append(f"{path}: missing other {key}")
            resources[key] = (element, variants)
    return resources, issues


def check_resource_pair(default_dir: Path, translated_dir: Path) -> list[str]:
    """校验一个模块的英文/中文资源；资源缺失和格式问题统一返回诊断列表。"""
    # defaults/translations 是两种语言的索引，issues/translated_issues 收集各自的解析错误。
    defaults, issues = read_resources(default_dir)
    translations, translated_issues = read_resources(translated_dir)
    issues.extend(translated_issues)
    # key 对应默认资源，element/variants 分别描述属性与各变体正文。
    for key, (element, variants) in defaults.items():
        if element.get("translatable") == "false":
            continue
        if any(CHINESE_TEXT.search(text) for text in variants.values()):
            issues.append(f"{default_dir}: Chinese in default {key}")
        if key not in translations:
            issues.append(f"{translated_dir}: missing translation {key}")
            continue
        # translated_element 与 translated_variants 用于核对属性和正文格式参数。
        translated_element, translated_variants = translations[key]
        if translated_element.get("translatable") == "false":
            issues.append(f"{translated_dir}: translation marked nontranslatable {key}")
        if key[0] == "string-array" and variants.keys() != translated_variants.keys():
            issues.append(f"{translated_dir}: item mismatch {key}")
        if element.get("formatted", "true") != translated_element.get("formatted", "true"):
            issues.append(f"{translated_dir}: formatted attribute mismatch {key}")
        if element.get("formatted") == "false":
            continue
        # variant/text 是中文条目；语言专属复数分类与默认 other 比较参数契约。
        for variant, text in translated_variants.items():
            # original 是相同分类或默认 other，缺失数组项已由长度检查报告。
            original = variants.get(variant, variants.get("other", ""))
            if placeholders(original) != placeholders(text):
                issues.append(f"{translated_dir}: placeholder mismatch {key}/{variant}")
    # key 在中文存在而默认缺失会破坏非中文用户的资源回退。
    for key in translations.keys() - defaults.keys():
        issues.append(f"{translated_dir}: missing default {key}")
    return issues


def main() -> int:
    """扫描仓库一/二级模块源码资源；返回非零退出码供 CI 阻止缺失翻译。"""
    # repository 从脚本位置推导，支持从任意工作目录运行而不误扫构建缓存。
    repository = Path(__file__).resolve().parents[1]
    # resource_dirs 对应 app/shizuku 和 base/feature 的实际模块层级。
    resource_dirs = sorted(set(repository.glob("*/src/main/res")) | set(repository.glob("*/*/src/main/res")))
    # issues 保存全仓诊断，module_count 只统计确有文本资源的模块。
    issues = []
    module_count = 0
    # resource_dir 为当前模块 res 根目录。
    for resource_dir in resource_dirs:
        # defaults 只用于判断当前模块是否承载文案，具体结构错误仍由成对检查报告。
        defaults, parse_issues = read_resources(resource_dir / "values")
        if not defaults and not parse_issues:
            continue
        module_count += 1
        issues.extend(check_resource_pair(resource_dir / "values", resource_dir / "values-b+zh+Hans"))
        # 英文或旧中文地区覆盖可能遮蔽新翻译，因此要求与标准资源逐项一致。
        for qualifier, canonical in (("values-en", "values"), ("values-zh-rCN", "values-b+zh+Hans")):
            # overrides 是可选覆盖；reference 是实际维护的唯一语言源。
            overrides, override_issues = read_resources(resource_dir / qualifier)
            reference, _ = read_resources(resource_dir / canonical)
            issues.extend(override_issues)
            # key/value 描述覆盖资源，必须在规范目录中存在且正文一致。
            for key, value in overrides.items():
                if key not in reference or value[1] != reference[key][1]:
                    issues.append(f"{resource_dir / qualifier}: stale override {key}")
    # issue 仅包含路径和资源标识，不包含用户或配置内容。
    for issue in sorted(issues):
        print(issue)
    print(f"Localization: {module_count} modules, {len(issues)} issues")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
