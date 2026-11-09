# Persistent Simkl rewatch cursor — Android + iPad 0.6.7

Date: 2026-11-09
Status: design/prototype only; no runtime integration, no migrations, no Simkl writes.

## Baselines pinned for review

- Android: `mistertomlinson/NuvioTV` `dev` at `89ecfd901f1d78e9a2e695e2cbd43ceee5c01abd`.
- Apple: official `NuvioMedia/NuvioMobile` tag `0.6.7-beta`, commit `dcade51767a4b76e29bbd28fc067683fb1febf9d`. The release has the `nuvio-0.6.7-full-release.ipa` asset. Do not compare against the moving Apple `cmp-rewrite` head.
- Simkl free accounts: ordinary watched episode history cannot serve as an independent replay cursor. Existing code still scrobbles to Simkl, so preserving *unchanged* historical records requires explicit replay-aware routing in **both** apps.

## Confirmed Android failure modes

1. The Player-completion bridge `playerCompletedNextUpSeedsByProfile` is volatile and is considered fresh for only 3 minutes.
2. Simkl's completed-history Next Up seed selection is independent of replay order; a historical series finale may win after the bridge disappears.
3. Completed episodes remove the Simkl durable in-progress entry, so it cannot recover a replay's next episode after restart.
4. `mergeSimklProgressWithDurable` makes any remote progress entry for a series suppress its local durable entry, even when older. Existing tests intentionally cover that precedence.
5. Details' `resolveFreshRewatchSuccessor` also relies on a 3-minute bridge.
6. Home's refreshed Next Up pipeline can drop a cached entry once fresh provider metadata reports no successor.

## Apple 0.6.7 compatibility

Apple is an independent Kotlin Multiplatform codebase (`composeApp/src/commonMain`). It has `SeriesPlaybackResolver`'s `allowRewatch` fallback and an independent `WatchProgressRepository`; it **does not consume Android's in-memory bridge**. The official 0.6.7 IPA has no automatic way to talk to a future custom rewatch VPS endpoint.

A server-only change cannot synchronize a replay between an unmodified iPad 0.6.7 IPA and Android. To get full cross-device behavior, a subsequent Apple build derived from this release must explicitly read/write the replay cursor, route replay playback through it, and distinguish replay from ordinary Simkl writes. The installed IPA remains unchanged during prototype work.

## Required invariants

- Simkl remains authoritative for original watched flags, ratings and list membership.
- A rewatch session **must not unwatch/re-watch** historical episodes, reset watched counters, or call Simkl history recovery solely to advance replay.
- A replay session is separate from the ordinary provider Next Up seed; no 3-minute expiration.
- A completed replay episode advances to its *immediate* next playable aired episode regardless of whether Simkl already marks that successor watched.
- A paused replay resumes its current episode/position, and newly loaded metadata never rewinds to season 1.
- Missing/partial metadata leaves a recoverable `AWAITING_METADATA` cursor, rather than deleting the card or assuming the series ended.
- A confirmed last episode marks the replay `FINISHED` and removes the replay CW card without altering the ordinary completed-series checkmark.
- Normal first-watches, episode unwatch actions, dismissals, season release alerts, other providers, and rating/credit timing are unchanged.
- Every read/write is scoped to the selected person and canonical show identity; do not equate Android local profile integer IDs with iOS profile IDs.
- Replays started on one device should eventually be visible on the other after authorized sync, without needing the other device to replay the episode.

## Prototype present in this branch

`domain/rewatch/RewatchSessionPolicy.kt` has pure in-memory state transitions for start, progress, completion, and recovering from unavailable metadata. `RewatchSessionPolicyTest.kt` tests ordinary first-watch exclusion, sequential progression across seasons, restart/TTL independence, terminal completion, stale callbacks, and metadata outage recovery.

**Not wired to any screen, repository, player, storage or server.** These models prove the state transition contract only; no user data is accessed or changed. Compilation/device tests still required.

## Proposed migration-safe rollout (separate future branches)

1. Run pure policy unit tests and improve any discovered edge cases.
2. Add an Android profile-scoped local store (DataStore/SQLite) using a separate `rewatch_sessions` feature key and explicit schema version; do not migrate or delete existing watch history, CW cache, or Simkl data.
3. Introduce explicit **Start Rewatch** and **Stop Rewatch** actions before automatic detection. Existing users with no replay marker follow unchanged behavior. Decide whether automatic replay detection after a meaningful start is wanted in a follow-up UX pass.
4. On Android, add one replay-aware event routing boundary across playback START, PAUSE, STOP, seek, credit completion, autoplay, and Details/Home Next Up. Only the player’s authoritative completion event can advance a replay. Suppress normal Simkl history writes **for an identified rewatch session only**, while preserving normal first-watch scrobbles.
5. Cover app force-stop, profile change during save, metadata outages, local playback timestamp conflicts, terminal episode, and preexisting badge behavior with tests. Validate on a sideload APK with AOT.
6. Expose an authenticated versioned VPS API for replay sessions and per-device event idempotency. Server-side session revisions and expected-revision compare-and-swap prevent lost updates; client timestamps are not reliable cross-device ordering. Each event includes a random event ID, a session ID and an actor/device ID; the server rejects cross-owner writes. Use HTTPS and do not log credentials.
7. Add the same protocol to a **custom iPad build based on Apple 0.6.7 full release source**; verify that the iPad's player and Simkl scrobbler obey the same replay boundary. Release and install an appropriately signed/sideloaded IPA. An unmodified official 0.6.7 IPA cannot participate.
8. Test Android -> iPad and iPad -> Android sync, offline conflict resolution, profile isolation, historical Simkl integrity, and mixed-version behavior before enabling automatic sync.

## Suggested wire contract (not implemented)

Owner is a stable server-side account/profile principal, linked to each app's local profile separately. `canonicalSeriesId` should be resolved from a known Simkl show ID where possible, with IMDb/TMDB/TVDB aliases maintained server-side. An identity string alone is not enough to infer alias equivalence.

- `GET /v1/rewatches`: read current scoped active sessions and revisions.
- `POST /v1/rewatches/events`: append an idempotent event with `eventId`, `sessionId`, `deviceId`, `expectedRevision`, type (`START`, `PROGRESS`, `COMPLETE_EPISODE`, `STOP`), and episode/position as appropriate.
- `GET /v1/rewatches/{sessionId}`: retrieve authoritative state after conflict; use session revision to reconcile offline changes.
- Responses include `schemaVersion`, `sessionId`, `revision`, `phase`, `episode`, `positionMs`, `lastCompletedEpisode`, and `updatedAt`.
- Never send Simkl OAuth tokens in these payloads. Authenticate clients separately and store minimal replay-state data.

## Open decisions before live integration

- Activation policy: explicit Start Rewatch only versus conservative implicit detection of playing an already-watched episode (risk: one-off replays become permanent CW cards).
- Rewatch of a show that has newly aired, previously unwatched episodes: stop/suspend replay or treat the new episode as a first watch. Avoid silently changing original history.
- Last-episode metadata completeness: validate against authoritative released-episode inventory, not just a partial addon response.
- Conflict semantics when two devices intentionally rewatch different episodes simultaneously.
- Session retention and explicit Stop/Rewatch Again user interface.
