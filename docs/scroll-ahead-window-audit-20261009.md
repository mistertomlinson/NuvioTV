# Ahead-row preparation experiment — October 9, 2026

Baseline: 333e74305 (glass-noise patch). Candidate changes only vertical cache aheadFraction 0.5 to 1.0; behindFraction remains 0.25. This is separate from the rejected row content-type and trailing-retention experiments. The earlier July 26 experiment 17a6ad687 changed ahead 1.0 to 1.5 with behind 0.5; it is not this candidate. September 17 commit 543df2c90 changed multiple navigation features and cache values together, so its history cannot isolate the cache effect.

Use fresh baseline AOT → force-stop → launch → explicitly Brandon, one warm-up plus two measured runs, followed by equivalent candidate setup. The exact three-row sequence and prior 16-press capture method remain in use. Controls ahead-base-2/3 are measured, ahead-base-1 is excluded warm-up.

Source/trace investigation: several large idle-prefetch bursts build nine card compositions in one pass. Baseline example: 50.82 ms wall / 37.35 ms main-thread CPU, with nested prefetch spanning 41.74 ms. Patched glass example: 47.70 ms wall / 39.54 ms CPU with nested prefetch 42.86 ms. Trace child durations are nested and must not be added together.

A separate 75.72 ms layout stall starts 162 ms after the first key, while removing the previously playing trailer. Main-thread CPU accounts for 33.78 ms, with 20.36 ms in Compose:onForgotten; video loading and MediaCodec threads consume another 43.55/33.34 ms across the device's cores. This supports investigating trailer disposal and CPU contention separately; it does not prove one release call accounts for all waiting or every mixed-row hitch. No trailer behavior is changed in this candidate.

Build: incremental release/R8/vital lint/assembly passed in isolated temporary output (13 executed tasks, 46 up-to-date); no clean.

## Initial matched warm results

| Run | UI-thread CPU | Layout total / max | Idle prefetch total / max | App-deadline misses |
| --- | --- | --- | --- | --- |
| ahead-base-2 | 7571.6 ms | 957.83 / 50.38 ms | 161.30 / 45.11 ms | 128/849 (15.08%) |
| ahead-base-3 | 7586.9 ms | 897.61 / 40.61 ms | 197.32 / 45.93 ms | 137/842 (16.27%) |
| ahead-candidate-2 | 8065.4 ms | 849.82 / 83.35 ms | 487.61 / 61.28 ms | 120/834 (14.39%) |
| ahead-candidate-3 | 8073.9 ms | 845.67 / 75.30 ms | 435.71 / 53.24 ms | 117/838 (13.96%) |

Pooled app-deadline rate: 15.67% control, 14.17% candidate (1.49 percentage points / 9.5% relative reduction). UI CPU +6.5%; layout total -8.6%; idle-prefetch duration grew from 179.31 to 461.66 ms. Whole-capture gfxinfo jank remains approximately 35% for both, and p99 does not improve. Two samples are insufficient to claim a small aggregate benefit is a repeatable visible-scroll improvement. A return-to-baseline control follows before any retention decision.

Candidate APK SHA-256: 6afa94f77d869251b7c1cb681d3fd29af13e37c38d5612cac31b712cb7a4541a. This experimental APK is separate from the verified glass-patch artifact.

## Transition-specific initial comparison
Each transition is repeated four times in each measured run. Windows run from key-down until the next key-down (last window ends one second later); thus a last-frame count can differ slightly from the main focused analysis which ends after key-up.

| Transition | Initial control misses / frames | Candidate misses / frames |
| --- | --- | --- |
| Trending Movies → Movies Coming Soon | 85/404 (21.04%) | 72/370 (19.46%) |
| Movies Coming Soon → Night of Terror | 62/420 (14.76%) | 48/438 (10.96%) |
| Night of Terror → Movies Coming Soon | 62/441 (14.06%) | 69/422 (16.35%) |
| Movies Coming Soon → Trending Movies | 56/424 (13.21%) | 48/440 (10.91%) |

The aggregate result masks differences by direction. Both landscape-to-portrait steps improve in the initial samples, but the reverse portrait-to-landscape step gets worse. These counts describe app deadlines, not a direct video-rated perception of a single visible hitch. Return-to-control results are still required before concluding that any change is worth the CPU and prefetch cost.

## Return-to-control results and decision

| Repeated control | UI-thread CPU | Layout total / max | Idle prefetch total / max | App-deadline misses |
| --- | --- | --- | --- | --- |
| ahead-repeat-2 | 7557.4 ms | 904.12 / 50.87 ms | 202.14 / 44.11 ms | 122/849 (14.37%) |
| ahead-repeat-3 | 7535.2 ms | 947.76 / 39.58 ms | 150.06 / 43.32 ms | 129/848 (15.21%) |

Repeated-control pooled app-deadline rate is 14.79%, versus candidate 14.17% (only 0.61 percentage points / 4.1% relative difference). The original control-to-candidate difference therefore shrank substantially on restart. Whole-capture gfxinfo jank is 34.50%/34.01% for repeated control, versus 35.21%/34.92% candidate; p99 is 85 ms throughout. The candidate's greater UI CPU cost is repeatable: about 8,070 ms versus repeated control 7,546 ms (+6.9%). Idle-prefetch duration is about 462 versus 176 ms. Layout totals shift work earlier, but long stalls remain.

Decision: **reject the larger ahead window**. The small app-deadline difference is insufficient to justify its CPU/preparation cost and the lack of a corresponding overall graphics-jank improvement. The source is restored to ahead 0.5 / behind 0.25; row content-type and trailing-retention experiments remain absent. The TV is restored to the verified 333e74305 glass-patch APK, with AOT followed by force-stop and explicitly selecting Brandon. Current local.properties and local.dev.properties still byte-match the primary checkout.

One memory snapshot each showed candidate PSS 379,515 KB / Java heap 221,924 KB / graphics 72,488 KB versus repeated control 361,245 / 216,740 / 69,572 KB. These single snapshots, with different swap levels, are not a controlled memory-cost estimate. They do not establish a leak or a precise cache cost.

No trailer lifecycle, buffering, fades, expanded-card, popup, sidebar, watched-state, or player-pool changes were made. The official beta uses a trailer pool while Enhanced has a shared hero player plus independently owned card players. Any future player work must preserve Enhanced's audio cleanup and popup/sidebar handoffs; the trace does not justify moving ExoPlayer calls onto an arbitrary background thread or deleting those cleanup steps.

Remaining evidence: meaningful work occurs in row/card composition and nested prefetch, and at least one long first-step stall combines trailer disposal with codec/loading CPU contention. Poster orientation by itself is not established as the sole cause. The retained noise patch avoids redundant image decoding but has not shown a reliable overall smoothness gain.
