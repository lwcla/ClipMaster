# TorrentSearch third-party dependencies

## Current task scope

Task 3 imports only static upstream resource files and source metadata. It adds
no external runtime dependency.

The existing `:feature:torrent-search` module directly declares the following
dependencies in `feature/torrent-search/build.gradle.kts`; all external versions
and coordinates remain owned by `gradle/libs.versions.toml`:

| Direct dependency | Purpose in the current empty integration shell | License source |
| --- | --- | --- |
| `:feature:torrent-search-api` | Stable host/API contract | ClipMaster internal module |
| AndroidX Core, Activity, Compose UI/Foundation/Material3 and Compose icons | Existing Activity and Compose shell | [AndroidX / AOSP Apache-2.0 notices](https://source.android.com/docs/setup/about/licenses) |
| Hilt Android and Hilt compiler | Existing host DI integration shell | [Dagger Apache-2.0 license](https://github.com/google/dagger/blob/master/LICENSE.txt) |

JUnit and `kotlinx-serialization-json` are test-only dependencies used by the
local contract test; they are not runtime dependencies introduced by this task.

Upstream source attribution is separate from runtime dependencies: the fixed
TorrentSearch snapshot is MIT licensed and its full text is retained in
`LICENSE`; its resource baseline and metadata are described in `NOTICE.md`.

## Follow-up dependency rule

Future runtime imports must add every new external coordinate through the version
catalog, document its exact purpose and license source here, and update the final
license aggregation. Gradle dependency-verification metadata and the complete
license inventory are intentionally deferred to task 8, after all runtime
dependencies are known; this task must not freeze incomplete verification metadata.
