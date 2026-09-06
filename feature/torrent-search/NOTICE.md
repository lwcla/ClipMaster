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

The sixth runtime batch ports Knaben, LimeTorrents, and LinuxTracker from the
same pinned sources and verified manifest hashes. Provider entry points retain
network orchestration while dedicated URL, category, JSON, and HTML parsers run
on injected computation dispatchers. Knaben follows the local NSFW/default-off
audit and submits explicit JSON fields. LimeTorrents treats its HTTP iTorrents
link only as an offline hash marker, then rebuilds a query-free HTTPS download
URL; no plaintext request is made. LinuxTracker preserves its nested-table
selectors and performs independent latest/top requests. All emitted web links
use exact reviewed hosts, HTTPS default ports, and no user information. Invalid
optional links degrade locally, fixtures are synthetic and offline, and this
batch adds no registry, UI, persistence, backup, or reflection behavior.

The seventh runtime batch ports MegaPeer, Mikan, and MyPornClub from the same
pinned sources and verified manifest hashes. Local adaptations keep network
requests in the caller coroutine, parse static HTML on injected computation
dispatchers, and validate every emitted or requested URL against exact HTTPS
hosts and default ports. MegaPeer and MyPornClub parse list drafts before
starting structured concurrent detail requests, preserve list order, and
propagate cancellation and transport failures. MegaPeer fixes two-digit years
to the explicit 2000..2099 range before strict UTC parsing. Mikan keeps its
Chinese metadata labels and anime category. MyPornClub follows the local
NSFW/default-disabled audit, and its synthetic fixtures contain neutral text
only. This batch adds no registry, UI, persistence, backup, reflection, or
runtime remote-code behavior.

The seventh-batch offline tests additionally cover all reviewed MegaPeer
Russian month tokens and category IDs, reject unsafe optional media links,
and exercise empty, malformed, missing-required-field, and unsafe-output-link
cases for Mikan and MyPornClub. These fixtures remain synthetic and perform no
requests to the upstream services.

The shared built-in Provider URL policy also rejects user information and
non-443 effective ports before a request is created. This authority boundary
applies to direct URLs, validated bases, and resolved relative or absolute
candidates while preserving default and explicit port 443. Provider-local
guards remain in place where already present, and the general network client
retains its separate same-origin port contract.

The eighth runtime batch ports NekoBT, Nyaa, and Sukebei from the same pinned
sources and verified manifest hashes. NekoBT retains its reviewed HTML endpoint
and dedicated parsers. Nyaa and Sukebei share only their static table and detail
card parsing skeleton while preserving separate request builders, exact-host
policies, categories, and safety metadata. Nyaa accepts only its reviewed mirror;
Sukebei remains NSFW, default-disabled, and fixed to the pornography category.
All parsing runs on injected computation dispatchers, cancellation propagates,
and optional unsafe URLs degrade without discarding otherwise valid results.
Fixtures use neutral synthetic content and no network. This batch adds no
registry, UI, persistence, backup, reflection, or runtime remote-code behavior.
