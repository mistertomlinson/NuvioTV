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
- Trakt provider adapter and registry binding are being added in the commit
  containing this handoff update.

## Current integration point

`WatchProgressRepositoryImpl` now selects its active read provider through:

- `TrackingProgressProviderRegistry`
- provider authentication flows
- `effectiveWatchProgressSource`
- the configured Watch Progress source

The following reads are now provider-neutral and route through either Trakt,
Simkl, or local/Nuvio Sync as appropriate:

- all progress and Continue Watching
- single-title and episode progress
- full episode-progress maps
- Next Up seeds
- watched movie IDs
- watched status
- aired episode order
- watched show episodes and sibling IDs
- dropped/hidden progress status

The normal incremental `assembleDebug` build passed after this conversion.

Writes and history mutations remain intentionally unchanged for now. They still
contain Trakt-specific behavior and must be converted separately so this read
migration cannot accidentally introduce dual-writing or change established
Trakt behavior.

## Next steps, in dependency order

1. Commit the provider-neutral read-path conversion with this updated handoff.
2. Convert optimistic progress writes to the selected active provider:
   - `saveProgress`
   - batch progress saves
   - optimistic progress updates
   - optimistic progress removals
   - clearing optimistic state
3. Preserve durable local progress regardless of the selected remote provider.
4. Convert playback-record removal without deleting unrelated provider history.
5. Convert manual watched/unwatched history mutations through the selected
   provider's history writer.
6. Prevent any operation from silently broadcasting writes to both Trakt and
   Simkl.
7. Make Supabase/Nuvio Sync upload and delete guards provider-neutral rather
   than Trakt-specific.
8. Add any provider-neutral repository interface methods required by Home,
   Details, watched badges, and player state.
9. Finish Home, Details, watched-badge, and player integration.
10. Finish mixed newest-first Simkl My List/library behavior.
11. Implement durable unfinished Simkl playback beyond Simkl's remote
    playback-retention window.
12. Finish hidden/dismissed Continue Watching behavior and release alerts.
13. Install and test with Trakt, Simkl, and Nuvio Sync selected.
14. Run fully warmed Home scrolling tests in every navigation mode before
    declaring parity complete.

## Immediate next action

Commit the provider-neutral read path and this handoff together. Then inspect
the existing `saveProgress`, `removeProgress`, `removeFromHistory`,
`markAsCompleted`, and batch-write paths before converting writes.

## Files most relevant to the next step

- `app/src/main/java/com/nuvio/tv/data/repository/WatchProgressRepositoryImpl.kt`
- `app/src/main/java/com/nuvio/tv/domain/repository/WatchProgressRepository.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingSources.kt`
- `app/src/main/java/com/nuvio/tv/data/repository/TraktTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/data/simkl/SimklTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/di/TraktTrackingModule.kt`
- `app/src/main/java/com/nuvio/tv/core/di/SimklAuthModule.kt`
