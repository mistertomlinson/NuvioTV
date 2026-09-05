# Credit analyzer and post-play integration

## Runtime policy

- The analyzer is considered only after playback position reaches 5:00. Seeking
  or resuming beyond 5:00 satisfies the trigger; five minutes of continuous
  playback is not required.
- Episodes query IntroDB first, even when the skip button is disabled. Any
  usable IntroDB interval suppresses VPS analysis for that episode.
- PenguPlay and streams that require custom HTTP request headers use the
  existing percentage/minutes fallback.
- While a VPS job is queued or running, legacy percentage timing is suppressed.
  If the VPS has a successful result for another release of the same title or
  episode with a nearby runtime, its conservative runtime-adjusted boundaries
  remain usable until exact analysis finishes.
- A completed AI result uses `final_credits_start_ms` for Next Episode,
  autoplay, and recommendations. `credits_start_ms` is diagnostic only and
  never opens post-play UI when post-credit scenes remain.
- Analyzer failure or a no-credits result retains an available cross-release
  estimate. Without one, analyzer failure, unavailability, or an unsupported
  stream restores the existing threshold behavior.

## Resume and cache behavior

The media key is based on the exact release, in this order:

1. torrent info hash + file index + size;
2. video hash + size;
3. a SHA-256 digest of stable media ID, filename, and size.

Signed URL query parameters are never part of the key. Leaving the player
cancels only app-side polling; it does not call the analyzer cancellation API.
The VPS continues the accepted job. Opening the same release later submits the
same key, allowing the service to return the in-flight job or the completed
SQLite-cached result without analyzing again.

Nuvio also sends a SHA-256 content key derived from the canonical movie ID, or
from the canonical series ID plus season and episode. It contains no title or
stream URL. Successful exact analyses are indexed under that identity. When a
different release is within the VPS runtime tolerance, the service estimates
both boundaries using end-relative and proportional offsets and chooses the
later value to avoid opening post-play UI early. Exact current-release timing
always replaces the estimate when it arrives. A materially different cut does
not receive this fallback.

## Rating handoff

When a connected rating provider is available, movies and the final episode of
a season show the existing rating overlay at `final_credits_start_ms`. Playback
continues underneath the overlay. Next Episode, autoplay, and recommendation
navigation remain blocked until the rating interaction finishes.

Selecting a rating or Dismiss runs the existing staggered exit animation,
reveals the still-playing video, and releases the appropriate post-play action
after a short delay. Rating submission is asynchronous and does not hold the
transition on network latency. Return to Video closes the prompt and suppresses
both Next Episode and recommendations for the remainder of that playback.
Ordinary episodes do not show the automatic rating prompt. The existing
late-playback Back-button rating prompt remains available and also leaves
playback running behind it.

## Build configuration

Set these values in `local.properties` (or `local.dev.properties` for a debug
build):

```properties
CREDIT_ANALYZER_BASE_URL=https://credit-analyzer.example.com/
CREDIT_ANALYZER_TOKEN=replace-with-the-vps-token
```

The endpoint must be reachable from the Android TV device. A blank endpoint or
token disables the integration and leaves existing timing behavior intact.
