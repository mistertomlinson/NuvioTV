# Nuvio Simkl Parity Handoff

Updated: 2026-08-02

## Repository state

- Project: `/Users/mac/Developer/NuvioTV_420`
- Branch: `feature/simkl-parity-20260801`
- Base before Simkl work: `b022d6ce`
- Latest committed performance fix before this work: `614110b6`
- Upstream reference used for the curated backport:
  `f6eb38ce1682d697d841f5246bede90ec0f9a4a5`
- Do not modify or delete untracked `.bak` files.

## Non-negotiable project rules

1. Preserve Trakt unless the user deliberately changes the selected source.
2. Never silently write playback or history to both Trakt and Simkl.
3. Playback scrobbling goes only to the selected Watch Progress provider.
4. Do not clear app data during testing.
5. Use the normal incremental `./gradlew assembleDebug` first.
6. Only use the user's exact AOT command for explicit performance testing:
   `adb -s 192.168.50.83:5555 shell cmd package compile -m speed -f com.nuviodebug.com`
7. Let Home fully warm before judging scrolling.
8. Any Home scrolling regression is unacceptable.
9. Avoid new network, coroutine, recomposition, or projection work on the active scroll path.
10. Keep the custom rating mapping unchanged:
    thumbs down = 2, thumbs up = 7, heart = 10.

## Completed work

- Provider-neutral tracking foundations.
- Native Simkl authentication and per-profile credentials.
- Simkl account settings screen and manual synchronization controls.
- Simkl snapshot synchronization and media projections.
- Simkl mutation, history-writing, and scrobbling foundations.
- Simkl progress, history, and library provider registration.
- Central Tracking settings screen and source selectors.
- Scrobbling restricted to the selected Watch Progress provider.
- Simkl snapshot projections cached off the main thread.
- Dedicated Simkl projection tests pass.
- Incremental debug build passed after the projection-cache backport.
- Trakt progress provider and registry binding are complete.
- Trakt history writer and registry binding are complete. This branch lacks
  upstream Trakt batch-history service methods, so the adapter deliberately
  performs history mutations one item at a time through the existing tested
  service APIs.

## Current integration point

`WatchProgressRepositoryImpl` now routes reads and writes through the selected
active tracking provider.

Completed provider-neutral repository behavior:

- Trakt selected: reads, playback removal, optimistic state, and manual history
  mutations route only to Trakt.
- Simkl selected: reads, playback removal, and manual history mutations route
  only to Simkl.
- Nuvio Sync selected: local/Supabase synchronization is used and neither
  external provider receives repository mutations.
- A durable local progress copy is retained in every mode.
- Manual watched/unwatched operations use the matching writer from
  `TrackingHistoryWriterRegistry`.
- No direct Trakt progress-service dependency remains in
  `WatchProgressRepositoryImpl`.
- No repository mutation is sent to Trakt merely because Trakt is connected.

Home Continue Watching and Next Up now use provider-neutral capabilities:

- `observeRemoteProgressLoaded()` protects cached rows while either Trakt or
  Simkl is still completing its initial remote load.
- Cached Next Up entries are evicted only after the active provider has
  conclusively loaded.
- `activeProviderContinueWatchingCutoffEpochMs()` applies Trakt's configured
  age window while Simkl retains its provider default of no cutoff.
- `shouldUseAsNextUpSeed()` applies the selected provider's completed-seed
  eligibility rules.
- `prepareNextUpSeed()` lets the active provider remap episode numbering before
  metadata lookup.
- The obsolete `isTraktProgressActive()` Home/repository API and all
  `useTraktProgress`/`seedsNotYetLoaded` branches have been removed.
- The existing custom Continue Watching cache, sequential older-seed resolver,
  partial emission behavior, enrichment, and ordering remain in place.
- The normal incremental `assembleDebug` build passed after this conversion.

Simkl's provider-level optimistic methods remain no-ops. Immediate UI and
long-term playback durability therefore still depend on the retained local
progress copy until dedicated Simkl optimistic projection support is added.

The provider-neutral Home code has compiled successfully but has not yet been
installed or runtime-tested with Trakt, Simkl, and Nuvio Sync selections.

## Next steps, in dependency order

1. Commit the provider-neutral Home Continue Watching/Next Up integration with
   this handoff update.
2. Audit old Trakt settings/source-switching methods that may remain reachable
   outside the centralized `TrackingSourceController`.
3. Add or expose provider-neutral video-ID/anime watched lookup where Details,
   episode badges, or player state require it.
4. Verify Home Continue Watching and Next Up behavior with Trakt selected:
   - cached rows survive initial remote loading
   - completed-history seeds resolve correctly
   - configured age cutoff remains unchanged
5. Verify the same Home paths with Simkl selected:
   - cached rows survive initial snapshot loading
   - Simkl progress and watched seeds populate
   - no Trakt-only cutoff is applied
6. Verify local/Nuvio Sync behavior when no external provider is selected.
7. Finish watched badges, Details, manual watched/unwatched, and player-state
   integration.
8. Finish mixed newest-first Simkl My List/library behavior.
9. Implement durable unfinished Simkl playback beyond Simkl's remote
   playback-retention window.
10. Add dedicated Simkl optimistic projection support if runtime behavior shows
    it is needed for immediate Continue Watching updates.
11. Finish hidden/dismissed Continue Watching behavior and release alerts.
12. Install and test with Trakt, Simkl, and Nuvio Sync selected.
13. Run fully warmed Home scrolling tests in every navigation mode before
    declaring parity complete.

## Immediate next action

Commit the provider-neutral Home Continue Watching/Next Up conversion and this
updated handoff.

After that, audit the remaining old source-switching methods outside the
centralized tracking settings/controller before installing the build.

## Files most relevant to the next step

- `app/src/main/java/com/nuvio/tv/data/repository/WatchProgressRepositoryImpl.kt`
- `app/src/main/java/com/nuvio/tv/domain/repository/WatchProgressRepository.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingSources.kt`
- `app/src/main/java/com/nuvio/tv/data/repository/TraktTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/data/simkl/SimklTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/di/TraktTrackingModule.kt`
- `app/src/main/java/com/nuvio/tv/core/di/SimklAuthModule.kt`
