# XL catalog rows

Experimental Modern Home presentation. Disabled by default; enable individual
rows using the wide-card/portrait icon next to the numbering and landscape
controls in Catalog Management. Preferences are scoped to the active profile.

The selected title occupies a permanent 16:9 slot at the left margin. Upcoming
2:3 portraits have exactly the same image height. On Right, the new selection
immediately takes the featured slot and the remaining portraits slide left by
one portrait pitch. Left reverses the selection. Only one predecessor and a
bounded visible runway are composed. Each title keeps its normal item key,
FocusRequester, Details/long-press callbacks, and complete card renderer.

Metadata is rendered below the XL images using HeroTitleContent, with only its
logo/title slot omitted. The typography, metadata rows, badges, synopsis line
limits, and metadata size preference are shared with ordinary rows. Artwork
inside the featured image retains the existing landscape-card logo behavior.

When the current platform contains XL rows, the same LazyColumn uses a taller
canvas. Ordinary rows retain their original screen anchor; XL rows align below
the platform controls. The outgoing ordinary metadata is translated using its
own LazyColumn item offset, so it moves by exactly the same scroll displacement
as its posters. XL metadata is an actual child of its row. An ordinary row after
an XL row reserves metadata headroom to prevent the two metadata blocks from
overlapping. Platforms with no XL rows keep the original viewport.

XL takes visual precedence over global and per-row landscape settings without
changing those preferences. Numbered XL rows use compact badges, retaining
solid/outline font choice and theme-color preference. The card renderer uses
the existing configurable poster corner radius, focus ring, depth, loading,
artwork fallback, and trailer clipping.

XL trailers play inside the featured card, including when the ordinary-row
playback target is Hero Media. The existing autoplay enable, delay, mute,
resolution, popup/sidebar preservation, backgrounding, and end handling remain
in use. The XL slot does not resize while waiting for or playing a trailer.
Turning autoplay off leaves the XL poster layout in place.
Enabling an XL row also exposes autoplay, delay, and mute controls when ordinary
poster expansion is off. The playback-location hint explains the XL exception.

Seasonal Spotlight and Watchly save a stable group preference, so replacement
catalog IDs inherit XL. Movie and series Watchly groups remain independent.

## Device checks

Compilation and JVM policy tests do not establish TV animation or focus quality.
Check these with the real sideload build before merging:

1. Enable XL on a middle row with ordinary rows above and below it. Move Down
   into it, then Down to the ordinary row, and back Up. Confirm synchronized
   outgoing metadata, incoming lower metadata, readable row titles, and no
   overlapping blocks or logo left above the XL row.
2. Step Right/Left, hold Right/Left, and reach the first/last title. Check the
   fixed featured slot, moving portraits, matching metadata/title, no sideways
   focus escape at the end, and single-Left sidebar access from item zero.
3. Test two quick Left presses, including starting from item one; a double Left
   should return to item zero without opening the sidebar on its second press.
4. Open Details and return. Open/close the sidebar and long-press options,
   including while a trailer is already playing. The same title should regain
   focus and the intended trailer should remain attached to that title.
5. Combine XL with solid and outline numbering, theme-colored numbers, per-row
   landscape, and global landscape. Turn XL off and confirm the previous row
   style returns. Try corner radius zero and a rounded setting.
6. Test autoplay disabled/enabled, different delays/mute settings, and ordinary
   playback target Hero Media. XL video belongs inside its card; ordinary rows
   retain the configured target. Background and reopen Home during a trailer.
7. Check consecutive XL rows, a single-title catalog, the final catalog row,
   pagination, a metadata/artwork-poor title, platform changes, and profile changes.

The experiment is intended for device validation on its separate feature branch.
