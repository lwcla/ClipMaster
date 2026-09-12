# TorrentSearch third-party dependencies

## Current task scope

Task 06D adds Provider/Torznab settings and an opt-in Cloudflare DNS-over-HTTPS mode
on top of the completed search, browse, detail, and local-data pages. TorrentSearch
also accepts real HTTP and HTTPS Provider requests for this user-approved self-use
build; the optional DoH resolver does not replace the existing HTTP transport.

Task 06B adds direct Coil Compose and Coil OkHttp network dependencies at the existing
catalog version 3.4.0. Existing Provider parsing and network behavior continue to use
the already declared coroutine, OkHttp, Jsoup, Room, DataStore, and kotlinx.serialization
dependencies.

The existing `:feature:torrent-search` module directly declares the following
dependencies in `feature/torrent-search/build.gradle.kts`; all external versions
and coordinates remain owned by `gradle/libs.versions.toml`:

| Direct dependency | Purpose in the current integration scope | License source |
| --- | --- | --- |
| `:base:general` | ClipMaster schema v4 backup contributor contract, package reader and restore report models | ClipMaster internal module |
| `:feature:torrent-search-api` | Stable host/API contract | ClipMaster internal module |
| AndroidX Core, Activity, Compose UI/Foundation/Material3 and Compose icons | Existing Activity and Compose shell | [AndroidX / AOSP Apache-2.0 notices](https://source.android.com/docs/setup/about/licenses) |
| Hilt Android and Hilt compiler | Existing host DI integration shell | [Dagger Apache-2.0 license](https://github.com/google/dagger/blob/master/LICENSE.txt) |
| AndroidX Room runtime/KTX/compiler 2.8.4 | Independent v1 database, generated DAO implementation and reviewed schema export | [AndroidX / AOSP Apache-2.0 notices](https://source.android.com/docs/setup/about/licenses) |
| AndroidX Preferences DataStore 1.2.1 | Atomic Provider, safe-mode and stable sort-code settings | [AndroidX / AOSP Apache-2.0 notices](https://source.android.com/docs/setup/about/licenses) |
| JUnit 4.13.2 (test-only) | `TorrentSearchUpstreamBaselineContractTest` contract and mutation tests | [JUnit official EPL-1.0 license](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |
| kotlinx-serialization-json 1.11.0 | Parse AniLibria JSON responses and static manifest/snapshot fixtures without reflection | [kotlinx.serialization official Apache-2.0 license](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt) |
| kotlinx-coroutines-android 1.10.2 | Provider 协调器的 Flow、超时和取消传播 | [Kotlinx Coroutines Apache-2.0 license](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| kotlinx-coroutines-test 1.10.2 (test-only) | 用虚拟调度器验证 Provider 超时、并发启动和取消 | [Kotlinx Coroutines Apache-2.0 license](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| OkHttp 5.3.2 | 真实 HTTP/HTTPS 请求边界、同来源重定向和协程取消联动 | [OkHttp Apache-2.0 license](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| okhttp-dnsoverhttps 5.3.2 | 用户主动选择后通过固定 Cloudflare HTTPS 端点解析公共域名 | [OkHttp Apache-2.0 license](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| Jsoup 1.22.1 | Parse AnimeTosho and AniRena HTML with selectors without executing site scripts | [jsoup MIT license](https://github.com/jhy/jsoup/blob/master/LICENSE) |
| Coil Compose and Coil network-okhttp 3.4.0 | Decode validated same-host HTTPS detail images at an explicit request and layout size; a feature-local OkHttp client disables HTTP and SSL redirects, and destination STOP removes image sub-Compositions to cancel requests | [Coil Apache-2.0 license](https://github.com/coil-kt/coil/blob/main/LICENSE.txt) |
| MockWebServer 5.3.2 (test-only) | 本地合成 HTTPS/gzip 响应，不进行真实联网 | [OkHttp Apache-2.0 license](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| okhttp-tls 5.3.2 (test-only) | 为 MockWebServer 生成仅测试使用的本地 HTTPS 证书 | [OkHttp Apache-2.0 license](https://github.com/square/okhttp/blob/master/LICENSE.txt) |

JUnit 4.13.2, `kotlinx-coroutines-test` 1.10.2, MockWebServer 5.3.2 and okhttp-tls 5.3.2 are test-only
dependencies used by local synthetic contract tests; they are not runtime
dependencies. Network fixtures use a loopback HTTPS
server and a test-only certificate trust configuration; production exposes no
cleartext test bypass; production cleartext is an explicit supported transport for
user-configured TorrentSearch providers.

Upstream source attribution is separate from runtime dependencies: the fixed
TorrentSearch snapshot is MIT licensed and its full text is retained in
`LICENSE`; its resource baseline and metadata are described in `NOTICE.md`.

## Follow-up dependency rule

Future runtime imports must add every new external coordinate through the version
catalog, document its exact purpose and license source here, update Gradle
dependency-verification metadata, and update this final license inventory. Task 8
freezes verification metadata only after both feature-disabled and feature-enabled
Release/R8 graphs resolve successfully.
