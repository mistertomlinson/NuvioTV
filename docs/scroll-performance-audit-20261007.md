# Nuvio Enhanced scrolling audit

Starting point: `99205ec70` (Polish actor navigation focus restoration).
Branch: `codex/scroll-jank-audit-20261007`.
Working copy: `/Users/mac/Documents/Codex/2026-10-07/here/work/NuvioTV-scroll`.
Original checkout: `/Users/mac/Developer/NuvioTV_420`, left on `dev`.

## Comparison

Compared the custom implementation against official `0.9.5-beta` (`62e1d8b16`) and the fetched official `dev` commit `6adf0251bd93a0600802483d770c015ba9904ed3`, whose version is `1.1.0-beta.5`.

Official source: https://github.com/NuvioMedia/NuvioTV/tree/6adf0251bd93a0600802483d770c015ba9904ed3

The official TMDB services keep enrichment and ID mappings in memory. Enhanced adds persistent disk caches, which are useful for fast restoration but introduce additional serialization, allocations, and disk work. Official Modern Home limits directional image prefetching to one item in one row after a 240 ms debounce. Enhanced already has a similarly restrained directional poster prefetch path, but a separate backdrop prewarmer launched up to four full-size hero image requests immediately on every focus change.

These are source-level findings, not an on-device proof that any one path causes the observed hitch.

## Changes

- Coalesce IMDb mapping saves instead of launching a full-cache save for every lookup. Reuse the same writer for TMDB enrichment. Save after two quiet seconds, with a 30-second bound during sustained updates. Keep requests arriving during a write and serialize subsequent writes; do not cancel a write in progress.
- Publish disk-restoration readiness only after restored entries have reached the memory cache. Concurrent lookups wait for the same restoration, avoiding unnecessary network requests during startup. A cancelled restoration can be retried. Preserve mappings learned while the disk read was running.
- Retain the last parsed, bounded disk snapshot for Home enrichment, TMDB enrichment, and IMDb mappings. Avoid reparsing the whole JSON document on each save and avoid parsing Home's same document twice to restore artwork and rating state.
- Skip unchanged writes and serialize directly to a buffered writer rather than allocating a full JSON string. Replace the file only after serialization succeeds. If replacement fails, retain the previous complete cache file.
- Retain existing file paths, expiry periods, entry caps, unchanged-entry timestamps, external-rating settlement (including a deliberately settled null rating), and focused-title repair semantics.
- Stabilize the IMDb mapping JSON fields across minified builds. Accept both descriptive field names and the legacy `a`/`b` names verified in the existing release mapping file. No cache wipe is required.
- Wait for 240 ms of idle navigation before speculative neighbor-backdrop loads. Run them sequentially and cancel pending work when focus changes or either scroll axis starts. Preserve the actual hero renderer and cold-start hero preload.
- Apply the same idle/cancellation policy to platform-backdrop warmup, observe current catalog data, and remember successful URLs so scrolling does not repeatedly warm every platform.

## Custom features preserved by scope

No changes to playback, credit analysis, post-play behavior, Simkl/Trakt synchronization, watched-state rules, release reminders, catalog ordering, platform selection, badges, expanded cards, trailer settings, double-Up navigation, or the recent actor/Home focus-restoration logic. Existing regression coverage for these areas is run alongside new cache tests; that is not a substitute for checking every feature on a TV.

There is no blanket upstream merge, Compose/Coil upgrade, cache deletion, package-ID change, or change to the scroll animation spec. The disk snapshot adds a bounded map of references and can temporarily retain older enrichment objects until the next save, in exchange for avoiding repeated JSON parsing and allocation. Unsaved enrichment may be refetched if the app is killed during the batching window; watch history/progress storage is not part of this change.

## Validation

Baseline: all 234 existing unit tests passed.

Final: **250 tests passed, zero failures and zero errors**, including 16 new tests. The minified sideload build and release lint checks succeeded. The new tests cover save coalescing, continuous-update deadlines, in-flight save ordering, JSON round trips, unchanged writes, failed-write recovery, disk deletion, Home rating/repair preservation, entry limits, expiry, legacy minified fields, concurrent restoration, and cancellation/retry.

Build command used (Android Studio's bundled Java runtime):

```sh
./gradlew :app:testSideloadUnitTest :app:assembleSideload --offline -Pkotlin.compiler.execution.strategy=in-process --no-watch-fs
```

The exported universal APK is signed, uses `com.nuvio.tv.sideload`, and matches the existing build's signing certificate. R8 output confirms that the IMDb cache entry class remains preserved. `git diff --check` passed. The original checkout is clean on `dev` at the starting commit. Nothing was pushed or installed.

## TV comparison still needed

No application was installed or controlled on either connected TV. Device/layout selection was not provided during this pass, so no frame-time improvement is claimed.

Use the same device, catalogs, layout, trailer settings, thermal state, and compilation mode for the original and candidate builds. Compare both a warmed session and a fresh launch while enrichment is still running. Test isolated Up/Down presses, held vertical movement, rapid Left/Right movement, and both ends of long rows. Also check platform switching, double-Up return, expanded/trailer cards, Details/actor back navigation, Continue Watching, watched badges, and profile switches. Compare frame timing as well as visual behavior; a desktop build passing cannot establish smoothness on the TV.
