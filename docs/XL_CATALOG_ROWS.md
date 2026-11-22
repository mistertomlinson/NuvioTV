# XL catalog rows

Modern Home XL spring landing on `fix/xl-spring-landing-20261122`, based on
`1496accd` Gentle Spring. The TV-approved ordinary spring and 1.10 time multiplier
remain the speed baseline. This revision waits for the confirmed focus frame to
draw before moving, eases the compact incoming row over its short range on that
same spring clock, and shares a whole-physical-pixel landing anchor with framework
relocation. Backdrop/cache fixes, edge fade, prepared metadata, preview returns,
CW geometry and hollow numbers are retained. Physical TV frame pacing still
requires review; native rendering tests cannot establish device smoothness.
Catalog Management uses one poster control: Portrait → Landscape → XL with
summary → XL without summary → Portrait. Portrait is the muted default icon;
landscape and either XL choice are white, including while focused. The portrait
photo frame is slightly taller and contains scaled mountain/sun artwork. XL
reuses the existing landscape photograph artwork at a larger size, with its
right portrait strip and optional horizontal summary strip.

The four choices are saved atomically and cannot retain conflicting row
landscape/XL overrides. Existing landscape and XL choices migrate naturally;
XL takes precedence when both legacy flags were set. Explicit portrait overrides
the global landscape default for that row without changing other rows or the
global preference. Group keys remain stable for Seasonal Spotlight and Watchly.
Rapid queued presses cycle the stored value inside the DataStore transaction.

Both XL variants retain metadata lines beneath the selected landscape image and
omit its title/logo slot. No-summary mode removes the synopsis and its reserved
height so the next row can peek through. Preferences remain per profile.
XL's featured card retains its title logo over an expanded-card trailer because
its lower metadata omits that logo. The regular per-catalog landscape rule still
hides its resting branding after the first video frame; universal landscape
behavior is unchanged.

The catalog number control now uses matching vector number-sign artwork derived
from its original solid glyph. Its outlined version uses a uniform-width inset outline inside
that same shape, so both versions have identical size, position and outer bounds,
independent of font line height and baseline clipping. This
changes only the setting icon; the actual numbered posters retain Jost and the
existing solid/outline rendering. Both XL number options now omit the added dark
contrast contour. Solid numbers retain their original gray/theme fill. Outlined
numbers now draw only their light stroke, with transparent interiors on both
regular and XL rows. Font, stroke, geometry and horizontal number fades remain.

## Focus and numbering

Physical focus stays on one permanent featured card while Left/Right change its
title. Portrait previews cannot take focus. The complete existing card renderer
retains Select, long press, details/options/sidebar return, configured corners,
focus borders, trailers, mute/delay and pagination behavior. XL geometry stays
fixed while trailers load or play.

Double Left now uses the ordinary row shortcut's 80 ms release-to-next-press
window, measured with native input timestamps. Both presses must be quick taps;
a held press/repeat cannot arm the next fresh Left as a shortcut. Slow Lefts
remain individual moves. A fresh Left at item zero can still open the sidebar.
The shortcut scrolls a bounded portrait runway at ordinary-row speed (compressing
very long trips), shuffling the landscape artwork through the same titles.
Trailers pause during the jump. Select/long press commits the landscape title
currently shown before the permanent card handles the action. Vertical navigation
cancels a running jump without moving focus later.

Only the next landscape image to the right is preloaded, using the featured
card's exact URL, size, Crop scale and inexact decode policy. An 80 ms quiet
window conflates held presses. Coil loads on the background path; leaving the row,
changing selection, or starting a jump cancels that request. No left lookahead
or wide landscape window is added.

Numbered XL posters and their lower metadata retain the ordinary 52 dp heading
anchor. Ranks extend into the existing left gutter, with eight dp behind the
poster edge; upcoming posters reserve measured rank gaps. The original Jost
solid/outline renderer and theme color are reused at a smaller size. Long ranks
scale down enough to fit the gutter rather than pushing the featured card right.
Their baseline sits slightly below the poster bottom, following the ordinary
number layout; the artwork viewport allows that lower ink and outline to draw.
Portraits and numbers share one animated runway. All unaffected numbers remain
fully opaque as they slide. A passing number stays attached to its own portrait,
with opacity based on how much of that portrait remains outside XL. A 12 dp
horizontal alpha feather softens the XL edge, including the lower overhang, so
no ink slides under the landscape and no hard number clip is visible. Incoming
opacity already follows the emerging portrait before the glyph clears XL; there
is no extra fade-in timer after the poster arrives. No number parks or relocates
independently. The crossing portrait stays in the runway until it is physically
covered by XL, retaining the image that hides the number's overlapping strip.
Removing that image too early cannot expose the whole number. Both solid and
outline glyphs use a small glyph-sized layer only while fading; other numbers
draw normally. This applies to single moves, held keys and the animated shortcut.
The featured rank derives directly from the displayed title. Shortcut completion
commits the first title and its rank together, avoiding a stale number from a
separate delayed selection index. Native tests compare the rendered first glyph
before/after the shortcut in both solid and outline styles.

