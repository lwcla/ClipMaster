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
- 新增或修改序列化、外部协议或运行时反射相关模型时，使用稳定字段注解，并评估 keep/R8 需求。

## 反混淆与稳定契约

- 先判断稳定边界：只有被 JSON/序列化、Intent、通知协议、WebView JS、导航类型安全路由、第三方 SDK、反射、系统框架或跨模块稳定 ABI 依赖的类型，才默认进入反混淆评估；普通 Room 实体、页面 UI state、Worker 内部结果和纯流程对象不因 data class 身份自动 keep。
- 需要反混淆时优先使用统一注解标记，例如 AndroidX `@Keep` 或项目后续语义化注解；类型注释必须说明谁依赖稳定，以及稳定的是类名、字段名、构造函数、方法签名、枚举值还是序列化 type name。
- 外部字段必须用协议注解固定，例如 `@SerialName`、`@SerializedName` 或项目等价注解；禁止依赖 Kotlin 属性名、枚举 `name`、ordinal 或 sealed 子类名作为长期协议。
- ProGuard/R8 规则要按最小粒度编写：先判断保留类名、字段、方法、构造函数、枚举值还是注解成员；禁止为省事把 `entity`、`model`、`data`、`dto` 等宽泛包整包 keep。
- 库模块对外暴露稳定模型、反射入口或序列化契约时，混淆规则优先放在该模块 consumer rules 或等价位置，不只依赖 app 模块兜底。
- 枚举或 sealed class 参与数据库、JSON、Intent、JS、导航或跨模块协议时，优先定义稳定 code/type 字段或序列化 type name。
- 涉及混淆规则、反射模型、序列化模型、通知/Intent/WebView JS/导航协议或 SDK 回调模型时，优先补充 release/R8 验证；无法运行时必须在方案文档和最终回复说明未验证项、风险和后续验证方向。

## 重构安全

- 拆分顺序从低风险到高风险：纯逻辑、IO/平台封装、可复用 UI/state、核心流程编排。
- 除非用户明确要求行为变化，抽取时保持行为等价。
- 暂时不拆分的逻辑必须记录在方案文档或最终回复中。
