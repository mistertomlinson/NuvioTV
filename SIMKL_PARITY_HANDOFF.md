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

The obsolete source-selection paths in `TraktViewModel` have also been removed:

- `onWatchProgressSourceSelected()` and `onLibrarySourceModeSelected()` had no
  callers and bypassed the centralized provider-aware transition logic.
- Their duplicate `TraktUiState` fields, settings observers, imports, and the
  now-unused `StartupSyncService` dependency were removed.
- The only remaining direct writes to `watchProgressSource` and
  `librarySourceMode` are inside `TrackingSourceController`.
- Trakt disconnect behavior and its required watched-item, watched-series, and
  Continue Watching cache cleanup dependencies remain unchanged.
- The normal incremental `assembleDebug` build passed after this cleanup.

## Next steps, in dependency order

1. Commit the removal of obsolete Trakt source-selection paths with this
   handoff update.
2. Add or expose provider-neutral video-ID/anime watched lookup where Details,
   episode badges, or player state require it.
3. Verify Home Continue Watching and Next Up behavior with Trakt selected:
   - cached rows survive initial remote loading
   - completed-history seeds resolve correctly
   - configured age cutoff remains unchanged
4. Verify the same Home paths with Simkl selected:
   - cached rows survive initial snapshot loading
   - Simkl progress and watched seeds populate
   - no Trakt-only cutoff is applied
5. Verify local/Nuvio Sync behavior when no external provider is selected.
6. Finish watched badges, Details, manual watched/unwatched, and player-state
   integration.
7. Finish mixed newest-first Simkl My List/library behavior.
8. Implement durable unfinished Simkl playback beyond Simkl's remote
   playback-retention window.
9. Add dedicated Simkl optimistic projection support if runtime behavior shows
    it is needed for immediate Continue Watching updates.
10. Finish hidden/dismissed Continue Watching behavior and release alerts.
11. Install and test with Trakt, Simkl, and Nuvio Sync selected.
12. Run fully warmed Home scrolling tests in every navigation mode before
    declaring parity complete.

## Immediate next action

Commit the obsolete Trakt source-selection cleanup and this updated handoff.

After that, audit Details, episode badges, and player-state callers that still
need provider-neutral video-ID or anime watched lookup.

## Files most relevant to the next step

- `app/src/main/java/com/nuvio/tv/data/repository/WatchProgressRepositoryImpl.kt`
- `app/src/main/java/com/nuvio/tv/domain/repository/WatchProgressRepository.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingSources.kt`
- `app/src/main/java/com/nuvio/tv/data/repository/TraktTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/data/simkl/SimklTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/di/TraktTrackingModule.kt`
- `app/src/main/java/com/nuvio/tv/core/di/SimklAuthModule.kt`

## Trakt provider-neutral scrobbler registration

Completed after commit `636f2b6b`:

- Added `TraktTrackingProvider`.
- Added `TraktTrackingScrobbler`.
- Registered the Trakt `TrackingProvider` in `TraktTrackingModule`.
- Preserved the existing Trakt episode-mapping service.
- Preserved the existing Trakt pause-scrobble action.
- Normal incremental `./gradlew assembleDebug` passed.
- Rating submission remains Trakt-specific and was not changed.

Important current state:

- `TrackingScrobbleCoordinator` can now resolve both Trakt and Simkl scrobblers.
- It still sends only to the selected Watch Progress provider.
- Internal and external players still contain their old direct Trakt scrobble calls.
- Do not remove those direct calls until each player is converted to the coordinator.
- Do not alter the custom rating overlay while converting playback scrobbles.

Immediate next action:

1. Convert external playback scrobbling to `TrackingScrobbleCoordinator`.
2. Build and commit that conversion separately.
3. Convert internal playback scrobbling while retaining Trakt-only rating submission.
4. Expose provider-neutral video-ID and parent-ID normalization APIs for Details/anime watched-state handling.

## External playback provider-neutral scrobbling

Completed after commit `e1716637`:

- Converted `ExternalPlaybackTracker` from direct Trakt authentication and scrobble calls to `TrackingScrobbleCoordinator`.
- External playback now sends START and STOP only to the selected, authenticated Watch Progress provider.
- Trakt episode mapping remains inside `TraktTrackingScrobbler`.
- Simkl identity enrichment remains inside `SimklTrackingScrobbler`.
- Removed Trakt-specific authentication, episode-mapping, and item-building dependencies from `ExternalPlaybackTracker`.
- Normal incremental `./gradlew assembleDebug` passed.
- No direct Trakt scrobble references remain in `ExternalPlaybackTracker`.

Immediate next action:

1. Convert internal-player scrobble START, PAUSE, STOP, completion, periodic, and seek handling to `TrackingScrobbleCoordinator`.
2. Preserve the existing Trakt-only rating overlay and `postRating()` behavior.
3. Build and commit the internal-player conversion separately.
4. Add provider-neutral video-ID and parent-ID normalization APIs for Details/anime watched-state handling.

## Internal playback provider-neutral scrobbling

Completed after commit `6faad75e`:

- Converted internal-player START, PAUSE, STOP, periodic progress snapshots, completion, and seek handling to `TrackingScrobbleCoordinator`.
- Playback scrobbles now go only to the selected, authenticated Watch Progress provider.
- Added provider-neutral `TrackingMediaReference` construction for internal playback.
- Trakt-specific episode mapping remains inside `TraktTrackingScrobbler`.
- Simkl-specific enrichment remains inside `SimklTrackingScrobbler`.
- Seek STOP/restart behavior is dispatched only to providers whose seek policy requires it.
- No direct `traktScrobbleService.scrobbleStart`, `scrobblePause`, or `scrobbleStop` calls remain in the internal player.
- Preserved the existing Trakt authentication check used to expose the custom rating prompt.
- Preserved the existing Trakt-only `postRating()` path and custom rating values.
- Normal incremental `./gradlew assembleDebug` passed.

Immediate next action:

1. Add provider-neutral `isWatchedByVideoId()` and `normalizeParentContentId()` APIs to `WatchProgressRepository`.
2. Delegate those APIs to the active tracking progress provider.
3. Wire Details/anime watched-state and relevant player progress normalization through them.
4. Build and commit separately.
5. Then begin runtime validation for Trakt, Simkl, and Nuvio Sync.