## Vertical presentation

`XlSceneState` owns each XL boundary explicitly. It captures the metadata block
that was actually presented before the focus change, including its preview and
scroll position. That snapshot moves with measured LazyColumn displacement;
virtualizing its original row cannot replace it with another row's metadata.
Rapidly crossing an XL row preserves any still-visible original outgoing
snapshot. A second ordinary-row focus after an XL exit instead resumes the
stationary hero presentation. It holds the actually painted first ordinary block
until the normal resolved/debounced hero reaches the latest selected row, then
transitions to that title in place. A rapid second Down during the exit retains
XL clearance/fade ownership until completion; it cannot reveal metadata early
or reset an already running metadata fade. This also applies when the immediate XL exit
has already completed. Older intermediate results cannot release the hold.
Returning into XL during that hold carries the visible snapshot, even if its
owner differs from the most recent logical focus.
Returning directly to that same visible owner preserves its logo composition.
Reversals retain the metadata's painted clip as well as its position, so a
prepared but hidden logo cannot flash into view over the departing poster.
At rest, an XL row has only its own inline metadata. The ordinary hero block is
unmounted, so a retained logo cannot remain behind the XL image.

On entering XL from an ordinary row, the outgoing ordinary block and posters
move together. On leaving XL upward, the incoming ordinary block is attached to
its row's measured header position. During XL motion a moving exclusion mask keeps older posters out of the
travelling ordinary hero space. It never uncovers a hidden row behind that
metadata. Outside that moving space the catalog clips only at the screen edge,
so an outgoing XL image can cross the former logo area intact. Measured title
heights share the poster focus anchor with moving metadata, preventing a final
few-pixel correction when the transition completes. In full-width platform mode,
the summary row is centered between the selector's bottom (42 dp) and the screen
bottom. Compact XL keeps that same poster anchor while exposing the following
row. Each non-first XL row reserves enough prefix to place preceding posters
entirely above the screen at landing. Ordinary-to-ordinary
navigation keeps the stationary upper metadata and ordinary clipping.

The poster top-edge alpha fade is restored using the cached, narrow-strip design.
Poster bodies retain their display lists; only the 20 dp feather strip needs a
destination-in surface. A 12 dp completely clear margin prevents lower slivers
with short headings. The viewport itself draws once. Ordinary-only pages keep
their original 52% portrait / 49% landscape viewport, zero extra top padding and
40 dp poster focus anchor. The feather fits above that poster anchor without
changing list geometry. XL retains its moving metadata exclusion and full-screen
canvas; its own large artwork does not receive the ordinary body fade.

The default ordinary-row BringIntoView animation and held-scroll velocity
(1200 dp/s) are unchanged. All owned XL boundaries use the exact ordinary-row
spring at their actual travel distance. Only that animation's duration scale is
multiplied by 1.10, including the existing system animation scale. This restores
the familiar quick movement and decelerating spring tail with a small slowdown.
The rejected poster-size duration calculation, eased tween and reference-stride
bookkeeping are removed. List and changing prefix/gap still share one clock.
Metadata fades and the 360 ms preview return have independent clocks and are
not stretched. A reversal after XL has already left the viewport restores any
unfinished ordinary-hero clearance gap with the return scroll; the preview then
takes gap ownership once that metadata clears. Ordinary-only scrolling, platform timing and shortcuts are unchanged.
The parent arms a boundary guard before focus transfer so a framework relocation
cannot move first and then hand off to a fresh controller. Actual focus arms
the owned motion. Before any displacement, the controller waits for the row
viewport to draw the confirmed focus state; a two-frame fallback releases a
detached/offscreen viewport. The draw signal is transition-specific and canceled
with interrupted motion, so stale focus frames cannot release a newer slide. If logical selection preceded a recycled row's measurement,
actual focus retries its motion preparation using the now-measured layout. The
controller suppresses competing relocation until completion or the next focus
change. Unsuccessful focus intent is released at key-up; fast scrolling and the
Double-Up shortcut clear it before taking ownership.

