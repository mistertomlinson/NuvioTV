#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()
ROWS = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeRows.kt"
HOME = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/HomeScreen.kt"

rows = ROWS.read_text(encoding="utf-8")
old = '''    fun restoreWatched(statusKey: String) {
        optimisticallyHiddenWatchedKeys -= statusKey
        stateFor(watchedStates, statusKey).value = true
    }

    fun watchedExitAnimationGeneration(item: MetaPreview): Int =
'''
new = '''    fun restoreWatched(statusKey: String) {
        optimisticallyHiddenWatchedKeys -= statusKey
        stateFor(watchedStates, statusKey).value = true
    }

    fun cancelWatchedExit(statusKey: String) {
        optimisticallyHiddenWatchedKeys -= statusKey
    }

    fun watchedExitAnimationGeneration(item: MetaPreview): Int =
'''
if rows.count(old) != 1:
    raise SystemExit("ModernHomeRows.kt: rewatch safety anchor did not match exactly once")
ROWS.write_text(rows.replace(old, new, 1), encoding="utf-8")

home = HOME.read_text(encoding="utf-8")
old = '''                    if (!wasAlreadyWatched) {
                        watchedRatingHandoffStatusKey = statusKey
                        watchedRatingHandoffObservedPending = false
                        watchedRatingHandoffIsSeries = true
                    }

                    /*
'''
new = '''                    if (!wasAlreadyWatched) {
                        /*
                         * A very fast re-watch can happen before the slower
                         * authoritative series projection has emitted the
                         * preceding unwatch. Remove only the presentation guard;
                         * the real watched badge still waits for provider state.
                         */
                        posterStatusRegistry.cancelWatchedExit(statusKey)
                        watchedRatingHandoffStatusKey = statusKey
                        watchedRatingHandoffObservedPending = false
                        watchedRatingHandoffIsSeries = true
                    }

                    /*
'''
if home.count(old) != 1:
    raise SystemExit("HomeScreen.kt: rewatch safety anchor did not match exactly once")
HOME.write_text(home.replace(old, new, 1), encoding="utf-8")

Path(__file__).unlink()
print("Immediate re-watch safety guard applied.")
