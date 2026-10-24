#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODERN = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt"


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        print(f"[already applied] {label}")
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    print(f"[patched] {label}")
    return text.replace(old, new, 1)


def main() -> None:
    text = MODERN.read_text(encoding="utf-8")

    # V10 treated every disappearance of the focused key as a manual removal.
    # That conflates an explicit Home-row removal with natural playback
    # completion while Home is away. Make slot 0 the default structural landing;
    # the live Home removal path below supplies the one narrow exception.
    old_helper = '''private fun resolveContinueWatchingStructuralFocusIndex(
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

    new_helper = '''private fun resolveContinueWatchingStructuralFocusIndex(
    previousKeys: List<String>,
    currentKeys: List<String>,
    previousFocusedIndex: Int
): Int {
    /*
     * Structural changes observed across navigation use the canonical CW
     * landing: slot 0. That includes natural playback completion, where the
     * previously focused title disappears while Home is away.
     *
     * Explicit manual removal is handled separately by a one-shot Home-local
     * key so it can preserve the old visual slot without changing this rule.
     */
    return 0
}'''

    text = replace_exact(
        text,
        old_helper,
        new_helper,
        "make slot 0 the default CW structural landing",
    )

    old_order_state = '''    val currentContinueWatchingOrderKeys =
        remember(uiState.continueWatchingItems) {
            stableContinueWatchingOrderKeys(
                uiState.continueWatchingItems
            )
        }

    val carouselRows = remember('''

    new_order_state = '''    val currentContinueWatchingOrderKeys =
        remember(uiState.continueWatchingItems) {
            stableContinueWatchingOrderKeys(
                uiState.continueWatchingItems
            )
        }

    /*
     * One-shot intent marker for an explicit CW options-menu removal.
     *
     * A title disappearing for any other reason (especially natural playback
     * completion while Home is away) must not inherit the manual-remove visual
     * anchor behavior.
     */
    var manualContinueWatchingRemovalKey by
        remember {
            mutableStateOf<String?>(null)
        }

    val carouselRows = remember('''

    text = replace_exact(
        text,
        old_order_state,
        new_order_state,
        "track explicit manual CW removal intent",
    )

    old_target = '''        val targetIndex =
            if (continueWatchingOwnsReturnTarget) {
                /*
                 * CW either owns real focus or owns an in-flight saved return:
                 *
                 * - a surviving title follows its identity through a reorder
                 * - a removed title leaves focus on the same numeric slot
                 */
                resolveContinueWatchingStructuralFocusIndex(
                    previousKeys = previousKeys,
                    currentKeys =
                        currentContinueWatchingOrderKeys,
                    previousFocusedIndex =
                        previousFocusedIndex
                )
            } else {
                /*
                 * CW is truly inactive.
                 *
                 * A fresh playback can insert a new title at the head while
                 * the user actually belongs to another Home row. Do not carry
                 * an old remembered CW title forward in that situation.
                 *
                 * Prepare CW at its newest item without requesting focus.
                 */
                0
            }'''

    new_target = '''        val focusedKeyBeforeChange =
            previousKeys.getOrNull(previousFocusedIndex)

        val manualRemovalKey =
            manualContinueWatchingRemovalKey

        val preserveManualRemovalSlot =
            manualRemovalKey != null &&
                focusedKeyBeforeChange == manualRemovalKey &&
                manualRemovalKey !in
                    currentContinueWatchingOrderKeys

        /*
         * Consume the explicit intent only after that exact title has actually
         * disappeared. If an unrelated reorder arrives first, keep the marker
         * armed for the eventual removal rather than misclassifying the reorder.
         */
        if (
            manualRemovalKey != null &&
            manualRemovalKey !in
                currentContinueWatchingOrderKeys
        ) {
            manualContinueWatchingRemovalKey = null
        }

        val targetIndex =
            if (preserveManualRemovalSlot) {
                /*
                 * Manual remove: keep the old numeric slot and therefore the
                 * current left-aligned viewport. The next poster slides into
                 * the removed poster's place.
                 */
                previousFocusedIndex.coerceIn(
                    0,
                    currentContinueWatchingOrderKeys.lastIndex
                )
            } else {
                /*
                 * Completion, reorder, insertion, and every non-manual
                 * structural change land on the newest CW card.
                 */
                resolveContinueWatchingStructuralFocusIndex(
                    previousKeys = previousKeys,
                    currentKeys =
                        currentContinueWatchingOrderKeys,
                    previousFocusedIndex =
                        previousFocusedIndex
                )
            }'''

    text = replace_exact(
        text,
        old_target,
        new_target,
        "separate manual removal from completion/reorder landing",
    )

    old_remove = '''            onRemove = {
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem = null
            },'''

    new_remove = '''            onRemove = {
                val selectedRemovalIndex =
                    uiState.continueWatchingItems
                        .indexOf(selectedOptionsItem)

                manualContinueWatchingRemovalKey =
                    currentContinueWatchingOrderKeys
                        .getOrNull(selectedRemovalIndex)

                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem = null
            },'''

    text = replace_exact(
        text,
        old_remove,
        new_remove,
        "arm manual CW removal key from options dialog",
    )

    # If the final CW item is manually removed there is no non-empty structural
    # landing to consume the marker. Clear it in the existing empty-list branch.
    old_empty = '''        if (
            previousKeys.isEmpty() ||
            currentContinueWatchingOrderKeys.isEmpty()
        ) {
            lastHandledContinueWatchingOrderKeys =
                currentContinueWatchingOrderKeys
            return@LaunchedEffect
        }'''

    new_empty = '''        if (
            previousKeys.isEmpty() ||
            currentContinueWatchingOrderKeys.isEmpty()
        ) {
            if (currentContinueWatchingOrderKeys.isEmpty()) {
                manualContinueWatchingRemovalKey = null
            }
            lastHandledContinueWatchingOrderKeys =
                currentContinueWatchingOrderKeys
            return@LaunchedEffect
        }'''

    text = replace_exact(
        text,
        old_empty,
        new_empty,
        "clear manual removal intent when CW becomes empty",
    )

    MODERN.write_text(text, encoding="utf-8")

    verify = MODERN.read_text(encoding="utf-8")
    required = [
        "manualContinueWatchingRemovalKey",
        "preserveManualRemovalSlot",
        "natural playback completion",
    ]
    missing = [needle for needle in required if needle not in verify]
    if missing:
        raise SystemExit(f"V11 validation markers missing: {missing}")

    print("\nV11 CW completion-vs-manual-remove refinement applied successfully to:")
    print("  - app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt")


if __name__ == "__main__":
    main()
