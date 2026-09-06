# TorrentSearch upstream notice

This module incorporates the resource baseline and auditable source metadata from
[`prajwalch/TorrentSearch`](https://github.com/prajwalch/TorrentSearch) at the
fixed commit `ba82e889637027f7bed82715ec4d2106cbce5a28`.

Upstream copyright: Copyright (c) 2026 Prajwal Chapagain.
Upstream license: MIT. The complete upstream license text is retained in
[`LICENSE`](LICENSE), including the required copyright and permission notice.

## Local adaptation boundary

ClipMaster mechanically imports the upstream Android resource and translation
baseline under the `torrent_search_` resource prefix and records the 43 built-in
Provider source files in `upstream-manifest.json`. The local changes are limited
to toolchain compatibility, security and privacy hardening, Hilt/host integration,
and resource namespacing.

Provider domain entries are extracted only from executable URL constants,
interface fields and detail-download selectors in the fixed Provider sources;
attribution comments are not network facts. For example, `1337x` permits only
`1337x.to` and `itorrents.org`, never the GitHub URL appearing in a source comment.

All Android resource IDs and file names use the `torrent_search_` prefix except
the AGP-recognized root metadata file `src/main/res/resources.properties`. That
file intentionally keeps its fixed name, contains `unqualifiedResLocale=en`, and
does not generate an `R` resource ID.

One resource compatibility adjustment is intentional: the API 31 upstream
`Theme.SplashScreen` / `postSplashScreenTheme` relationship is replaced with the
platform no-action-bar parent because this resource-only phase neither imports
the SplashScreen runtime nor adopts the upstream launcher. The prefixed theme
names remain available for later host-owned Activity work.

The first reviewed runtime batch ports AniLibria, AnimeTosho, and AniRena from
the pinned source files recorded in the manifest. These implementations are
adapted to ClipMaster's typed Provider contracts, Hilt-owned shared network
boundary, HTTPS/host allowlists, deterministic epoch-millisecond parsers, and
synthetic fixtures. AniRena follows the local manifest's NSFW/default-disabled
audit result even though the pinned upstream class labels it safe.

The magnet builder also retains the 20 public tracker values and their order
from the pinned upstream `TorrentUtils.kt`. ClipMaster URL-encodes each tracker
query value and does not contact the comment-linked tracker-list service at
runtime; future tracker changes require a reviewed application update.

The fourth runtime batch ports Btsow, Dmhy, and Ext from the same pinned
sources. Local changes separate request orchestration from static JSON/HTML
parsing, validate hashes and exact HTTPS origins, use injected parsing
dispatchers and clocks, and bound Ext page fields before form submission.
Ext retains its request HMAC protocol but follows the local NSFW/default-off
audit. Its description request uses an explicit fixed AJAX header through
the shared client. Provider descriptions remain untrusted HTML for the
later script-disabled presentation layer. Synthetic fixtures are local
test data and do not contact upstream services.

The integration does not adopt Koin, `TorrentSearchApplication`, `MainActivity`,
upstream launcher declarations, global exception handling, or global Coil
configuration. The host application remains responsible for its own
`Application`, activity lifecycle, dependency injection, launcher, exception
handling, and image-loading setup.

The manifest is a fixed local audit artifact. It permits no remote delivery of
code, selectors, or scripts; future Provider runtime work must remain pinned to
this reviewed source baseline and local HTTPS-only network policy.

The fifth runtime batch ports Eztv, FileMood, and InternetArchive from the same
pinned sources and verified manifest hashes. Local adaptations isolate parsing
from networking, encode query/path data structurally, validate exact HTTPS
hosts and hashes, and reuse cancellable mapping and UTC/Long value parsers.
Eztv retains its fixed public layout Cookie through the shared text client;
this is a presentation option, not a user session. FileMood safely reads
nonstandard peer counts and derives identity only from a validated .html path.
InternetArchive follows the local NSFW/default-off audit, bounds identifiers
and exact metadata routes, skips invalid file candidates, and intentionally
fixes the upstream metadata.metadata category typo to metadata.mediatype.
Fixtures are synthetic and offline. No registry, UI, persistence, or backup
behavior is added in this batch.
