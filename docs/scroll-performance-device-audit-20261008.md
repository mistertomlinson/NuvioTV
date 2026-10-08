# Modern Home device profiling — 8 October 2026

Baseline APK: commit `5f6c07cdbee45cb43bb22591e3f0a901f2136656` from the first cache/prefetch pass. The user verified that APK on the device and reported no obvious subjective improvement. The earlier cache changes are retained; these measurements compare against that first pass, not against original `dev` or an installed official app.

Device: onn 4K Pro, Android 14/API 34, 1920 × 1080, 60 Hz. Profile: **Brandon**, confirmed in the app sidebar before the final matched runs. Modern Home uses the full-width platform row, landscape Continue Watching cards and portrait catalog cards. The existing Projectivy accessibility service stayed enabled. Only `com.nuvio.tv.sideload` was controlled. Both builds use full `speed` AOT compilation. The final matched comparison additionally force-stops Enhanced after AOT and relaunches it before warm-up, as requested by the user. Initial exploratory runs did not explicitly force-stop after AOT. No application data or image caches were cleared.

## Findings and changes

1. The previous `EnhancedModernHomeRowsListBoundary` was a local composable function. Despite its name and stable inputs, the compiler did not mark it restartable or skippable. It now lives at file scope, with the remaining captured state handles passed explicitly and three unused parameters removed. The new compiler report confirms `restartable skippable`. A whitespace-normalized comparison verifies that the entire row navigation body is unchanged except for passing the same Continue Watching card-style value explicitly. Double-Up, fast-scroll landing, focus restoration, row keys, expansion, trailers and card sizing keep their existing logic.
2. Loaded catalog rows previously owned an unconditional infinite poster-shimmer clock. A 10-second idle trace showed 600 Compose animation callbacks despite no rendered artwork changes. Clocks now exist only inside actual poster placeholders. Full skeleton rows still share one clock; a real poster owns a clock only until its image succeeds (or while an image remains unavailable). Placeholder appearance, image requests and error fallback stay intact. Official `dev` also creates fallback shimmer clocks conditionally inside loading placeholders.

This is a targeted change to the existing custom implementation, not a port of official Home or a dependency upgrade. Current primary-checkout `local.properties` and `local.dev.properties` were copied only if necessary and verified equal without exposing their contents.

## Measurement method

Each 30-second system trace records six single Down presses, six Up presses, three Right presses and three Left presses, starting and ending at the first Continue Watching card. There is a 1.2-second pause before each press, plus ADB delivery time. Graphics counters are reset before each run. The first baseline run includes image decoding; subsequent runs are warm. Perfetto data stays on this Mac. The trace includes scheduling, Compose/Android tracing, graphics and frame timelines; no debug-build instrumentation was added to the APK.

The original three runs reported 76/835 (9.10%), 71/845 (8.40%), and 95/820 (11.59%) missed-deadline frames. P95 total frame times were 46, 40 and 48 ms; P99 values were 69, 53 and 73 ms. Warm traces still contained layout, accessibility-tree and drawing work, with very little background dispatcher activity. This makes disk cache work an insufficient explanation for the warmed scrolling hitch by itself.

The device's frame timeline also reports substantial buffer stuffing. Android's total frame latency is not the same as time spent executing UI code. The modern missed-deadline counter is used here; the much larger legacy jank counter is not substituted for it. These short runs are diagnostic samples, not a controlled lab benchmark or proof that all custom features have been exercised.

## Initial candidate measurements

The first candidate contains the composition-boundary and shimmer fixes. After a newly started warm-up run (161/899 missed-deadline frames, P95 61 ms), the repeated warm samples were:

| Sample | Missed-deadline frames | P95 / P99 total latency | Main-thread CPU over trace | Layout slice total |
| --- | --- | --- | --- | --- |
| Baseline warm 2 | 71/845 (8.40%) | 40 / 53 ms | 8,282 ms | 1,445 ms |
| Baseline warm 3 | 95/820 (11.59%) | 48 / 73 ms | 8,107 ms | 1,439 ms |
| Candidate warm 2 | 85/830 (10.24%) | 46 / 61 ms | 6,666 ms | 689 ms |
| Candidate warm 3 | 81/837 (9.68%) | 42 / 57 ms | 6,649 ms | 716 ms |

These initial samples show about 19% less main-thread CPU and 51% less accumulated layout-slice time. **The layout reduction did not survive the fresh-baseline check below and must not be attributed to this patch.** They do **not** show a meaningful missed-deadline-rate improvement: the combined warm rates are both approximately 10%. The largest individual layout slices also remain around 40 ms. RenderThread CPU stayed around 7.75 seconds per trace, so reduced UI-thread work alone does not eliminate the remaining drawing/presentation cost.

A separate 10-second stationary Home trace confirms **zero** Compose animation callbacks on the candidate versus **600** on the baseline. The candidate process was present in the trace and Home remained visible. No clock remains merely because a fully loaded catalog row is composed.

The first candidate warm-up filled the trace buffer and lost process-name metadata; its graphics counter result is retained above, but it is excluded from CPU comparisons. Baseline and initial candidate measurements were separated by a tool-availability interruption, so these are observational comparisons rather than a tightly controlled randomized experiment.

## Additional row setup reduction