Ordinary metadata uses a simple Box instead of the unconditional
SubcomposeLayout introduced in v6. XL still premeasures adjacent metadata;
movable content carries the same loaded logo/state into the ordinary layout.
The existing prepared layout host is retained through the complete boundary and
metadata fade, and released to the simple Box only after the handoff. Activating
an already loaded neighbor cannot replace its layout host mid-slide.
Following-row preview-gap closure retains its existing 360 ms tween. Compact
exit gap/list movement shares one clock, without a separate gap tween chasing
focus relocation. Prepared metadata is measured even while hidden. Its measured
height determines the resting hero position before promotion, and the moving
layer records its actually applied translation. The first ordinary row uses a
reachable measured-header anchor when its heading is shorter than 40 dp,
preventing a final correction at the beginning of the list.

The space before a following ordinary row is calculated from the XL row's
measured bottom, the viewport, and the ordinary hero's resting position, keeping
the next hero just below the screen instead of leaving a second oversized gap.
The minimum exit clearance includes the measured heading correction as well as
the preview-gap calculation; a short heading cannot strand the XL tail a few
pixels on screen and prevent the metadata fade from starting.
On a downward summary-XL exit, the next row and its already loaded metadata move
up together at full opacity while the outgoing XL artwork clears the screen.
There is no metadata fade for this style. A summary XL starts with its following
hero 8 dp below the viewport.

Compact XL starts with its normal next-row peek. On Down, the incoming row
follows the same spring progression over its own shorter travel distance. The
intervening gap grows by the difference between the two distances, letting XL
continue off screen while the incoming row decelerates onto its ordinary poster
anchor instead of hitting a clamp. The list, measured gap, metadata translation
and BringIntoView target all use the same whole-physical-pixel anchor; ownership
release must not trigger a one- or two-pixel correction. On this compact downward exit, the incoming metadata stays invisible until the entire
outgoing XL row is above the screen and the incoming row has landed, then starts
a 220 ms fade at its resting position. The departing XL's lower edge masks any overlap, preserving
its complete image. Gap and travel are quantized together to physical pixels,
avoiding direction reversals or a late spring correction. Changing focus cancels
the shared motion and retains the actual current gap for a continuous reversal.
An interrupted exit retries the selected row's relocation after cancellation
releases the list. A partly expanded gap regains the outgoing hero's clearance
while returning to XL, and its metadata follows the measured gap displacement.

The gap stays open while browsing ordinary rows below XL, allowing a later
upward return to bring the scene down together. On Up into compact XL, the
outgoing row keeps its current gap and moves with its metadata until that block
is offscreen. A hidden early gap tween no longer consumes the preview's return
behind the travelling metadata mask. Once the mask clears, the scene-owned
preview starts a full 360 ms slide from just below the screen to its final peek.
It compensates for remaining list travel and retains any remaining gap until
the longer XL entry has stopped. It never forces that gap shut mid-slide.
Row recycling cannot restart this clock at its final position. Numbered and
unnumbered rows use the same geometry, including portrait and landscape ordinary
rows. The preview can start while XL is still approaching its resting position.
A partly completed compact-exit reversal first preserves its original clearance
to prevent a clipped logo from appearing over XL. Down into XL retains the
measured outgoing-metadata clearance trigger. Neither direction waits for the
backdrop before returning the preview.
Cleared outgoing metadata stays unmounted while the preview returns. If a short
block leaves upward before its reserved hero space clears, that remaining space
still excludes older posters until it too is offscreen.

Continue Watching keeps the standard 24 dp spacing before XL while CW is
selected. Its actual landscape, wide or portrait card height determines the
boundary geometry. Entering XL expands the required prefix with list travel;
returning up to CW removes that prefix on the same clock. This avoids an
extra portrait-sized gap and a late metadata position correction.

Double-Up captures the actually painted ordinary metadata and its mask, then
moves both with the outgoing rows during the existing fast return. Intermediate
focus callbacks cannot promote a stationary hero over the first XL poster.
Landing commits the first scene and clears any stale compact-preview expansion
before ordinary selection updates resume. The shortcut's 2000 dp/s speed is
unchanged.

## Backdrop handoff

