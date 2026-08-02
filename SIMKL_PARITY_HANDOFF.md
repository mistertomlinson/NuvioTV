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

`WatchProgressRepositoryImpl` now routes both reads and writes through the
selected active tracking provider.

Completed provider-neutral behavior:

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
- `removeProgress()` no longer deletes Trakt playback merely because Trakt is
  connected.
- `markAsCompleted()` no longer mirrors completion to an unselected provider.
- The normal incremental `assembleDebug` build passed after the write-path
  conversion and legacy dependency cleanup.

Simkl's provider-level optimistic methods are currently no-ops. Immediate UI
and long-term playback durability therefore continue to depend on the local
progress copy until dedicated Simkl optimistic projection support is added.

## Next steps, in dependency order

1. Commit the provider-neutral write-path conversion with this handoff update.
2. Audit remaining Supabase/Nuvio Sync guards outside
   `WatchProgressRepositoryImpl` and remove any Trakt-specific assumptions.
3. Replace or supplement the compatibility method
   `isTraktProgressActive()` with provider-neutral repository APIs where callers
   require active-provider information.
4. Add repository APIs needed by Home and Details:
   - remote progress loaded state
   - active-provider Continue Watching cutoff
   - Next Up seed preparation/remapping
   - video-ID/anime watched lookup
5. Verify Home Continue Watching and Next Up behavior with Trakt selected.
6. Verify the same Home paths with Simkl selected.
7. Verify local/Nuvio Sync behavior when no external provider is selected.
8. Finish watched badges, Details, and player-state integration.
9. Finish mixed newest-first Simkl My List/library behavior.
10. Implement durable unfinished Simkl playback beyond Simkl's remote
    playback-retention window.
11. Add dedicated Simkl optimistic projection support if required for immediate
    Continue Watching updates.
12. Finish hidden/dismissed Continue Watching behavior and release alerts.
13. Install and test with Trakt, Simkl, and Nuvio Sync selected.
14. Run fully warmed Home scrolling tests in every navigation mode before
    declaring parity complete.

## Immediate next action

Commit the provider-neutral write-path conversion and this updated handoff.

After that, audit all remaining callers of `isTraktProgressActive()` and all
Supabase synchronization guards before changing Home behavior.

## Files most relevant to the next step

- `app/src/main/java/com/nuvio/tv/data/repository/WatchProgressRepositoryImpl.kt`
- `app/src/main/java/com/nuvio/tv/domain/repository/WatchProgressRepository.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingSources.kt`
- `app/src/main/java/com/nuvio/tv/data/repository/TraktTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/data/simkl/SimklTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/di/TraktTrackingModule.kt`
- `app/src/main/java/com/nuvio/tv/core/di/SimklAuthModule.kt`
