# Persistent playback diagnostics (diagnostic branch only)

This instrument-only branch is intended to explain reports such as "The Office S6E10"
with a Playing badge for the Survivor release `S06E10-Amazon Redux.avi`,
while the actual picture appears to be The Office S9E15.

It does **not** change the selected episode, stream ranking, TorBox file selection,
stream cache settings, Nuvio rewatch behavior, or Streaming Originals refresh.

## Logged transitions

- App/process session, timestamp and monotonically increasing sequence number
- Initial Continue Watching/episode playback target and link identity
- Stream-screen request, add-on candidate metadata (up to 50 per completed result), autoplay selection, retries
- Last-link reuse hit, source/episode switch requests, selected link cache writes
- In-player next-episode target, chosen candidate, rejected candidates, and handoff
- TorBox create status, torrent ID, video-file candidates, chosen file ID/name, and returned link fingerprint
- Player init, ready/buffering/ended state transitions, first frame and playback errors
- Sources panel Playing badge matched row, original filename, stream key/hash/file-index evidence

Identifiers can be correlated by timestamps, episode IDs, TorBox torrent ID,
filename and SHA-256 fingerprints of URLs / infohashes. Raw playback URLs,
bearer credentials, request headers, API keys and magnet links are not
persisted by this logger. Avoid publishing exported logs publicly because
release names and viewing history are still included.

**Limitation:** the TV video frames are not analyzed. If a release is
mislabelled, logs establish which link and TorBox file were selected but
cannot independently tell what scenes are inside it. A separate TorBox/VLC
preview is required to confirm the footage.

## Storage and retrieval

Two rolling JSONL files, each approximately 512 KiB, are kept under
`Android/data/<applicationId>/files/diagnostics/` on the device's shared
external storage. The total storage budget is approximately 1 MiB.
Files survive ordinary app restarts and device reboots but not clearing app
data or uninstalling the app. Older entries can rotate out before seven days
if many stream candidates are logged; files inactive for seven days expire.

For the downstairs device, from the iMac with wireless ADB:

```bash
adb -s 192.168.50.83:5555 shell pm list packages | grep nuvio
adb -s 192.168.50.83:5555 pull /sdcard/Android/data/com.nuvio.tv.sideload/files/diagnostics/playback-current.jsonl "$HOME/Desktop/nuvio-playback-current.jsonl"
adb -s 192.168.50.83:5555 pull /sdcard/Android/data/com.nuvio.tv.sideload/files/diagnostics/playback-previous.jsonl "$HOME/Desktop/nuvio-playback-previous.jsonl"
```

The second file may not exist until the first rotation; that is normal.
If the build uses a different application ID, replace `com.nuvio.tv.sideload`.

No log extraction is possible from the VPS unless it has a path to the TV;
these files are stored locally on the Android device.

## Reproduction

1. Note the requested show and episode.
2. Allow autoplay or use Continue Watching normally.
3. If the wrong video appears, note what actually appears on screen.
4. Open Sources once and note the row marked Playing, without switching yet.
5. Collect both diagnostic files. You may also collect ordinary `adb logcat`
   for player/debrid exceptions not included in these breadcrumbs.

Look for `stream_screen_autoplay_selected` or `next_episode_selected`,
`torbox_file_selected`, `torbox_link_resolved`,
`episode_stream_handoff`, `player_initialize`, `first_frame`,
and `playing_indicator_match`. Compare `linkHash` values to establish
whether the same resolved link reached the player.

## Testing

The branch must pass compilation and the targeted JUnit test
`PlaybackTraceTest` before it is installed or merged. A completed build/CI
run has **not** been claimed by this documentation.