XL uses a cached 160 × 90 color field with a light 12%-black tint. A much broader
three-pass, radius-24 box blur removes small details and keeps broad artwork
colors. It uses the same landscape image as the visible card, rather than a
potentially different ordinary fanart image for that title. The XL field fills
the screen in either trailer-layout mode. The ordinary hero's left/bottom
gradient now shades each blurred frame as an experiment controlled by
`XL_BLUR_GRADIENT_PREVIEW`; disabling that switch removes only this shading.
Only blurred frames use the modestly lighter 0.92 gradient opacity; ordinary
backdrops retain their existing gradient strength. Per-draw alpha modulation preserves the lighter tint. The existing XL frame
layer retains the complete image/gradient in an offscreen surface at full
opacity too, so moving/fading the page can reuse that rasterized composite.
The gradient dissolves with its own image rather than changing after it lands. The fixed blur runs on one background CPU lane, with cancellation checks
between scanlines. It cannot grow with a full-size decode or tall poster fallback.
Coil caches this separately from the original image; no sharp placeholder is
used for a blurred frame. The frame's scale and alignment are fixed before its
first crossfade, independently of ordinary overscan membership and poster
fallback changes. Its request dimensions stay 160x90 through later hero
measurements, so an incoming field cannot settle into a second crop or scale.
Blurred platform artwork uses the existing 110% canvas at a fixed scale of 1,
retaining 5% overhang on each side. The outer canvas applies platform parallax
once, so the image cannot consume that overhang through duplicate translation.
No live GPU blur or animated blur radius is attached
to scrolling artwork, text, trailers or controls.

Platform entry, idle platform warming and the existing small vertical-neighbor
window select the actual artwork variant: XL uses the landscape artwork and
its renderer's exact cached 160x90 blur request; ordinary rows keep sharp hero
render dimensions. A sharp cache entry is not treated as a prepared blur.
Platform entry joins that matching preload under the outgoing captured page,
using the existing timeout and timings. Idle warming remains serial, pauses
through pending/active platform transitions and row scrolling, and is keyed by
URL plus variant; live XL preference updates are
read when selecting a destination. No wider preload window is added.

Horizontal XL changes have **no idle/debounce timer**. The selected destination's
landscape and blurred background are prepared concurrently, with obsolete loads
cancelled immediately. A single presentation commit changes the landscape and
starts a 220 ms background dissolve. The outgoing composite stays underneath
while the incoming image fades over it; fading both images toward transparency
would let black leak through at the midpoint. A rapid retarget freezes the
current blend until the new image has faded over it. Movable content preserves
the existing image painters when they become part of the retained blend.
Cancelled zero-opacity targets are discarded immediately. Multiple prepared
opaque blur fields are blended on a background worker into one 160x90 retained
field before adding the next incoming image. A float RGB sidecar avoids repeated
8-bit rounding darkening during rapid retargets. This bounds prepared blur-only
transitions to one retained field plus one incoming image; mixed sharp/blur
transitions retain their existing exact composite. Old frames are discarded
when the latest fade finishes. No new window capture, GPU readback or live blur
is used. Cold images retain the preceding artwork
while loading, bounded at two seconds for failures; input and the portrait
runway remain responsive. Select commits the title currently displayed. Only
one item to the right is speculatively warmed, after the existing quiet lookahead
window; that window never delays an active selection. During double Left the
landscape shuffles normally, while the blur holds until the final destination
is prepared rather than processing every transient image.

Entry into XL from either direction keeps the current ordinary backdrop visible until the
matching landscape and blur are prepared, then starts a direct 220 ms crossfade
while the row is moving. It has no black stage, idle debounce or row-settle wait.
The ordinary image and gradient remain in the outgoing frame, and the XL field
with its experimental gradient fades over that exact composite. An outgoing compact raw image retains
its captured dimensions and layout inside the new full-screen XL canvas. The
image transition is owned outside the two trailer-layout containers, so moving
from a letterboxed hero into XL does not destroy its outgoing bitmap or reset
the crossfade.

Exiting XL immediately commits its sharp destination and gradient for a direct
**180 ms linear dissolve** over the retained blurred composite during row
movement. No black stage is inserted. A cold image keeps that composite until
its renderer is ready, with a bounded failure fallback. The compact target has
its final width, alignment and gradient coordinates inside the full XL canvas,
so releasing the canvas after the dissolve cannot alter its crop. Above and below backdrops are prewarmed at hero render
size while on XL; ordinary-to-ordinary debounce is unchanged. The exit's ordinary
metadata is precomposed/measured for the two neighboring ordinary rows while
XL is displayed. Those slots are never placed or drawn until chosen. The chosen
slot retains its loaded logo, and its logo transition is disabled during the
vertical handoff. Upward and summary-down exits keep sliding text/logo fully
opaque. Compact downward exit keeps prepared metadata invisible until all XL
row pixels leave and the incoming row is parked, then uses its original 220 ms
fade. This alpha animation is independent of the longer XL slide and adds no
extra debounce after clearance. The scene keeps metadata ownership until that
fade finishes to avoid an opacity snap. Row ownership survives until
landing so image timing cannot snap moving metadata into place.

