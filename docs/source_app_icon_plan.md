状态：已完成

# 来源应用图标加载方案

## 已实现基线：范围与用户体验

- 缓存可读且 Coil 解码成功时直接使用缓存，保留 iconHash 缓存键，不查询系统。
- 路径空、文件缺失/不可读/空文件或解码失败时，按非空包名调用 PackageManager 读取 Application 图标；不扫描所有安装应用，不借用 Shizuku 进程内部能力。
- 未知来源空包名不查询系统；应用卸载、包不可见、系统读取失败时返回 null，由现有 UI 显示默认应用图标。Application 图标若由系统返回默认图标，则保留系统结果。
- 单行名称、图标尺寸、即时选择和 2:3 入口布局继续以搜索页方案为准。

## 已实现基线：数据流与职责

- `SourceAppIconResolver<Icon>` 为可测试的加载顺序协作者，构造注入缓存和系统读取函数，失败返回 null；协程取消向上传递。
- `SourceAppIconReader` 持有应用 Context 与共享 Coil ImageLoader，负责文件校验、缓存解码、单包名系统读取和失败日志，读取返回 Drawable?，不写数据库、文件或缓存元数据。
- `rememberSourceAppIcon` 仅连接 Compose 与协作者：在 IO 调度器异步读取，以包名、路径、hash 为重启 key；菜单可见行才加载，离开组合时取消。系统 Binder 调用不能强制中断，返回后检查取消；不将读取放进 ViewModel 初始化或重组同步路径。
- 复用 `isUsableCachedIconFile`、`buildSourceAppIconCacheKey`、项目 Coil 实例及默认图标资源；应用列表读取器面向整表扫描、Shizuku 读取器为另一进程 internal API，不能直接复用为菜单单项加载。

## 已实现基线：涉及文件与实现步骤

1. 在 `app/src/test/java/com/cla/clip/master/ui/widget/sourceapp/SourceAppIconResolverTest.kt` 补加载顺序与取消回归测试，先验证失败。
2. 在 `app/src/main/java/com/cla/clip/master/ui/widget/sourceapp/` 实现 Resolver、Reader 与 Compose 异步适配。
3. 搜索来源项接入共享图标能力，保留稳定包名身份、默认图标和事件契约。
4. 运行 app 单测、Kotlin 编译与差异检查，同步最终结果。

## 已实现基线：日志与诊断计划

- 本地文件检查/解码异常或解码 ErrorResult：WARN，字段仅 reasonCode=cached_icon_read_failed/cached_icon_decode_failed 与异常类型，不输出路径、包名、名称、hash 或搜索内容。
- 系统图标读取失败：DEBUG，reasonCode=system_icon_unavailable 与异常类型；不记录包名。合法未知来源不记录日志，缓存命中、绘制和重组不记录日志。
- 读取失败只记录当前可见行一次加载的结果，不进行循环重试；相同参数重组不重读。取消不记失败日志，按任务取消传播。
- 编译与回归测试验证顺序/取消；设备验收检查存在缓存、缓存缺失、坏图标、未知来源、卸载应用、大列表滚动与关闭菜单。

## 已实现基线：测试验证

- 红测：保留只读缓存的占位行为运行定向测试，7 项中 3 项预期 AssertionError 失败（缓存缺失、缓存不可用、系统读取必须被尝试），证明测试可复现旧问题；日志 `build/source-app-icon-red.log`。
- 最终执行 `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --console=plain`，`BUILD SUCCESSFUL`（33 秒），51 个测试类共 248 项全部通过，失败/错误/跳过均为 0，新增 7 项全部通过；日志 `build/source-app-icon-validation.log`。`git diff --check` 通过。
- 首次沙箱无法写入既有 `E:\Android\.gradle` 锁文件，经权限复核使用现有缓存完成验证，未更改 Gradle 配置。设备上的 PackageManager 可见性、缓存文件真实解码、图标外观与菜单取消仍待设备验收，JVM 单测只覆盖加载顺序和取消契约。

- JVM 合成数据覆盖缓存命中不访问系统、路径缺失/缓存不可用时返回系统图标、系统失败返回 null、未知包名不访问系统、hash 参数透传、取消不中途降级。
- `.\gradlew.bat :app:testDebugUnitTest --tests 'com.cla.clip.master.ui.widget.sourceapp.SourceAppIconResolverTest' --console=plain` 验证红测。
- `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --console=plain` 与 `git diff --check` 验证最终实现。

## 已实现基线：已知取舍与开放问题

- 当前代码尚不补存数据库或持久化系统图标，重复打开菜单可能重读系统；持久化补齐与分页解耦作为后续方案单独维护。
- Provider 现有同步行为暂未修改；后续持久化方案需统一文件发布和并发约定，不能让两条链路分别维护不兼容的清理规则。
- 新类型仅编译期调用，没有序列化、反射、导航或稳定外部协议，无需 keep/R8；平台图标外观、包可见性、Coil 解码与 UI 生命周期仍需设备验收。

## 变更记录





- 2026-10-02：按用户要求补齐图标三级加载链路；先补回归测试并观察 3 项预期失败，再接入单包名系统图标读取。最终 Kotlin 编译与 app 248 项单测、差异检查通过，标记已完成；不扫描整表、不扩大持久化范围，设备验收待补。
