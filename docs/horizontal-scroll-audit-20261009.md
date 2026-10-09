# Held horizontal Home scrolling investigation

Baseline: Enhanced `dev` / `17e22dac5`, package `com.nuvio.tv.sideload`.
Reference: installed official `com.nuvio.tv`, version `1.1.0-beta.3` / version code 1066; source tag commit `9bf4ed1e34ca5912c4d09bf02aa125f7d5e467a9`.
Device: onn 4K Pro, Android 14, `192.168.50.83:5555`. Profile: Brandon in both apps.

## Evidence and retained candidate

The official Advanced screen was inspected without changing any toggles: Nuvio Focus Scrolling **on**, Fast Horizontal Navigation **off**, RGB565 image decoding **on**. The TV's input dispatcher reports `KeyRepeatTimeout: 400ms` and `KeyRepeatDelay: 50ms`.

Official Modern Home rows have no row-level throttle, but their parent `ModernHomeRowsList` installs `dpadVerticalFastScroll`, whose shared horizontal throttle defaults to **80 ms**. Enhanced's vertical helper leaves horizontal input to each row, where the loaded and skeleton Home rows instead use **100 ms**.

Both Nuvio Scroll implementations use a spring with damping ratio **0.95** and stiffness **180**. Both horizontal row alignment specifications place the focused poster at the leading row margin. Official's motion still follows focus; it does not run a separate constant-speed horizontal scroll motor. Compose's `UpdatableAnimationState` continues with the previous velocity as the target changes. The locally inspected Foundation 1.10.2 and 1.11.2 versions have identical `UpdatableAnimationState` source. This does not imply all framework code or rendering work is identical.

The throttle samples **dispatch uptime**, rather than the repeat's scheduled input timestamp. With input every 50 ms, a 100 ms cutoff is exactly on the two-repeat boundary. Small variations cause some second repeats to be rejected, yielding alternating approximately 100 and 150 ms focus steps. An 80 ms cutoff leaves room for dispatch variation and matches official's policy.

Candidate: only change `horizontalGateMs` from **100 to 80 ms** in the loaded and skeleton Modern Home LazyRows. Vertical gate remains 100 ms. Alignment, spring, poster sizing/expansion, badges, sidebar restore suppression, row keys, trailer ownership, My List behavior, platform pages, credit analyzer and login configuration are untouched.

An initial unthrottled candidate was compiled but **never installed or retained** after the full parent path was examined. The current candidate restores the shared throttle helper unchanged.

## Test method

A small shell-only Android input helper injects an actual held-key sequence: ACTION_DOWN, repeated ACTION_DOWN with increasing repeatCount after 400 ms, then ACTION_UP. It matches the device's 50 ms repeat configuration. Repeated individual `input keyevent` calls would not exercise the held-key throttle and were not used as a substitute.

Three matched baseline runs start from the same first card in **Dystopian Worlds Colliding**, outside blur-badge rows and their neighbors. Each trace contains a right hold and a left hold of 1.6 seconds, 25 down events plus one up per direction. Alignment to the first card and one preparatory right tap happen before tracing. Steady analysis starts 100 ms after the first repeated key and ends at key-up, excluding initial movement/trailer teardown and release/settle. No sidebar opening occurs inside measured windows. Video recording is separate from performance tracing.

Official's clean reference trace uses 0.9-second holds in Top Series for You, avoiding row-end focus escape. One earlier official run escaped into the app's update banner; it is excluded from performance comparison. The session's banner was dismissed through its close button; official package, compilation, saved toggles and account data were not modified.

## Results

| Test set (three runs / six steady hold windows each) | Focus gaps over 125 ms | Gap range | App deadline misses |
| --- | --- | --- | --- |
| Earlier matched baseline | 19 / 50 | 99.65–152.08 ms | 8 / 388 (2.06%) |
| Candidate initial | 1 / 57 | 84.18–127.70 ms | 34 / 352 (9.66%) |
| Candidate after warming full row | 0 / 58 | 88.06–108.53 ms | 18 / 380 (4.74%) |
| Baseline reinstalled and warmed full row | 21 / 48 | 100.41–155.30 ms | 13 / 380 (3.42%) |

The warmed candidate's median focus interval was 100.27 ms. It consistently made 13 focus requests in each 1.6-second hold, including initial movement. The returned baseline made 10–12, with repeated approximately 150 ms intervals. The clean official trace also showed roughly 100 ms intervals, but its different row and shorter holds make its rendering counters unsuitable for a direct numerical comparison.

The evidence supports a **more regular focus/scroll target cadence**, not a proven reduction in missed rendering deadlines. Even after warming, the candidate missed 18 of 380 deadlines versus 13 of 380 in the returned baseline: 4.74% versus 3.42%. Faster focus progression may add work; these short, non-randomized sessions cannot establish the cause or statistical significance. Initial candidate results were worse and are retained above rather than excluded. Physical-remote visual review is still needed before promotion.

For the returned baseline, only `horizontal-baseline-return2/3/4` are used. `return1` is excluded because the warm-up command had not been confirmed complete before recording began. The official update-banner run is likewise excluded. No sidebar measurements are included.

## Build and navigation validation

- Incremental sideload build succeeded; 253 unit tests passed with zero failures, errors or skips.
- Current primary `local.properties` and `local.dev.properties` were copied byte-for-byte without printing their contents. The current release keystore was copied locally and remains ignored; no secrets are included in the patch.
- Candidate APK SHA-256: `c70298657b6df39c5550939b3998580c855b15d7b6c38c81187fc3f80d38c17d`.
- Signing certificate SHA-256: `ab0f234a8bfacc174676e84c7623c7f77efeacff7f85f7ec13c5af5d4e809e9c`, matching the prior Enhanced release. An initial debug-key fallback was caught and corrected before installation.
- Installed candidate was hash-verified, compiled with `speed`, force-stopped **after** compilation, then launched with Brandon selected. The baseline return comparison used the same procedure.
- Long right hold reached the final poster; long left hold returned to the first poster without opening the sidebar during the hold.
- Detail/back returned to the same focused poster. Down/up restored the same row and item. Idle Home trailer resumed.
- Continue Watching landscape cards traversed right and back to the first card. A separate sidebar open/close restored focus to that card; this was outside every performance trace.
- Final restored candidate passed installed APK hash and full AOT speed verification. Current-process log inspection found no fatal exception or fatal signal entries.

The candidate is isolated on `codex/horizontal-scroll-audit-20261009`, based on `17e22dac5`; no merge or push is part of this task. Scope is two repeat thresholds plus their explanatory comment. Other custom features were not exhaustively retested; no changes were made to their implementations.
