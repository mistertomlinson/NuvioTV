# Home badge exit and trailer teardown audit — October 9, 2026

## Badge correction

Haze 0.7.3 draws its native blur region on the Home source, independently of the badge's AnimatedVisibility alpha. Matching the radius animation to the shell's fade did not make them share opacity; it could leave a blur-only ghost.

The badge now detaches its Haze child when the Coming Soon label becomes inactive. The grey shell and text retain their existing 300 ms exit fade. Entrance strength, active glass appearance, horizontal date changes, and popup label retention remain intact. This removes the blur immediately rather than attempting to fade native blur opacity, which this Haze version does not expose.

The separate badge-only candidate was built and installed on the onn 4K Pro, AOT compiled with speed, force-stopped after Success, relaunched, and explicitly tested on Brandon. Screen recordings covered Movies Coming Soon ↔ Night of Terror and Trending Movies, rapid row reversals, and horizontal title changes. Series Coming Soon and the adjacent inactive row were also checked. The badge returns and disappears correctly; the native blur region is no longer registered through the inactive shell's exit.

## Home trailer change

Pinned official beta 0.9.5 (62e1d8b16) reuses its trailer player. Enhanced already retains a hero player, but expanded Home cards were creating and releasing their own player. Media3 1.10.0-rc01's ExoPlayerImplInternal.release sends MSG_RELEASE and waits for playback-thread teardown. That wait runs on the player's application thread, which is the UI thread here.

The change adds one separate lazy player for expanded Home cards. It initializes only when a valid expanded-card trailer actually starts. On departure the existing stop/clear path removes the media, listener, and lifecycle observer; the removed PlayerView additionally detaches its TextureView and key callback. Media3 checks TextureView identity before clearing a surface, protecting a newer card's view. The player object survives for reuse. Hero and popup players remain separate, and existing autoplay, audio, seeking, and preservation policies are unchanged.

This removes repeated player construction and synchronous full release. It does not remove every codec stop or surface-detachment cost, and it does not explain jank during ordinary scrolling before trailers start. The previously retained vertical-prefetch patch remains included.

## Measurement procedure

Three owned-player baseline traces used the same two vertical moves between Dystopian Worlds Colliding (V for Vendetta) and Suspenseful Cosmic Encounters (The Answer). Screenshots immediately before each key confirmed video had rendered in the expanded card. Each measurement window begins at the first key dispatch and lasts one second. No sidebar, blurred Coming Soon row, or adjacent row occurs in these windows. Screen recordings were used separately for the badge and did not run during the traces.

Baseline longest Compose disposal slices (ms): Down 50.23 / 48.88 / 42.21; Up 23.42 / 22.39 / 21.55. These are whole Compose:onForgotten slices and include disposal work beyond the ExoPlayer release itself. 

## Release results and interpretation

Three runs per build, with two actual playing-trailer departures per run:

| Mean of each move’s longest slice/frame (ms) | Owned player Down | Reused player Down | Owned player Up | Reused player Up |
|---|---:|---:|---:|---:|
| Compose:onForgotten disposal | 47.11 | 15.88 | 22.45 | 8.39 |
| Main-thread Choreographer frame | 96.14 | 61.52 | 57.14 | 51.86 |

Downward longest disposal slices were 50.23 / 48.88 / 42.21 ms before and 14.01 / 16.08 / 17.55 ms after. Upward values were 23.42 / 22.39 / 21.55 ms before and 7.88 / 8.04 / 9.26 ms after. Compose disposal includes work beyond player release, so this is not a measurement of release() alone.

App Deadline Missed counts in these six one-second windows were **24/297 frames (8.08%) before and 32/288 (11.11%) after**. Overall missed-frame counts did not improve in this small sample. Retain the scoped change for the verified removal of the synchronous full-release barrier and the shorter worst vertical UI frames, without claiming a general frame-rate improvement. Codec teardown, texture-surface detachment, and layout still cost time. Ordinary scrolling before autoplay is a separate case; the retained e52ea317a prefetch change remains intact.

Player logs confirmed one hero instance and one reusable expanded-card instance across repeated Home card moves, rather than repeated Home player Init/Release cycles. Screenshots before all six candidate key presses confirmed the expected video was rendering. No screen recording or sidebar transition occurred during these performance windows.

## Regression checks

- Incremental release assembly succeeded; all 253 existing unit tests passed, with zero failures, errors, or skips. Popup-preservation, trailer session cache, post-play trailer behavior, Home cache, catalog, focus, and badge tests are included.
- Vertical V for Vendetta ↔ The Answer autoplay passed repeatedly. Horizontal Rise of the Planet of the Apes ↔ V for Vendetta playback and replay passed.
- A rendered trailer progressed behind the options popup. Closing it retained the correct card. Sidebar opening retained the expanded video; D-pad Right restored the same focused card. Sidebar blur transitions were excluded from the measurements.
- Details → Back restored V for Vendetta at the first card in Dystopian Worlds Colliding, with the expected horizontal position.
- Android Home backgrounding removed all Enhanced AudioPlaybackConfiguration entries; reopening and changing focus successfully resumed trailers. Normal sidebar Back exits the app by its unchanged MainActivity handler; reopening and selecting Brandon also passed.
- Coming Soon exit, quick return, and date changes passed on the separate badge candidate; the same badge fix was rechecked in the combined release. Movie and series badge appearances remain present.
- Only Home expanded-card player ownership and removal cleanup changed. Hero, popup, full playback, credits, login, watch-state, and focus/preservation policies were not rewritten.

## Tested APK

`Nuvio-Enhanced-home-trailer-badge-20261009-universal.apk`

SHA-256: `e7f842834db2172860d60d7679701395248eeb4dda3b0ac010c7df97878da758`

Package: `com.nuvio.tv.sideload`, version 38 / 0.4.20-beta. Signing certificate SHA-256: `ab0f234a8bfacc174676e84c7623c7f77efeacff7f85f7ec13c5af5d4e809e9c`.

Install → AOT speed Success → force-stop → launch → explicitly select Brandon was followed. The installed APK hash and `[status=speed]` were independently verified. The provided installer and verifier use this exact APK fingerprint. The APK includes all previously retained scrolling patches.

## Build isolation

All changes remain on codex/scroll-jank-audit-20261007 in the isolated worktree. Primary dev at 99205ec70 is unchanged and clean. Current primary local.properties and local.dev.properties match byte for byte, verified without exposing values. Only com.nuvio.tv.sideload is installed or compiled. No settings, profile configuration, watch state, or full movie playback is changed for testing.
