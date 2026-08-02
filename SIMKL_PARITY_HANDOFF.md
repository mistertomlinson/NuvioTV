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

`WatchProgressRepositoryImpl` still selects only between:

- direct Trakt-specific repository code; and
- local/Nuvio Sync storage.

It does not yet use `TrackingProgressProviderRegistry`, so selecting Simkl in
settings does not yet route Home, Continue Watching, Next Up, watched badges,
or Details lookups through `SimklTrackingProgressProvider`.

The provider registry previously contained only Simkl. The next commit adds
`TraktTrackingProgressProvider` and its Hilt multibinding so the repository can
be converted without breaking existing Trakt behavior.

## Next steps, in dependency order

1. Build and verify the Trakt provider registration added with this handoff.
2. Commit the provider registration and this handoff together.
3. Convert `WatchProgressRepositoryImpl` to use:
   - `TrackingProgressProviderRegistry`
   - provider authentication flows
   - `effectiveWatchProgressSource`
   - the currently selected active provider
4. Preserve the branch's custom local-progress retention and Next Up behavior
   while replacing Trakt-only read paths.
5. Make these repository reads provider-neutral:
   - all progress
   - Continue Watching
   - single-item progress
   - episode progress
   - Next Up seeds
   - watched movie IDs
   - watched status
   - watched show episodes
   - sibling IDs
   - dropped/hidden status
6. Make optimistic progress/removal provider-neutral.
7. Keep local durable unfinished progress merged with providers where required.
8. Make Supabase/Nuvio Sync guards provider-neutral rather than Trakt-specific.
9. Add provider-neutral remote-loaded and Next Up preparation APIs to the
   repository interface where required by Home.
10. Wire Home, Details, watched badges, and player state to the active provider.
11. Implement provider-neutral manual watched/unwatched history mutations.
12. Finish the mixed newest-first Simkl My List/library behavior.
13. Implement durable local unfinished Simkl playback beyond Simkl's remote
    playback-retention window.
14. Finish hidden/dismissed Continue Watching behavior and release alerts.
15. Install and test both Trakt-selected and Simkl-selected configurations.
16. Run warmed Home scrolling tests in every navigation mode before declaring
    parity complete.

## Immediate next action

Run the incremental debug build. If it passes, inspect the staged diff and
commit with a message similar to:

`Register Trakt progress provider`

Then begin the surgical provider-neutral conversion of
`WatchProgressRepositoryImpl`.

## Files most relevant to the next step

- `app/src/main/java/com/nuvio/tv/data/repository/WatchProgressRepositoryImpl.kt`
- `app/src/main/java/com/nuvio/tv/domain/repository/WatchProgressRepository.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/tracking/TrackingSources.kt`
- `app/src/main/java/com/nuvio/tv/data/repository/TraktTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/data/simkl/SimklTrackingProgressProvider.kt`
- `app/src/main/java/com/nuvio/tv/core/di/TraktTrackingModule.kt`
- `app/src/main/java/com/nuvio/tv/core/di/SimklAuthModule.kt`
