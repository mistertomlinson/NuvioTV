#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODERN = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt"


def main() -> None:
    text = MODERN.read_text(encoding="utf-8")

    old = '''private fun resolveContinueWatchingStructuralFocusIndex(
    previousKeys: List<String>,
    currentKeys: List<String>,
    previousFocusedIndex: Int
): Int {
    /*
     * Continue Watching is recency ordered. Any structural title/order change
     * therefore makes slot 0 the canonical landing position.
     *
     * Keep the existing call shape so all current structural-change pathways
     * share one rule:
     * - if CW owns/returns focus, focus and viewport land on slot 0;
     * - if another Home row owns focus, CW quietly prepares slot 0 without
     *   stealing focus.
     *
     * This intentionally replaces the older identity/same-numeric-slot rule.
     */
    return 0
}'''

    new = '''private fun resolveContinueWatchingStructuralFocusIndex(
    previousKeys: List<String>,
    currentKeys: List<String>,
    previousFocusedIndex: Int
): Int {
    if (currentKeys.isEmpty()) {
        return 0
    }

    val previousFocusedKey =
        previousKeys.getOrNull(previousFocusedIndex)

    val focusedItemWasRemoved =
        previousFocusedKey != null &&
            previousFocusedKey !in currentKeys

    return if (focusedItemWasRemoved) {
        /*
         * Removing the poster that currently owns focus is different from a
         * recency reorder/insert. Preserve the numeric slot so the next poster
         * slides into the same left-aligned visual position and the viewport
         * does not pull poster 0 back on screen.
         */
        previousFocusedIndex.coerceIn(
            0,
            currentKeys.lastIndex
        )
    } else {
        /*
         * For recency reorders/inserts, slot 0 is canonical. This covers a
         * played CW title moving to the head and a new Search-origin title
         * being inserted at the head.
         */
        0
    }
}'''

    if new in text:
        print("[already applied] preserve visual anchor when focused CW item is removed")
    else:
        count = text.count(old)
        if count != 1:
            raise SystemExit(
                "CW remove-anchor refinement: expected exactly one V9 helper, "
                f"found {count}"
            )
        MODERN.write_text(text.replace(old, new, 1), encoding="utf-8")
        print("[patched] preserve visual anchor when focused CW item is removed")

    verify = MODERN.read_text(encoding="utf-8")
    if "Removing the poster that currently owns focus is different" not in verify:
        raise SystemExit("V10 validation marker missing")

    print("\nV10 CW remove-anchor refinement applied successfully to:")
    print("  - app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt")


if __name__ == "__main__":
    main()
