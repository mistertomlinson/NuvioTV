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
    if (currentKeys.isEmpty()) {
        return 0
    }

    val previousFocusedKey =
        previousKeys.getOrNull(previousFocusedIndex)

    val followedIndex =
        if (previousFocusedKey != null) {
            currentKeys.indexOf(previousFocusedKey)
        } else {
            -1
        }

    return if (followedIndex >= 0) {
        followedIndex
    } else {
        previousFocusedIndex.coerceIn(
            0,
            currentKeys.lastIndex
        )
    }
}'''

    new = '''private fun resolveContinueWatchingStructuralFocusIndex(
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

    if new in text:
        print("[already applied] canonical CW structural landing at slot 0")
    else:
        count = text.count(old)
        if count != 1:
            raise SystemExit(
                "canonical CW structural landing: expected exactly one helper, "
                f"found {count}"
            )
        MODERN.write_text(text.replace(old, new, 1), encoding="utf-8")
        print("[patched] canonical CW structural landing at slot 0")

    verify = MODERN.read_text(encoding="utf-8")
    if "This intentionally replaces the older identity/same-numeric-slot rule." not in verify:
        raise SystemExit("V9 validation marker missing")

    print("\nV9 CW structural-focus refinement applied successfully to:")
    print("  - app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt")


if __name__ == "__main__":
    main()
