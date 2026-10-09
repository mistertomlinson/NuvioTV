# Ordinary Home scrolling: retained prefetch change — October 9, 2026

## Retained change
ModernHomeContent now uses standard rememberLazyListState prefetch for the vertical Home list instead of Enhanced's explicit ahead 0.5 / behind 0.25 cache window. This matches the official beta 0.9.5 parent list (local reference nuvio-official/0.9.5-beta, 62e1d8b16). The official inner rows explicitly use nestedPrefetchItemCount 2; Enhanced's default inner LazyListState already uses the same initial count in Foundation 1.10.2, so no inner-row change is warranted.

The change leaves Enhanced's renderer, mixed orientations, numbered cards, card depth, badges, saved indices/offsets, D-pad handlers, focus restoration, pagination, platform pages, launch staging and trailer/popup/sidebar behaviors intact. It does not change disk caching. The larger ahead/behind-window experiments remain rejected and absent. The user observed a significant visible scrolling improvement, including blurred rows, and explicitly requested retaining this change.

## Test scope and exclusions
Testing used com.nuvio.tv.sideload on the onn 4K Pro, Android 34, 1920×1080/60 Hz. Baseline application source is 333e74305; documentation HEAD at build start was f34a47f4b. Both builds used AOT speed compilation followed by force-stop and explicit selection of Brandon. Current local.properties and local.dev.properties byte-match the primary dev checkout; no values were printed or committed.

Vertical scrolling is the priority. The user clarified that ordinary scrolling before trailer playback is the problem, and asked to exclude blur-badge rows and their neighbors. The first proposed mixed movie-poster route was abandoned. The initial row-12-to-20 captures ordinary-base-1/2/3 cross Series Coming Soon and are excluded entirely.

The verified measured range is:
12. Dystopian Worlds Colliding
13. Suspenseful Cosmic Encounters
14. Vengeance and Crime
15. Psychological Mind Games
16. Race to Save Humanity

Series Coming Soon is row 19; its neighboring rows 18 and 20 are excluded. The Movies Coming Soon section and its neighbors are also outside the measured range. Navigation needed to reach and leave the range is excluded from analysis.

Initial captures start by closing the sidebar, then use 24 single presses: four Down and four Up, repeated three times, approximately 0.9 seconds plus ADB delivery between presses. The user identified sidebar blur as a separate source of lag. The original tail included sidebar opening and is NOT used for the reported comparison. Reanalysis stops before the final navigation key begins, excluding the entire last step and sidebar transition. Both arms therefore use the same 23-press window, about 23.6–23.9 seconds, beginning roughly 0.9 seconds after closing the sidebar. First runs are warm-up; only runs 2/3 are compared below.

## Matched vertical results, sidebar opening excluded

| Run | Main CPU | Layout total / longest | Idle prefetch total / longest | App-deadline misses |
| --- | --- | --- | --- | --- |
| ordinary-safe-base-2 | 10,363.4 ms | 1,179.49 / 33.78 ms | 38.46 / 38.46 ms | 85/1278 (6.65%) |
| ordinary-safe-base-3 | 10,385.4 ms | 1,183.35 / 37.81 ms | 51.14 / 51.14 ms | 85/1295 (6.56%) |
| ordinary-safe-default-2 | 10,608.8 ms | 942.64 / 9.19 ms | 219.63 / 40.52 ms | 59/1299 (4.54%) |
| ordinary-safe-default-3 | 10,618.9 ms | 940.37 / 11.80 ms | 197.02 / 56.62 ms | 64/1310 (4.89%) |

Pooled app-deadline rate falls from 6.61% to 4.71% (1.89 percentage points, 28.6% relative). Layout total falls about 20.3%; total main CPU grows about 2.3% over nearly equal windows. More composition occurs in prefetch rather than measure/layout. Long prefetch bursts still exist; this is not elimination of all stalls. App-deadline misses are distinct from total graphics jank and subjective smoothness.

A control layout example without player activity spends 41.53 ms wall / 36.78 ms main CPU composing eight posters, applying changes and starting image painters. Descendant durations overlap and must not be added together. This supports targeting row preparation without attributing these stalls to video teardown.

## Additional captures with no sidebar use
The long clean-default-1/2 captures overflowed the 128 MiB trace buffer, lost initial process metadata and key events, and are excluded. This is a profiling capture problem, not an application regression.

Replacement clean-short-default-1/2 captures navigate to row 12 before starting the trace, immediately begin the same 24 safe-row presses, and return to Continue Watching after the measurement window. They never open the sidebar. Their 36-second traces retain process metadata and all 72 key dispatch slices. Only the first 48 dispatch slices (24 presses) are measured; the return path is excluded.

| No-sidebar run | Main CPU | Layout total / longest | Idle prefetch total / longest | App-deadline misses |
| --- | --- | --- | --- | --- |
| clean-short-default-1 | 10,742.5 ms | 1,239.59 / 34.80 ms | 126.73 / 64.19 ms | 76/1357 (5.60%) |
| clean-short-default-2 | 10,782.7 ms | 1,211.92 / 22.25 ms | 69.83 / 36.88 ms | 63/1343 (4.69%) |

These confirm that some hitches remain and the largest-layout benefit varies with preparation context. They are supplemental candidate runs, not a matched no-sidebar baseline comparison, and must not be substituted into the earlier percentage calculation. No application ExoPlayer, codec or AudioTrack thread CPU appears in any reported active window. Pre-play trailer resolution/background work is still permitted; absence of player work does not imply absence of all background activity.

## Horizontal checks
Eight Right / eight Left were checked on row 12, with navigation setup and sidebar transitions excluded from reported windows. The matched analysis excludes the final Left, yielding 15 presses per run. Control runs 2/3 have 56/445 (12.58%) and 61/445 (13.71%) app-deadline misses. The new no-sidebar candidate's first horizontal pass is treated as warm-up: 67/428 (15.65%), with substantial background dispatch work. Its second pass is 55/436 (12.61%), main CPU 5,417.2 ms and longest layout 11.38 ms. Controls average 5,497.4 ms main CPU and longest layouts 10.18/10.46 ms. This does not establish a separate horizontal smoothness gain. No horizontal implementation change is made.

## Validation and retained artifact
Incremental isolated-output assembleSideload passed release R8, vital lint and packaging: 14 executed / 45 up-to-date tasks, 11m22s; no clean. All 253 unit tests passed with zero failures/errors/skips. Incremental test build passed in 22 seconds, 5 executed / 29 up-to-date tasks.

On-device navigation and exact focus/scroll restoration were checked by moving to Equilibrium at index 3 in Dystopian Worlds Colliding, opening Details and returning with Back. The same selected poster and horizontal offset were restored. Repeated vertical and horizontal routes complete without focus loss. No playback, watched-state changes, profile edits, preference changes, data clearing or official-package modifications were performed. These focused checks do not exhaust every custom feature; the production diff changes only vertical list prefetch.

Retained APK: Nuvio-Enhanced-scroll-prefetch-20261009-universal.apk
SHA-256: bfd857fb8bf1c3f283292f487690c26cfc357b751d2f8efc779f5ea244c8f1a2
Package/version: com.nuvio.tv.sideload, 38 / 0.4.20-beta.
APK signing certificate SHA-256: ab0f234a8bfacc174676e84c7623c7f77efeacff7f85f7ec13c5af5d4e809e9c.
Installed APK hash and full AOT speed status are verified. The primary dev checkout remains unchanged. Sidebar blur and Coming Soon blur remain separate investigation targets; this change does not claim to fix their effect costs.
