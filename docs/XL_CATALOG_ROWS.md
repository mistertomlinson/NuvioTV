# XL catalog rows

Experimental Modern Home presentation on its own feature branch.
Catalog Management cycles its wide-card/portrait control through:

1. Off — ordinary catalog presentation.
2. XL with summary — the original XL icon with its lower bar.
3. XL without summary — the same icon without its lower bar.

Existing XL preferences migrate to the summary mode. Both XL variants retain
metadata lines below the selected image; the second omits the synopsis and its
reserved height. The next catalog's actual heading and upper posters can then
appear below it. Preferences remain per profile and use stable group keys for
Seasonal Spotlight and Watchly replacements.

## Focus and row navigation

There is one permanent featured-card focus target per XL row. Left/Right update
its selected title while preserving that physical focus node. Upcoming portraits
are visual previews, not alternate focus targets. Held horizontal keys use an
XL-local time gate and never invoke generic spatial focus search.

The complete existing card renderer still owns Select, Details, long press,
rounded corners, focus outlines, artwork fallback and inline trailer playback.
Logical selection publishes the current item/index to the normal Home focus and
trailer pipeline. Per-item FocusRequesters attach to the permanent slot as its
title changes. Pending restoration selects the requested title before requesting
focus. Up/Down retain ordinary row movement; fresh Left at item zero can open the
sidebar. The double-Left shortcut remains independent of held-key repeats.

## Layout and metadata

The selected image stays 16:9; upcoming portraits stay 2:3 with equal image height.
Only the visible portrait runway is composed. Summary text uses the full-size
half-width synopsis typography even when other metadata is set to Small. The
logo/title slot is omitted from XL metadata; image logos remain part of the
existing landscape-card artwork treatment.

Ordinary-to-ordinary navigation retains stationary upper metadata. Its scroll
translation is active only while entering XL or handing off from a summary XL
row. The transition is scoped to its destination row so a rapid subsequent move
cannot animate an unrelated ordinary row. Summary-free XL exits return to the
ordinary lower viewport; they do not insert the summary-mode metadata runway.

An animated 94%-black layer dims the backdrop below XL artwork and metadata.
It fades away on ordinary rows or when the platform icon row receives focus.
Cards, metadata, focus rings, and platform controls remain above that layer.

Numbered XL rows reuse the original Jost number renderer, including its solid
and outline paint and theme-color treatment. Smaller ranks sit at the bottom
left, eight dp behind the poster edge. Measured rank widths reserve room before
each poster; a stable leading inset keeps the featured slot from shifting when
the selected rank changes digit count. Ordinary numbered rows retain their
existing geometry.

XL overrides landscape presentation while enabled without rewriting that saved
preference. Enabled trailers play inside the featured card with the existing
delay, mute, popup/sidebar preservation and backgrounding behavior. XL geometry
does not resize for trailer loading/playback. Autoplay and delay settings remain
available when ordinary poster expansion is disabled.

## Validation and device review

Robolectric Compose UI tests render the actual XL row/card. They cover fresh and
held Left/Right events during animation, stable physical focus identity, matching
selected-title navigation, deliberate Up to platform controls and return,
summary-free next-row visibility, and synopsis size. JVM tests cover rank gaps,
metadata transition boundaries, three-state persistence/profile isolation, and
existing app behavior. These supplement physical-TV validation.

On the TV check ordinary → ordinary, ordinary → XL → ordinary, consecutive XL
rows, and both XL summary modes. Traverse the first/last title and the 9 → 10
rank boundary. Open Details/options/sidebar and return, including while a trailer
plays. Confirm corner radius, number styles, theme color, autoplay/mute/delay,
platform changes, pagination, and the next-row peek in summary-free mode.
