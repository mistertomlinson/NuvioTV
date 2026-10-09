# Mixed-row scrolling investigation — October 9, 2026

Work remains on codex/scroll-jank-audit-20261007. Baseline is 4b6172c82. Primary dev remains untouched at 99205ec70.

## Reproduction
Brandon, Home: Trending Movies (portrait) → Movies Coming Soon (landscape) → Night of Terror (portrait), then reverse. Each trace repeats Down, Down, Up, Up four times with 1.2 seconds between commands plus ADB delivery time. Start/end on the first Trending Movies card, verified visually. Preserve the existing date badge, artwork, autoplay, accessibility service, and settings. AOT speed compilation is always followed by force-stop, launch, and explicitly selecting Brandon.

The original row-base-1/2/3 runs started on the platform strip, not the first Continue Watching card. They include platform switching and are excluded from comparisons. mixed-base-1 overflowed its 64 MB trace buffer, so CPU results are unavailable; later captures use 128 MB. Whole-trace graphics counters include an idle tail where autoplay can begin; focused analysis uses the first input through one second after the final input instead. FrameTimeline app-deadline results and gfxinfo jank are distinct measurements and must not be interchanged.

## History
- 5a63a9c70 (September 10): launch/catalog readiness work.
- 543df2c90 (September 17), merged through 9f4c53f6f: Simplify and optimize Enhanced Home navigation. Uses the default BringIntoView spring, removes custom landing corrections/lightweight renderers, and reduces vertical ahead/behind cache from 1.0/0.5 to 0.5/0.25. The current baseline retains that motion and cache setting.
- 0d078da4a (September 27): landscape trailer expansion; height follows the existing width clock and is gated on trailer first frame. No proof this feature caused ordinary single-step jank.
- d84cfe333 (September 28): Continue Watching card styles.
- 0c0f75492 (October 2): Coming Soon hero glass status pill. This adds a Haze capture while the pill appears and fades out. In pinned Haze 0.7.3, each animated noiseFactor becomes a float cache key, and a miss decodes the noise drawable and creates an alpha-adjusted bitmap. A warm focused baseline recorded 140–143 main-thread decodeBitmap slices during 16 moves. This is concrete extra work, but does not establish that blur or poster shape alone accounts for the entire hitch.
- Later commits add badges, double-Up, watched-state and focus fixes. These features are preserved.

## Source-backed hypotheses
Compose Foundation 1.10.2 shares prefetch timing and learned nested-item counts by the parent row content type. Baseline labels all Home rows modern_home_row despite different card widths and numbering. The row-type candidate assigns finite types for CW, portrait, landscape, numbered portrait, and numbered landscape. Row keys and loading-to-content identity are unchanged.

The second candidate changes only the trailing cache fraction from 0.25 to 1.0 in addition to the row types. The ahead fraction stays 0.5. Compose CacheWindowLogic.onKeepAround retains only previously measured rows and does not schedule new preparation for the trailing window. This trades a bounded increase in retained compositions for less rebuilding on direction reversal; device measurement is required before retaining it.

## Restart control warning
Reinstalling the unchanged 4b6172c82 baseline produced mixed-repeat-1 with 873 ms of focused-window layout, versus 1909/1930 ms in the original baseline warm runs. The row-type-only candidate produced 994/985 ms. Therefore the original apparent layout reduction is not attributable to row typing. The repeated baseline was interrupted before additional warm samples; final comparisons must use another matched restart. No claim of demonstrated improvement from row types alone is warranted.

## October 9 matched control and rejected experiments
Fresh AOT → force-stop → launch → Brandon baseline runs used the original 4b6172c82 APK. Active windows cover roughly 21 seconds, 16 presses, starting and ending on the first Trending Movies card. Original settings remained in effect. Neither row typing nor the larger trailing cache is retained in the final source.

| Warm run | UI-thread CPU in active window | Largest layout / idle-prefetch slice | FrameTimeline app-deadline misses |
| --- | --- | --- | --- |
| Baseline 2 | 7,361 ms | 38.38 / 50.82 ms | 137 / 831 (16.49%) |
| Baseline 3 | 7,514 ms | 48.89 / 41.57 ms | 130 / 838 (15.51%) |
| Row types + retention 2 | 7,503 ms | 43.66 / 42.11 ms | 140 / 825 (16.97%) |
| Row types + retention 3 | 7,570 ms | 65.12 / 64.40 ms | 130 / 839 (15.49%) |

The retention experiment did not reliably improve missed frames or large stalls, so both experimental changes were removed. Memory snapshots were 375,810 KB PSS baseline and 363,380 KB candidate, illustrating that one process snapshot is too noisy to establish the extra cache's memory cost. No memory-saving claim is made. The experiment patch is preserved separately for review; it is not part of the retained implementation.

