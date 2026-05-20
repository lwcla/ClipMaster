---
name: repo-coding-gate
description: 当前仓库的代码修改门禁工作流。用于任何代码修改、修复、重构、UI 调整、数据层调整、Android/Kotlin/Compose 改动、规则变更或实现计划，要求 Codex 先检查复用、同步文档、遵守架构边界、执行验证，并在最终回复中完成自检。
---

# 仓库编码门禁

在当前仓库进入任何代码或规则修改前，先使用本 skill。它不替代 `AGENTS.md`，而是把仓库硬规则整理成可执行顺序。

## 执行顺序

1. 先向用户发送一句简短说明，包含：
   - 将检查哪些已有能力；
   - 可能更新哪份文档，或为什么不需要文档；
   - 预计如何拆分组件、类或方法；
   - 将运行什么验证命令。
2. 如果当前上下文没有完整规则，先阅读本地 `AGENTS.md`。
3. 修改前按顺序做复用检查：
   - 当前 feature/package；
   - 当前模块；
   - 共享模块；
   - 全仓兜底。
   优先使用 `rg` 或 `rg --files`，最终回复要说明复用或不复用原因。
4. 按任务类型选择配套 skill：
   - Compose/UI 改动使用 `$repo-compose-discipline`；
   - ViewModel、Repository、DAO、mapper、formatter、parser、validator、helper、工具类或数据流改动使用 `$repo-architecture-boundaries`；
   - 行为、架构、日志、诊断、生命周期或用户可见流程改动使用 `$repo-docs-logs`；
   - Room 表、设置、用户数据、下载/缓存元数据、备份恢复、dirty 状态或 Auto Backup 影响使用 `$repo-backup-coverage`；
   - 暂存、提交、改提交信息、检查提交范围或任何 Git 操作使用 `$repo-git-discipline`。
5. 涉及行为、UI、架构、数据契约、日志、备份、生命周期或规则变化时，先或同步更新文档。
6. 实现时保持范围收敛。入口类只做流程编排，复用逻辑下沉到命名清晰的协作者。
7. 运行最贴近的验证命令。Kotlin/Compose/Android 改动优先运行模块编译命令，并运行 `git diff --check`。
8. 最终回复前使用 `$repo-final-self-check`。

## Skill 语言规则

- 当前仓库的 `.codex/skills/` 是项目专用 skill，新增或更新时默认使用简体中文编写。
- 保留 skill 目录名、`name`、稳定命令、文件路径、类型名和配置 key 的英文标识；触发说明、正文流程、UI 元数据和维护约束使用简体中文。
- 如果需要引用第三方 API、命令行参数或英文专有名词，可以保留原文，但要用简体中文解释使用边界。

## 阻塞条件

出现以下情况时，不要直接修改代码：

- 没有执行复用检查；
- 必须同步的文档缺失或过期；
- 新逻辑会继续塞进已经过载的文件，且没有记录例外；
- 日志、隐私或诊断边界不清晰；
- 无法验证且没有说明风险。
