状态：实现中

# local.properties 广告配置示例

## 当前状态

广告参数只从本机 `local.properties` 或 CI Gradle 属性读取，不写入源码、不进入备份、不输出到日志。模块开关统一在项目 `gradle.properties` 中声明，也可以由 CI/命令行 `-P` 覆盖；当前真实国内广告源支持 CSJ 与 uni-ad，但同一个 buildType 只能开启其中一套。

## 模块开关

项目 `gradle.properties` 默认关闭全部可选实现。需要构建广告包时，把目标开关改为 `true`，或在命令行传同名 `-P` 参数：

```properties
# debug/internal 穿山甲 adapter 开关。
enableCsjDebugAdFeature=false
# release 穿山甲 adapter 开关。
enableCsjReleaseAdFeature=false
# debug/internal uni-ad adapter 开关。
enableUniadDebugAdFeature=false
# release uni-ad adapter 开关。
enableUniadReleaseAdFeature=false
```

开关开启后，对应 buildType 的下列参数必须完整且为数字 ID，否则 Gradle 在配置期失败。只把 ID 写入 `local.properties` 不会自动启用模块。

## uni-ad 示例

```properties
# debug/internal：绑定项目固定 debug-internal.keystore 的测试后台应用和测试 adpid。
uniadDebugAppId=123456
uniadDebugUnionId=654321
uniadDebugDetailNativeAdpid=1000000001

# release：绑定正式 release 签名的正式后台应用和正式 adpid。
uniadReleaseAppId=234567
uniadReleaseUnionId=765432
uniadReleaseDetailNativeAdpid=1000000002

# 可选：要求用户已同意包含当前 uni-ad、章鱼、泛连 SDK 清单的隐私政策版本。
uniadRequiredPrivacyPolicyVersion=2026-06-uniad
```

## CSJ 示例

```properties
# debug/internal：绑定项目固定 debug-internal.keystore 的测试应用和测试代码位。
csjDebugAppId=123456
csjDebugDetailNativeAdSlotId=987654321
csjDebugUseTestAdSlot=true

# release：绑定正式 release 签名的正式应用和正式代码位。
csjReleaseAppId=234567
csjReleaseDetailNativeAdSlotId=876543210
csjReleaseUseTestAdSlot=false

# 可选：要求用户已同意包含当前 CSJ SDK 清单的隐私政策版本。
csjRequiredPrivacyPolicyVersion=2026-06-csj
```

## 临时关闭

- 临时关闭 debug/internal 真实广告：把对应 debug feature 开关设为 `false`，无需删除本机 ID。
- 临时关闭 release 真实广告：把对应 release feature 开关设为 `false`，无需删除本机 ID。
- 同一 buildType 不要同时开启 CSJ 与 uni-ad；Gradle 会在配置期失败，避免同包重复带入两套真实国内广告 SDK。

## 变更记录

- 2026-06-03：新增广告本机配置示例，记录 uni-ad 与 CSJ buildType 专属参数、隐私版本和临时关闭方式；原因是广告模块已改为配置齐全时自动打包，开发者不需要手动传启用开关。
- 2026-09-13：增加四个显式广告 feature 开关示例，并把临时关闭方式改为关闭开关；原因是可选模块统一由 `gradle.properties`/`-P` 控制，广告密钥只负责参数配置与开启后的完整性校验。
