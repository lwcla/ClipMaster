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

The integration does not adopt Koin, `TorrentSearchApplication`, `MainActivity`,
upstream launcher declarations, global exception handling, or global Coil
configuration. The host application remains responsible for its own
`Application`, activity lifecycle, dependency injection, launcher, exception
handling, and image-loading setup.

The manifest is a fixed local audit artifact. It permits no remote delivery of
code, selectors, or scripts; future Provider runtime work must remain pinned to
this reviewed source baseline and local HTTPS-only network policy.
