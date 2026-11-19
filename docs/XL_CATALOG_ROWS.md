# XL catalog rows

Experimental Modern Home presentation on `feat/netflix-xl-catalog-rows-20261118`.
Catalog Management cycles Off → XL with summary → XL without summary → Off.
The no-summary icon omits the lower horizontal bar. Both XL variants retain
metadata lines beneath the selected landscape image and omit the title/logo
slot there. No-summary mode removes the synopsis and its reserved height so the
next row can peek through. Preferences are per profile; existing XL preferences
continue to mean summary enabled. Seasonal/Watchly replacement keys stay stable.

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

Numbered XL posters and their lower metadata retain the ordinary 52 dp heading
anchor. Ranks extend into the existing left gutter, with eight dp behind the
poster edge; upcoming posters reserve measured rank gaps. The original Jost
solid/outline renderer and theme color are reused at a smaller size. Long ranks
scale down enough to fit the gutter rather than pushing the featured card right.

## Vertical presentation

`XlSceneState` owns each XL boundary explicitly. It captures the metadata block
that was actually presented before the focus change, including its preview and
scroll position. That snapshot moves with measured LazyColumn displacement;
virtualizing its original row cannot replace it with another row's metadata.
A second direction press carries any still-visible outgoing snapshot forward;
it cannot disappear merely because focus has already crossed an XL boundary.
Returning directly to that same visible owner preserves its logo composition.
At rest, an XL row has only its own inline metadata. The ordinary hero block is
unmounted, so a retained logo cannot remain behind the XL image.

On entering XL from an ordinary row, the outgoing ordinary block and posters
move together. On leaving XL upward, the incoming ordinary block is attached to
its row's measured header position. During XL motion a moving exclusion mask keeps older posters out of the
travelling ordinary hero space. It never uncovers a hidden row behind that
metadata. Outside that moving space the catalog clips only at the screen edge,
so an outgoing XL image can cross the former logo area intact. A 30 dp inset
before a non-first XL row ensures the preceding row clears y=0 when XL lands. Ordinary-to-ordinary
navigation keeps the stationary upper metadata and ordinary clipping.

An ordinary row following a summary XL row has real space for its upper metadata.
For a summary-free downward exit, that space grows during the movement: the next
row moves toward its normal position while the entire outgoing XL row travels
off screen. The incoming ordinary metadata remains transparent until the rows
land and the incoming backdrop frame is available. The gap stays open while
browsing ordinary rows below it, allowing a later upward return to bring the
whole scene down together. Once XL is back in place and outgoing metadata is off
screen, the gap closes to restore the next-row peek.

## Backdrop handoff

XL uses a faint backdrop under a 94%-black layer. Each managed image handoff
first reaches black, commits the new backdrop without a second image crossfade,
and waits for the target image renderer's success/error acknowledgement. A
bounded two-second wait avoids stranding the screen if a renderer never replies.
A stale outgoing frame cannot release the new handoff.

The backdrop then fades to 94% black for XL, or to the ordinary background when
leaving. Row landing and image readiness gate this reveal. A summary-free exit's
ordinary metadata uses that same opacity clock. The new metadata composition
starts with the destination logo, so its internal logo crossfade cannot retain
the previous XL/ordinary title. Platform navigation keeps its existing separate
whole-page transition.

## Verification and TV review

Robolectric tests exercise the real XL card and horizontal navigation, plus a
real focusable LazyColumn harness using the production scene controller, hero
metadata, headroom and backdrop timing. They cover synchronized vertical motion,
no retained upper metadata at rest, the summary-free exit/return, delayed-frame
handoffs, strict double-Left timing, and numbered-card anchoring. JVM tests cover
snapshot ownership, recycling, interrupted transitions, gap lifetime, frame
identity, preferences and existing app behavior. These supplement TV review.

On the TV check ordinary → ordinary, ordinary → XL → ordinary in both directions,
summary/no-summary variants, consecutive XL rows, and rapid reversals mid-slide.
Confirm each slow Left moves one title, only rapid double Left jumps to the start,
and a held Left cannot arm a jump after release. Check numbered ranks 1/9/10/100,
corner radius, number styles/theme color, artwork, live trailers and popup/sidebar
return. A slow or unavailable image should remain dark until ready or the bounded
fallback, then reveal the destination metadata and backdrop together.