On return from Details, the saved row key and selected title seed the XL scene
before its first composition. This prevents the temporary default first row
from starting an entry fade behind the existing frozen-Details return overlay.
An initial or restored XL backdrop starts at its final tint and image opacity;
only a real row/title change starts a new dissolve. The existing navigation
snapshot/draw handshake and 350 ms Details dissolve are unchanged.

On the platform icon row, pages whose first catalog is XL already use the blurred
color field and 12% tint. Moving Down into that XL does not add another darkening animation;
ordinary-first pages keep their normal backdrop. Platform parallax captures
and retains the outgoing page's dim amount until its ghost reaches black. A new
scene cannot brighten an outgoing XL bitmap or abruptly dim an ordinary bitmap.
All platform switches use the original timings/easing again: 150 ms outgoing
fade/travel, 250 ms incoming fade, and 330 ms incoming parallax/travel. There is
no XL-specific slowdown. Media receives parallax as state and reads it only
inside its graphics layer; the Home composition no longer reads an animated
Float for every platform frame. The outgoing dim snapshot and gradient style remain
pinned until black, so flipping the incoming scene cannot darken the old field
or expose a bright ordinary frame.

## Verification and TV review

Robolectric tests exercise the real XL card and horizontal navigation, plus a
real focusable LazyColumn harness using the production scene controller, hero
metadata, headroom and backdrop timing. They cover synchronized vertical motion,
no retained upper metadata at rest, measured-heading landing without a jump,
full-width balance and preceding-row clearance, tighter following-hero spacing,
reveal before row settlement, a second rapid Up with normal metadata debounce,
hard metadata exclusion of scaled poster bottoms with short headings,
a real loaded blur staying pixel-identical after late ordinary measurements,
10% parallax overhang without exposed screen edges, compact two-phase travel,
compact metadata fade only after landing/clearance, visible preview return while artwork is cold,
original XL number styles/fades, loaded-logo reuse through the simple ordinary host,
direct entry in either direction before row settlement, delayed pair readiness
without black, constant RGB brightness throughout real-image dissolves and
rapid reversal, a single viewport draw pass, restored XL focus without a new
fade, XL trailer logo policy,
concurrent gradient fading and platform alpha pinning, XL-first platform darkness,
the summary-free exit/return, delayed-frame handoffs, strict double-Left timing,
animated portrait return, and numbered-card anchoring. JVM tests cover
snapshot ownership, recycling, interrupted transitions, gap lifetime, frame
identity, preferences and existing app behavior. A separate integration suite mounts actual ModernHomeContent and real catalog
cards for numbered/plain, summary/compact, first/middle XL, ordinary landscape,
full/half-width icons and XL-disabled pages. Native drawing checks verify a
single viewport draw, unchanged row anchors, a spatial poster-edge alpha ramp,
no lower slivers and hardware display-list reuse. Additional checks compare XL
pixel pace to the ordinary spring, reject a visible mid-exit position stall,
retain the prepared metadata layout host and verify the original compact fade
cadence. Native bitmap captures also check final metadata pixels. A counting Coil transformation
checks cache reuse and worker-thread execution. These supplement TV review and
do not measure physical TV frame pacing.

On the TV check ordinary → ordinary, ordinary → XL → ordinary in both directions,
summary/no-summary variants, consecutive XL rows, and rapid reversals mid-slide.
Confirm each slow Left moves one title, only rapid double Left jumps to the start,
and a held Left cannot arm a jump after release. Check numbered ranks 1/9/10/100,
corner radius, number styles/theme color, artwork, live trailers and popup/sidebar
return. A slow or unavailable image should retain the outgoing backdrop until ready or
the bounded fallback. Metadata follows its row-motion policy independently.

## Coming Soon badge

XL uses the existing Coming Soon glass, border, font and width/text animations.
It sits below the posters, centered vertically on the first metadata line and
inset 52 dp from the physical screen's right edge, mirroring the left metadata
inset. The extra-width parallax canvas cannot push it off screen. Its overlay
never increases row height, including compact XL. It fades out over 150 ms as
soon as the row loses focus. The ordinary global badge stays suppressed while XL
is active and cannot revive an old XL date from a frozen hero during exit.
