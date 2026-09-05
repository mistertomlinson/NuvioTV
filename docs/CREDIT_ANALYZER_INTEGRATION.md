# Credit analyzer and post-play integration

## Runtime policy

- The analyzer is considered only after playback position reaches 5:00. Seeking
  or resuming beyond 5:00 satisfies the trigger; five minutes of continuous
  playback is not required.
- Episodes query IntroDB first, even when the skip button is disabled. Any
  usable IntroDB interval suppresses VPS analysis for that episode.
- PenguPlay and streams that require custom HTTP request headers use the
  existing percentage/minutes fallback.
- While a VPS job is queued or running, fallback timing is suppressed.
- A completed AI result uses `final_credits_start_ms` for Next Episode,
  autoplay, and recommendations. `credits_start_ms` is diagnostic only and
  never opens post-play UI when post-credit scenes remain.
- Analyzer failure, unavailability, unsupported streams, or a no-credits result
  restores the existing threshold behavior.

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

## Build configuration

Set these values in `local.properties` (or `local.dev.properties` for a debug
build):

```properties
CREDIT_ANALYZER_BASE_URL=https://credit-analyzer.example.com/
CREDIT_ANALYZER_TOKEN=replace-with-the-vps-token
```

The endpoint must be reachable from the Android TV device. A blank endpoint or
token disables the integration and leaves existing timing behavior intact.
