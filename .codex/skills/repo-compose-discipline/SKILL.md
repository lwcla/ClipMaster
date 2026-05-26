---
name: repo-compose-discipline
description: 当前仓库的 Compose UI 纪律工作流。用于新增、编辑、抽取或评审 Jetpack Compose 页面、组件、弹窗、底部弹层、列表、卡片、工具栏、操作行、空态/加载/错误态、导航 UI 或 UI state 模型，尤其关注复用、组件归属、稳定 key、Preview、生命周期和字符串资源。
---

# 仓库 Compose 纪律

Compose 或 UI 改动应与 `$repo-coding-gate` 配合使用本 skill。

## 修改前检查

1. 按顺序查找已有 UI 能力：
   - 同页面或同 package；
   - 同 feature 的 component/widget package；
   - 共享 UI、widget 或设计系统目录；
   - 全仓兜底。
2. 决定组件归属：
   - 页面级 Composable 只负责状态和结构编排；
   - feature 内可复用组件放在对应 feature 附近；
   - 跨 feature 组件放到共享 UI/widget/设计系统目录；
   - 页面私有组件必须有真实的页面专属原因。
3. 尽量把业务实体转换为稳定 UI state 或 config。
4. 优先使用 slot、callback、config 对象，避免大量 Boolean 或可空参数。
5. 新增用户可见文案前检查字符串资源。
6. 检查生命周期：Flow/Paging 使用生命周期感知 API，不从不可见 UI 启动重型任务。

## 实现规则

- 可复用 Composable 提供 `modifier: Modifier = Modifier`。
- 共享 Composable 不直接做业务副作用，业务动作通过 callback 交给调用方。
- `LazyColumn`、`LazyRow`、grid、pager 和动画 item 使用稳定 key。
- 除非现有设计要求，不要卡片套卡片，也不要把页面区块做成装饰性卡片。
- 避免一次性复制 Dialog、Sheet、Card；应抽组件，或记录为什么不能抽。
- 小屏和动态字体下确保文本不溢出、不重叠。
- 所有新增或修改的 `@Composable`、Preview、remember 辅助函数、格式化展示函数、状态分支函数和事件回调都必须补充简体中文注释。
- 所有新增或修改的 UI state 字段、局部状态、局部变量、布局尺寸、颜色/间距常量、动画参数、Lazy key、回调参数和派生状态变量都必须补充简体中文注释，说明 UI 职责、状态来源、用户交互、重组边界、保存边界或修改风险。
- `LaunchedEffect`、`DisposableEffect`、滚动状态、动画、资源加载、权限触发、导航回调和一次性事件必须注释说明触发 key、启动条件、取消边界以及为什么副作用放在当前层级。
- 复杂共享 UI 尽量新增或更新 Preview/sample state。

## 最终回复要点

最终回复说明：

- 复用了哪个已有组件，或为什么不能复用；
- 新增或拆分了什么组件，放在哪里；
- 新增或修改的 Composable、变量和状态字段是否已补齐简体中文注释；
- 是否存在页面私有组件作为已记录例外；
- 运行了什么验证。
