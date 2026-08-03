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
7. Provider-neutral Simkl My List/library backend is complete; Home catalog and cache wiring remain.
8. Durable unfinished Simkl playback beyond Simkl's remote
   playback-retention window is complete.
9. Add dedicated Simkl optimistic projection support if runtime behavior shows
    it is needed for immediate Continue Watching updates.
10. Simkl stale-playback reappearance suppression is complete; provider-neutral Next Up dismissal and release alerts remain.
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

## Provider-neutral watched-ID and parent-ID helpers

Completed after commit `cb375df8`:

- Exposed `isWatchedByVideoId(videoId, episode)` through `WatchProgressRepository`.
- Exposed `normalizeParentContentId(parentContentId, videoId)` through `WatchProgressRepository`.
- Delegated both helpers to the selected active `TrackingProgressProvider`.
- Simkl can now project anime watched state using episode video IDs when the parent series ID cannot be matched reliably.
- Details retains its existing local plus authoritative watched-episode projection and supplements it with the provider video-ID fallback.
- The existing Next-to-Watch algorithm was preserved.
- Internal-player progress now normalizes the parent content ID before saving.
- Trakt continues using its existing effective-content-ID mapping.
- Local/Nuvio Sync mode retains the original parent content ID.
- Normal incremental `./gradlew assembleDebug` passed.

Immediate next action:

1. Install the current APK without clearing app data.
2. Validate Tracking settings and account state.
3. Test Trakt-selected Home, Details, internal-player, and external-player behavior.
4. Test Simkl-selected Home, Details, internal-player, and external-player behavior.
5. Test Nuvio Sync/local mode and verify no remote provider receives playback writes.
6. Allow Home to warm fully before evaluating scrolling performance.
7. Run the established full AOT command only when beginning performance validation.
## Provider-neutral ratings

Completed in commit `Add provider-neutral ratings`:

- Added `TrackingRatingCoordinator` as the single rating-submission path.
- Ratings are routed only to the selected Watch Progress provider.
- Trakt selected and connected:
  - rating prompt is available
  - rating is submitted only to Trakt
- Simkl selected and connected:
  - rating prompt is available
  - rating is submitted only to Simkl through `POST /sync/ratings`
- Nuvio Sync selected:
  - rating prompt is not available
  - no external rating is submitted
- A disconnected selected provider does not expose the rating prompt.
- Having both Trakt and Simkl connected never causes dual rating writes.
- Home, Details, and Player now use the same provider-aware availability rule.
- Removed all direct UI calls to Trakt `postRating()`.
- Simkl ratings validate the 1-10 range client-side.
- Simkl responses are inspected to confirm `added.statuses` contains an applied item;
  an HTTP success response alone is not treated as proof that the rating was stored.
- Preserved Trakt title/year fallback compatibility.
- Preserved the existing rating interface and exact mappings:
  - Thumbs down = 2
  - Thumbs up = 7
  - Love it = 10
- The two rating overlay files were not modified.
- Normal incremental `./gradlew assembleDebug` passed.
- No Home active-scroll-path work was added; availability checks occur only during
  watched actions or playback preparation/completion.

Runtime rating validation completed successfully:

- Trakt-selected ratings work.
- Simkl-selected ratings work.
- Nuvio Sync does not expose the rating prompt.
- A disconnected selected provider does not expose the rating prompt.
- No dual rating writes occurred.
- Home, Details, and Player behavior all passed.
- Exact 2 / 7 / 10 mappings were confirmed.
- Temporary Continue Watching and Simkl ordering diagnostics were removed.
- The working tree was clean after diagnostic removal.

Immediate next action:

1. Fix the Nuvio Sync watch-progress isolation gate so Supabase watch-progress
   synchronization runs only when Nuvio Sync is the selected effective source.
2. Build and commit that correction separately.
3. Then implement durable per-profile unfinished Simkl progress.

## Nuvio Sync progress isolation

Completed in commit `Isolate Nuvio Sync progress synchronization`:

- Supabase watch-progress synchronization now runs only when the stored Watch
  Progress Source is exactly `NUVIO_SYNC`.
- Applied the same source-isolation rule to watched-item synchronization.
- Removed the previous Trakt-authentication-based gate.
- Trakt-selected and Simkl-selected modes now skip all Supabase:
  - progress pushes
  - single-progress pushes
  - progress deletions
  - progress pulls
  - watched-item pushes
  - watched-item pulls
- Account sync checks the Nuvio Sync gate before consuming progress or watched
  item pull results.
- Startup sync checks the Nuvio Sync gate before consuming progress or watched
  item pull results.
- A skipped provider pull is no longer treated as authoritative empty remote
  data by Account or startup synchronization.
- Existing library synchronization behavior was preserved.
- Existing Nuvio Sync source-selection restoration remains intact.
- Normal incremental `./gradlew assembleDebug` passed.
- No Home active-scroll-path work was added.

Runtime validation completed:

- Simkl-selected startup reported
  `shouldUseSupabaseWatchProgressSync=false`.
- Simkl-selected startup skipped Supabase progress and watched-item sync.
- Nuvio Sync-selected startup reported
  `shouldUseSupabaseWatchProgressSync=true`.
- Nuvio Sync successfully pulled zero watched items and zero progress entries
  from an intentionally empty cloud account.
- No deleted cloud entries were restored.
- Trakt was not directly runtime-tested because it is disconnected, but it uses
  the same exact stored-source gate as Simkl.

## Authoritative Nuvio Sync source selection

Completed and runtime-tested after the isolation commit:

- A deliberate user switch to Nuvio Sync now downloads both progress and
  watched-item snapshots before changing the selected source.
