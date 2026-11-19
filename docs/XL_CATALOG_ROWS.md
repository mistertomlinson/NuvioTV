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
very long trips), then changes the featured title. The landscape image, inline
metadata, trailer and Select target stay on the same title until that scroll
finishes. Vertical navigation cancels a running jump without moving focus later.

Numbered XL posters and their lower metadata retain the ordinary 52 dp heading
anchor. Ranks extend into the existing left gutter, with eight dp behind the
poster edge; upcoming posters reserve measured rank gaps. The original Jost
solid/outline renderer and theme color are reused at a smaller size. Long ranks
scale down enough to fit the gutter rather than pushing the featured card right.
Their baseline sits slightly below the poster bottom, following the ordinary
number layout; the artwork viewport allows that lower ink and outline to draw.

## Vertical presentation

`XlSceneState` owns each XL boundary explicitly. It captures the metadata block
that was actually presented before the focus change, including its preview and
scroll position. That snapshot moves with measured LazyColumn displacement;
virtualizing its original row cannot replace it with another row's metadata.
Rapidly crossing an XL row preserves any still-visible original outgoing
snapshot. A second ordinary-row focus after an XL exit instead resumes the
stationary hero presentation, transitioning to the new ordinary title in place.
Returning directly to that same visible owner preserves its logo composition.
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

The space before a following ordinary row is calculated from the XL row's
measured bottom, the viewport, and the ordinary hero's resting position, keeping
the next hero just below the screen instead of leaving a second oversized gap.
On either downward exit, the gap grows only as much as needed during movement:
the next row moves toward its normal position while the entire outgoing XL row
travels off screen. A summary XL starts with its following hero 8 dp below the
viewport, and compact XL starts with its normal next-row peek. The incoming metadata reveals below the departing XL's bottom edge,
using the backdrop's fade clock as soon as its frame is ready. Neither the
metadata nor the row mask can clip the XL artwork. The gap stays open while
browsing ordinary rows below it, allowing a later upward return to bring the
whole scene down together. Once XL is back in place and outgoing metadata is off
screen, the gap closes to restore the next-row peek.

## Backdrop handoff

XL uses a faint backdrop under a 94%-black layer. Each managed image handoff
first reaches black, commits the new backdrop without a second image crossfade,
and waits for the target image renderer's success/error acknowledgement. A
bounded two-second wait avoids stranding the screen if a renderer never replies.
A stale outgoing frame cannot release the new handoff.

Entering XL waits for the new frame and row landing before returning to 94%
black. Exiting XL immediately commits its destination behind the dark layer,
waits only for that image's frame, and fades to the ordinary backdrop while rows
are still scrolling. Above and below backdrops are prewarmed at hero render size
while on XL; ordinary-to-ordinary debounce is unchanged. The exit's ordinary
metadata uses the same opacity clock. Row ownership survives until landing so
finishing the image fade cannot snap moving metadata into place.

On the platform icon row, pages whose first catalog is XL already use the 94%
dark layer. Moving Down into that XL does not add another darkening animation;
ordinary-first pages keep their normal backdrop. The new metadata composition
starts with the destination logo, so its internal logo crossfade cannot retain
the previous XL/ordinary title. Platform navigation keeps its existing separate
whole-page transition.

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
