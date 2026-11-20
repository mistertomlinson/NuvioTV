# XL catalog rows

Experimental Modern Home presentation on `feat/netflix-xl-catalog-rows-20261118`.
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

The catalog number control now uses matching vector number-sign artwork derived
from its original solid glyph. Its outlined version uses a uniform-width inset outline inside
that same shape, so both versions have identical size, position and outer bounds,
independent of font line height and baseline clipping. This
changes only the setting icon; the actual numbered posters retain Jost and the
existing solid/outline rendering.

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
stationary hero presentation, transitioning to the new ordinary title in place.
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

Ordinary posters now disappear through a 20 dp alpha gradient just above the
metadata cutoff. The resting heading and poster positions are unchanged: pages
without XL gain an equal 20 dp of viewport height, content padding and focus
inset. The gradient moves with the existing ordinary metadata exclusion band
during XL handoffs. A departing XL image still crosses the old logo region
intact. The strip also respects each row's measured bounds: focus-scale and rank
overhang cannot reappear in the empty gap after the preceding row clears it.
Only the narrow fade strip uses an offscreen surface; the backdrop is
preserved beneath its transparent pixels and metadata draws above it. Brushes
and paint are cached, with no full-screen poster-mask texture or per-frame blur.
The default vertical BringIntoView animation, held-scroll velocity (1200 dp/s)
and existing 360 ms following-row headroom adjustment are unchanged.

The space before a following ordinary row is calculated from the XL row's
measured bottom, the viewport, and the ordinary hero's resting position, keeping
the next hero just below the screen instead of leaving a second oversized gap.
On either downward exit, the gap grows only as much as needed during movement:
the next row moves toward its normal position while the entire outgoing XL row
travels off screen. A summary XL starts with its following hero 8 dp below the
viewport, and compact XL starts with its normal next-row peek. The incoming metadata is fully opaque and reveals below the departing XL's
bottom edge as it slides in, independently of backdrop brightness. Neither the
metadata nor the row mask can clip the XL artwork. The gap stays open while
browsing ordinary rows below it, allowing a later upward return to bring the
whole scene down together. Once XL is back in place and outgoing metadata is off
screen, the gap closes to restore the next-row peek.

## Backdrop handoff

XL uses a cached 160 × 90 color field with a light 12%-black tint. A much broader
three-pass, radius-24 box blur removes small details and keeps broad artwork
colors. It uses the same landscape image as the visible card, rather than a
potentially different ordinary fanart image for that title. The XL field fills
the screen in either trailer-layout mode and omits
the ordinary hero's black gradients. Those gradients are restored for ordinary
rows. The fixed blur runs on one background CPU lane, with cancellation checks
between scanlines. It cannot grow with a full-size decode or tall poster fallback.
Coil caches this separately from the original image; no sharp placeholder is
used for a blurred frame. No live GPU blur or animated blur radius is attached
to scrolling artwork, text, trailers or controls.

Horizontal XL changes have **no idle/debounce timer**. The selected destination's
landscape and blurred background are prepared concurrently, with obsolete loads
cancelled immediately. A single presentation commit changes the landscape and
starts a 220 ms background crossfade. Cold images retain the preceding artwork
while loading, bounded at two seconds for failures; input and the portrait
runway remain responsive. Select commits the title currently displayed. Only
one item to the right is speculatively warmed, after the existing quiet lookahead
window; that window never delays an active selection. During double Left the
landscape shuffles normally, while the blur holds until the final destination
is prepared rather than processing every transient image.

Vertical managed handoffs first reach black, commit the appropriate sharp or
blurred image, and wait for the matching renderer's success/error acknowledgement.
Tokens include the image variant so a sharp callback cannot release a blurred
frame using the same URL. A bounded two-second wait protects against a missing
renderer acknowledgement. Entering XL waits for frame/row readiness before
returning to the light 12% tint. Exiting XL immediately commits its sharp destination
behind black and reveals it over a **180 ms linear fade** during row movement,
shorter than v7's 400 ms. Above and below backdrops are prewarmed at hero render
size while on XL; ordinary-to-ordinary debounce is unchanged. The exit's ordinary
metadata is precomposed/measured for the two neighboring ordinary rows while
XL is displayed. Those slots are never placed or drawn until chosen. The chosen
slot retains its loaded logo, and its logo transition is disabled during the
vertical handoff. Text and logo arrive fully opaque. Row ownership survives until
landing so image timing cannot snap moving metadata into place.

On the platform icon row, pages whose first catalog is XL already use the blurred
color field and 12% tint. Moving Down into that XL does not add another darkening animation;
ordinary-first pages keep their normal backdrop. Platform parallax captures
and retains the outgoing page's dim amount until its ghost reaches black. A new
scene cannot brighten an outgoing XL bitmap or abruptly dim an ordinary bitmap.
All platform switches use the original timings/easing again: 150 ms outgoing
fade/travel, 250 ms incoming fade, and 330 ms incoming parallax/travel. There is
no XL-specific slowdown. The outgoing dim snapshot and gradient style remain
pinned until black, so flipping the incoming scene cannot darken the old field
or expose a bright ordinary frame.

## Verification and TV review

Robolectric tests exercise the real XL card and horizontal navigation, plus a
real focusable LazyColumn harness using the production scene controller, hero
metadata, headroom and backdrop timing. They cover synchronized vertical motion,
no retained upper metadata at rest, measured-heading landing without a jump,
full-width balance and preceding-row clearance, tighter following-hero spacing,
reveal before row settlement, a second rapid Up, XL-first platform darkness,
the summary-free exit/return, delayed-frame handoffs, strict double-Left timing,
animated portrait return, and numbered-card anchoring. JVM tests cover
snapshot ownership, recycling, interrupted transitions, gap lifetime, frame
identity, preferences and existing app behavior. These supplement TV review.

On the TV check ordinary → ordinary, ordinary → XL → ordinary in both directions,
summary/no-summary variants, consecutive XL rows, and rapid reversals mid-slide.
Confirm each slow Left moves one title, only rapid double Left jumps to the start,
and a held Left cannot arm a jump after release. Check numbered ranks 1/9/10/100,
corner radius, number styles/theme color, artwork, live trailers and popup/sidebar
return. A slow or unavailable image should remain dark until ready or the bounded
fallback, then reveal the destination metadata and backdrop together.

## Coming Soon badge

XL uses the existing Coming Soon glass, border, font and width/text animations.
It sits below the posters, centered vertically on the first metadata line and
inset 52 dp from the physical screen's right edge, mirroring the left metadata
inset. The extra-width parallax canvas cannot push it off screen. Its overlay
never increases row height, including compact XL. It fades out over 150 ms as
soon as the row loses focus. The ordinary global badge stays suppressed while XL
is active and cannot revive an old XL date from a frozen hero during exit.
