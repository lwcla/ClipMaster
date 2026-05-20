---
name: repo-architecture-boundaries
description: 当前仓库的架构边界工作流。用于修改或新增 ViewModel、Repository、Worker、DAO、Room 实体、mapper、comparator、parser、formatter、validator、helper、平台封装、序列化模型、设置项或工具函数，确保职责、位置、命名、失败契约和模块边界清晰。
---

# 仓库架构边界

非平凡 Kotlin、领域层或数据层改动，应与 `$repo-coding-gate` 配合使用本 skill。

## 边界检查

1. 先识别入口类，并让入口类专注流程编排：
   - ViewModel 连接 UI 状态和事件；
   - Repository 协调数据源和事务；
   - Worker 协调后台执行；
   - DAO 只暴露数据库契约；
   - mapper、parser、formatter、validator 承载纯逻辑或单一职责逻辑。
2. 新增代码前先查找已有 helper、mapper、validator、formatter、repository 和平台封装。
3. 按“领域 + 职责”放置新逻辑，避免塞进泛化 `Utils` 或过载入口文件底部。
4. 无状态解析、比较、映射、规范化和校验优先使用纯 Kotlin 函数或 `object`。
5. 需要 `Context`、数据库、网络、调度器、系统 API 或可替换依赖时，优先使用构造函数注入的类。

## 类和方法规则

- 按职责命名：`*Mapper`、`*Comparator`、`*Parser`、`*Formatter`、`*Validator`、`*Reader`、`*Writer`、`*Publisher`、`*Scheduler`、`*Repository`。
- 明确失败语义：返回值、sealed result、可空值或异常，不能悄悄混用。
- 除非记录为临时例外，不要把业务 mapper、comparator 或 parser 逻辑放进大型 ViewModel/Repository 文件。
- 保持模块依赖单向，不要为了复用让底层模块依赖 app feature 代码。
- 类型、方法、字段、契约和非显然分支补充简体中文注释。
- 新增序列化或外部协议模型时，使用稳定字段注解，并评估 keep/R8 需求。

## 重构安全

- 拆分顺序从低风险到高风险：纯逻辑、IO/平台封装、可复用 UI/state、核心流程编排。
- 除非用户明确要求行为变化，抽取时保持行为等价。
- 暂时不拆分的逻辑必须记录在方案文档或最终回复中。
