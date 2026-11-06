# Per-catalog landscape exposure preloading

The Catalog Management landscape override can expose the next row before that
next row receives focus. Its visible poster window needs decoded images in
Coil before the user reaches the landscape row.

## Earlier fixes and remaining gaps

- `a6c97ebc`: added exposed portrait-row prewarming on October 14.
- `30aa88ae` (merged by `3a0f33b3`): enlarged the visible window and filtered
  scrolling emissions before `collectLatest` on October 16.
- `f7be615c`: removed the idle delay and recorded completion only after successful
  requests on October 27.

At baseline `a56caedf`, the scan still began at `focusedRowIndex + 1`. Moving
onto the landscape bridge therefore dropped its exposed target, even if that
target's batch had not finished. Any idle enrichment update to `carouselRows`
could also restart `collectLatest`, although the target artwork was unchanged.
The single completion signature did not establish that images remained cached.

The image requests also differed. Bare Coil 2.7 requests default to `Scale.FIT`;
`AsyncImage` with `ContentScale.Crop` resolves to `Scale.FILL` and inexact
precision. Matching width and height alone did not match the decode. This matters
especially when portrait cards reserve a landscape-width expansion request:
a FIT-sized poster can be too small for the subsequent FILL request.

The pre-composition starting index could also differ from the renderer's saved
viewport, because it consulted a focus map that can be prefilled with zero.

## Current implementation

`HomeExposurePrewarmer` owns jobs by the actual row window: row key, image URLs,
and pixel dimensions. Unchanged windows keep their in-flight jobs across focus
and metadata changes. Later idle updates check the actual cached bitmap identity
using weak references. Resident windows need no new requests; eviction or an
earlier failure makes the affected window eligible again. Larger replacement
bitmaps can be adopted through Coil's own size validation.

The bounded scan includes the focused bridge and the next three possible
bridges. Every exposed orientation is eligible, including consecutive per-row
landscape overrides. At most two exposure requests execute concurrently; each
window processes its images sequentially. New work starts only from idle
snapshots, and changing the displayed platform cancels the owner's scope.

The preloader and `ModernCarouselCard` share `homePosterImageRequest`, with
explicit FILL scaling and inexact precision. The ordinary directional poster
prefetch uses it too. Window position follows the real LazyRow, retained actual
focus, then saved scroll index, matching renderer initialization. Universal
landscape does not activate this exposure scheduler.

## Verification

- Twelve focused JVM regression tests pass using Kotlin 2.3.0, coroutines 1.8.1,
  and JUnit 4.13.2. They cover landing on the bridge, consecutive landscape rows,
  bounded selection, metadata churn, preserved jobs, eviction, retry, changed
  artwork/dimensions, concurrency, and removed windows.
- The shared image-request builder compiles against Coil 2.7.0 and Android 36.
- Full `:app:compileDebugKotlin` passes with JDK 21 and Android 36. A single
  worker and in-process Kotlin compilation at a 2400 MB heap avoided the
  environment's disappearing-daemon failure with separate 4 GB daemons.
- The project's `:app:testDebugUnitTest --tests
  com.nuvio.tv.ui.screens.home.HomeExposurePrewarmerTest` also passes: 12 tests,
  zero failures, errors, or skips. This compiles the app and its unit-test sources;
  only the focused exposure test class was executed.
- Sideload verification requires the owner's local runtime configuration,
  which is unavailable in this checkout. The debug variant was compiled for
  source validation; no installable build was produced or installed.
- On-device validation remains pending.

On-device validation must include previously unseen poster URLs, long-session
revisits, saved horizontal positions, consecutive landscape rows, and ordinary
scrolling. A warm-cache-only first pass cannot establish this result. Remote
image failures or navigation faster than image retrieval can still outrun
best-effort preloading; this change does not delay focus to wait for a network
request.