Ordinary and Continue Watching rows used to create a text measurer and measure four ranking-number strings, although their numbering was off. The final candidate creates the measurer only for numbered rows, preserves the exact `1`, `88` and `888` widths used for their leading padding and number slots, and removes the unused `8` measurement. No card-number font, overlap, spacing or alignment formula changes. Changing number mode recreates the relevant measurements.

## Matched restart comparison

For the final comparison, the original `5f6c07cdb` APK was reinstalled, AOT-compiled with `speed`, explicitly force-stopped, and relaunched. Brandon was selected by the user and then verified in the sidebar. One full traversal warmed the rows before two recorded warm runs. The final candidate follows the same sequence, and its profile is independently verified before testing.

Fresh baseline warm runs: 78/830 missed-deadline frames (9.40%, P95 48 ms, P99 69 ms) and 90/825 (10.91%, P95 46 ms, P99 69 ms). Main-thread CPU was 7,701 and 7,721 ms. The fresh baseline's layout time was already much lower than the original session, demonstrating why the earlier apparent 51% reduction is not a sound causal claim. The repeated baseline idle trace still shows the unnecessary animation clock activity.


### Final matched results

| Warm sample | Missed-deadline frames | P95 / P99 latency | Main-thread CPU | RenderThread CPU |
| --- | --- | --- | --- | --- |
| Restarted baseline 2 | 78/830 (9.40%) | 48 / 69 ms | 7,701 ms | 7,852 ms |
| Restarted baseline 3 | 90/825 (10.91%) | 46 / 69 ms | 7,721 ms | 7,861 ms |
| Final candidate 2 | 85/832 (10.22%) | 48 / 69 ms | 6,708 ms | 7,954 ms |
| Final candidate 3 | 87/824 (10.56%) | 46 / 65 ms | 6,679 ms | 7,811 ms |

The final candidate used **13.2% less main-thread CPU** in these two warm samples. The missed-deadline rate was **10.15% versus 10.39%**, with essentially unchanged P95/P99 times and RenderThread CPU. The difference in missed frames is too small, and the sample too short, to establish either a scrolling improvement or a regression. **This pass removes real overhead but does not demonstrate that the visible hitch is fixed.**

Accumulated layout times were 697/689 ms for the restarted baseline and 796/695 ms for the final candidate. Thus, the earlier apparent layout reduction is explicitly rejected as a patch benefit. The source-level removal of unneeded ranking measurements remains valid, but no isolated frame-time gain is assigned to it or to the new composition boundary.

The matched idle traces recorded **585 versus zero** Compose animation callbacks over ten seconds. The baseline main thread used 673 ms of CPU while idle; the final candidate had no scheduled main-thread CPU in that trace. Background worker activity remained, as expected. The final warm-up run was 104/809 missed-deadline frames (12.86%, P95 57 ms, P99 89 ms); it is excluded from the warm aggregates, just like the baseline warm-up.

The changes are retained because they remove unnecessary work while preserving the custom implementation, not because the two-run sample proves visibly smoother scrolling. Remaining trace costs include drawing/presentation, accessibility-tree updates, and occasional long layout/prefetch work. The existing accessibility service and custom rendering were preserved.

## Feature and build checks

- All 250 unit tests passed after the final source changes; release compilation, R8, release lint and APK assembly succeeded. Existing unrelated warnings remain.
- Current primary-checkout `local.properties` and `local.dev.properties` match the isolated worktree. Generated configuration contains the credit-analyzer URL/token and Supabase/login fields; values were not printed or committed.
- The first candidate passed Home vertical/horizontal traversal, Netflix platform switching and return, double-Up from a deep row to the platform strip, a short synthetic held-Down/release followed by horizontal focus, and opening a My List title's Details and returning to the same focused card. One earlier baseline Details return landed on Continue Watching instead; this pass does not claim to fix that intermittent behavior.
- The final APK also passed platform switching, horizontal navigation from rank 1 through rank 10 in Netflix’s numbered row, preserved number spacing/overlap, and return to Home. Brandon was independently selected in the profile picker and verified again in the sidebar. The installed APK hash and AOT `speed` status both passed verification.
- These smoke checks do not cover every profile setting, actor-navigation path, trailer/expanded-card configuration, playback or credit-analysis flow. Those implementations were not changed. ADB's synthetic long press is not a substitute for testing sustained physical remote input.
- There is no dependency upgrade, scroll animation change, cache wipe, profile-data reset, or change to watched-state/sync/playback logic. The primary `dev` checkout remains at `99205ec70`.

## Deliverables and verification

Final APK: `Nuvio-Enhanced-scroll-ui-20261008-universal.apk`.

SHA-256: `627628a55c9ba83bebb542fd0ae4ffc4c7d02dde084867d559fecb3d84e3a830`.

Package: `com.nuvio.tv.sideload`, unchanged version code 38 and version name `0.4.20-beta`. The signing certificate matches the existing Enhanced build. The APK was built with the current local login and credit-analyzer configuration. The original first-pass APK remains available separately for rollback.

The supplied `verify-scroll-ui-build.sh` checks the installed APK hash and reports whether full AOT `speed` compilation is present. It does not install, stop, clear or modify the application. For future installation/testing, use the sequence **install → AOT speed compile → force-stop Enhanced → launch → explicitly select Brandon**.