- The preselection pull can explicitly bypass the normal source gate.
- The cloud snapshot is authoritative during this deliberate transition,
  including when the cloud account is empty.
- Existing local progress and watched items are cleared only after both remote
  pulls succeed.
- A pull failure leaves the previous source and local state intact.
- The source is changed to Nuvio Sync only after authoritative replacement
  completes.
- Continue Watching and Next Up caches are invalidated after the transition.
- Normal startup synchronization remains merge-based and non-destructive.
- Runtime testing confirmed that switching Simkl -> Nuvio Sync with an empty
  cloud account removes stale local Continue Watching titles.
- No Home active-scroll-path work was added.
- Normal incremental `./gradlew assembleDebug` passed before runtime testing.

Immediate next action:

1. Commit the authoritative Nuvio Sync source-selection behavior and handoff.
2. Implement durable per-profile unfinished Simkl progress.

## Durable per-profile unfinished Simkl progress

Completed and runtime-validated:

- Added a dedicated per-profile DataStore for unfinished Simkl playback.
- Only progress from 2% through less than 85% is retained.
- Simkl remote playback remains authoritative while it exists.
- When Simkl removes or expires remote playback, Nuvio falls back to its
  durable local copy and keeps the title in Continue Watching.
- Remote and durable entries are deduplicated by title so separate episodes
  cannot create duplicate Continue Watching entries.
- Completed, removed, or explicitly dismissed progress clears the durable copy.
- Simkl durable progress is visible only while Simkl is the selected Watch
  Progress Source.
- It does not leak into Trakt or Nuvio Sync.
- Switching away from Simkl hides the progress; switching back restores it.
- The store follows the existing profile DataStore isolation.
- Clearing app data or uninstalling Nuvio removes this device-local fallback.
- No work was added to the active Home scrolling path.

Validation completed:

- Focused `SimklDurableProgressTest` passed.
- Normal incremental `assembleDebug` passed.
- Installed without clearing app data.
- Progress survived removal from both Simkl watched history and Simkl playback,
  followed by a manual Simkl sync.
- Provider switching correctly hid and restored the Simkl-owned entry.
- Removing the entry inside Nuvio kept it removed after restart.
- Cross-episode Continue Watching deduplication test passed.

## Provider-neutral Simkl library backend

Completed in the provider-neutral library work following `65fe0df8`:

- Registered the existing Trakt library service as a
  `TrackingLibraryProvider`.
- `LibraryRepositoryImpl` now honors the profile-selected `LOCAL`, `TRAKT`,
  or `SIMKL` library source.
- Library items, list tabs, membership reads, default toggles, membership
  changes, and manual refreshes route only through the selected provider.
- Simkl default add/remove behavior maps to Plan to Watch.
- Provider writes remain isolated; selecting Simkl does not write to Trakt.
- A disconnected selected provider safely falls back to the local library.
- Normal Plan to Watch removal is allowed.
- Simkl removals that would also erase watched history or a rating fail before
  mutation until the confirmation-capable UI is wired.
- Focused provider-routing unit tests passed.
- Normal incremental `assembleDebug` passed.
- No Home UI or active scrolling-path code was changed.

## Simkl removed-playback suppression

Completed after `e9119471`:

- Added a profile-scoped Simkl progress-dismissal tombstone store.
- Removing Simkl progress now clears the durable fallback and records the
  removal before deleting the remote playback session.
- Stale Simkl snapshots and stale durable entries are filtered so a removed
  Continue Watching item cannot immediately reappear.
- Episode-specific removal suppresses only that episode.
- Whole-title removal suppresses all playback entries for that title.
- Genuinely newer playback automatically clears matching tombstones and
  becomes visible again.
- Matching is case-insensitive and tombstones are capped at 300 entries.
- Existing durable-progress tests and focused dismissal tests passed.
- Normal incremental `assembleDebug` passed.
- No Home UI or active scrolling-path code was changed.
- Provider-neutral Next Up dismissal still requires later Home pipeline wiring.

## Selected-provider refresh routing

Completed after `e60cf9e0`:

- App resume still requests the normal Nuvio startup/account synchronization.
- External tracking refresh now targets only the selected authenticated provider.
- Selecting Trakt refreshes Trakt only.
- Selecting Simkl refreshes Simkl only.
- Selecting Nuvio Sync refreshes no external tracking provider.
- Existing `refreshConnected()` behavior remains available for operations that
  deliberately refresh every connected provider.
- Focused routing tests passed.
- Normal incremental `assembleDebug` passed.
- The previous unconditional Trakt refresh was removed from `MainActivity`.

## Provider-neutral Next Up dismissal

Completed after `5aff5829`:

- Home no longer injects or calls `TraktProgressService` directly.
- Next Up dismissal routes through the active tracking provider.
- Trakt preserves its server-side hidden-progress behavior.
- Simkl stores profile-scoped dismissal tombstones and filters dismissed
  Next Up seeds from its projection.
- Nuvio Sync/local mode retains the existing local Home dismissal only.
- Historical Trakt dismissal migration runs only while Trakt is selected.
- The migration is not marked complete unless the active provider handles
  every migrated dismissal.
- Focused routing and Simkl dismissal regression tests passed.
- Normal incremental `assembleDebug` passed.

## Current immediate next action

After the unrelated Home UI work is complete, wire the custom
`nuvio.mylist` Home catalog and cache to the provider-neutral repository:

- Simkl uses Plan to Watch.
- Preserve mixed movies, shows, and anime.
- Preserve newest-first ordering and provider attribution.
- Add/remove must update only the selected provider.
- Replace stale Trakt-backed Home cache behavior without adding work to the
  active scrolling path.