## Pixel-equivalent glass noise candidate
The original fade supplies noiseFactor = 0.025 × clamped visibility. Haze 0.7.3 disables noise below 0.005; otherwise it uses Paint.alpha = round(noiseFactor × 255), and caches the resulting bitmap by the unrounded Float. The new helper canonicalizes factors that produce the same integer alpha. It retains the 0.005 cutoff before quantization and represents alpha 1 with 0.005 rather than 1/255, which would accidentally disable noise in Haze. There are at most seven factors including disabled noise. Blur radius, fade timing, text transitions, geometry, colors, popup capture, mixed poster settings and navigation are unchanged.

Unit tests compare rendered alpha against the original formula across 10,001 fade positions, check cutoff/endpoints and assert that equivalent rendered opacities share a key. This is coupled intentionally to pinned Haze 0.7.3 behavior and should be reviewed on a library upgrade. Source reference: https://repo.maven.apache.org/maven2/dev/chrisbanes/haze/haze-android/0.7.3/haze-android-0.7.3-sources.jar (AndroidHazeNode.kt, getNoiseTexture/withAlpha/withNoise).

## Build environment repair
Additional unit validation found two identical duplicate generated Kotlin files and ten identical duplicate generated Java files with ' 2' appended to their filenames. Only those verified duplicates were moved to a separate local quarantine directory. No clean, global cache deletion, application source replacement, or configuration change was needed. The cache experiment's unit validation then succeeded.

The duplicate-output problem subsequently affected 1,917 extra ' 2.class' files and 126 ' 3.class' files, including stale bytecode, and further duplicate generated Java files appeared during builds. Packaging correctly rejected duplicate class definitions. Temporary build output and Gradle project-cache state were therefore redirected to /private/tmp/nuvio-scroll-glass-20261009 via an external Gradle init script. The source checkout, current local.properties, signing configuration, dependencies, and primary dev remain unchanged; no clean or global cache reset was used. This is a build-environment workaround, not an application-code fix.

## Release validation
The isolated-output release build completed successfully: 253 unit tests, zero failures/errors/skips; release R8, vital lint and APK assembly passed. Existing nullable-receiver warnings in EnrichmentDiskCacheTest remain. Both local.properties and local.dev.properties byte-match the primary checkout, verified without exposing their contents. Package com.nuvio.tv.sideload, version 38 / 0.4.20-beta; signing certificate SHA-256 ab0f234a8bfacc174676e84c7623c7f77efeacff7f85f7ec13c5af5d4e809e9c matches the preceding build.

Final APK: Nuvio-Enhanced-scroll-glass-20261009-universal.apk
SHA-256: 7a761b2ea56f3f43101786c713c32f9e46cbb7a441833deb83152b70c6840318

## Final glass-noise device results
The first glass run was warm-up and excluded. The two measured runs used the same 16 single D-pad presses and roughly 21-second active window as the October 9 controls.

| Warm run | Main-thread decode count / duration | UI-thread CPU | Largest layout slice | App-deadline misses |
| --- | --- | --- | --- | --- |
| Baseline 2 | 143 / 94.38 ms | 7,361.4 ms | 38.38 ms | 137/831 (16.49%) |
| Baseline 3 | 143 / 98.93 ms | 7,513.9 ms | 48.89 ms | 130/838 (15.51%) |
| Glass 2 | 63 / 41.70 ms | 7,464.6 ms | 75.72 ms | 139/828 (16.79%) |
| Glass 3 | 61 / 40.96 ms | 7,423.7 ms | 44.78 ms | 130/843 (15.42%) |

Average decode count fell from 143 to 62 (56.6%); summed decode duration fell from 96.66 to 41.33 ms (57.2%). Main-thread CPU was essentially unchanged (7,437.65 vs 7,444.15 ms). Pooled app-deadline misses were 16.00% baseline versus 16.10% patched. Whole-capture gfxinfo jank was 36.82%/34.95% baseline and 35.83%/35.67% patched; these include the idle tail and are not the same metric as app-deadline misses. Large layout stalls remain (including 75.72 ms in one patched run).

Conclusion: retain this narrow change because it demonstrably avoids redundant image work while preserving the rendered noise. These samples do **not** demonstrate an overall smoothness improvement or establish that the portrait/landscape hitch is fixed. The cache experiments remain reverted. Longer layout/prefetch stalls and rendering cost remain investigation targets; broad changes to mixed-row geometry, blur, launch, or autoplay are not justified by this result.

## Navigation and installation checks
Installed APK hash and full AOT speed status both verified. Force-stop was performed after compilation, then Brandon explicitly selected. Mixed portrait/landscape row navigation, horizontal Coming Soon navigation (including October 13 to October 20 badge text), details opening and Back restoring the selected card, and double-Up returning to the platform strip were checked. Left the TV on Brandon's Home, first Continue Watching card. No playback, watched-state actions, profile edits, app-data clearing, or settings changes were used. These focused checks cannot guarantee every custom feature, but the production diff is limited to the noise-factor calculation.
