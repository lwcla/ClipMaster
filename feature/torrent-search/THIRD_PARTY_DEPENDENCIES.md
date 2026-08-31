# TorrentSearch third-party dependencies

## Current task scope

Task 04A adds the shared Provider search kernel, direct OkHttp boundary and
coroutine coordinator on top of the Task 3 static upstream baseline. It does not
yet import real Provider implementations, UI data, Room, Torznab or WebView.

The existing `:feature:torrent-search` module directly declares the following
dependencies in `feature/torrent-search/build.gradle.kts`; all external versions
and coordinates remain owned by `gradle/libs.versions.toml`:

| Direct dependency | Purpose in the current integration scope | License source |
| --- | --- | --- |
| `:feature:torrent-search-api` | Stable host/API contract | ClipMaster internal module |
| AndroidX Core, Activity, Compose UI/Foundation/Material3 and Compose icons | Existing Activity and Compose shell | [AndroidX / AOSP Apache-2.0 notices](https://source.android.com/docs/setup/about/licenses) |
| Hilt Android and Hilt compiler | Existing host DI integration shell | [Dagger Apache-2.0 license](https://github.com/google/dagger/blob/master/LICENSE.txt) |
| JUnit 4.13.2 (test-only) | `TorrentSearchUpstreamBaselineContractTest` contract and mutation tests | [JUnit official EPL-1.0 license](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |
| kotlinx-serialization-json 1.11.0 (test-only) | Parse static manifest and snapshot fixtures in local contract tests | [kotlinx.serialization official Apache-2.0 license](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt) |
| kotlinx-coroutines-android 1.10.2 | Provider 协调器的 Flow、超时和取消传播 | [Kotlinx Coroutines Apache-2.0 license](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| kotlinx-coroutines-test 1.10.2 (test-only) | 用虚拟调度器验证 Provider 超时、并发启动和取消 | [Kotlinx Coroutines Apache-2.0 license](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| OkHttp 5.3.2 | 直接 HTTP 边界、HTTPS 限制和协程取消联动 | [OkHttp Apache-2.0 license](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| MockWebServer 5.3.2 (test-only) | 本地合成 HTTP/gzip 响应，不进行真实联网 | [OkHttp Apache-2.0 license](https://github.com/square/okhttp/blob/master/LICENSE.txt) |

JUnit 4.13.2, `kotlinx-serialization-json` 1.11.0,
`kotlinx-coroutines-test` 1.10.2 and MockWebServer 5.3.2 are test-only
dependencies used by local synthetic contract tests; they are not runtime
dependencies introduced by Task 04A.

Upstream source attribution is separate from runtime dependencies: the fixed
TorrentSearch snapshot is MIT licensed and its full text is retained in
`LICENSE`; its resource baseline and metadata are described in `NOTICE.md`.

## Follow-up dependency rule

Future runtime imports must add every new external coordinate through the version
catalog, document its exact purpose and license source here, and update the final
license aggregation. Gradle dependency-verification metadata and the complete
license inventory are intentionally deferred to task 8, after all runtime
dependencies are known; this task must not freeze incomplete verification metadata.
