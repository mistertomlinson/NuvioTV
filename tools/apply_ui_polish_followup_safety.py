#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()
REL = "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeRows.kt"
path = ROOT / REL
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{REL}: expected exactly one safety anchor, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    '''    private val watchedExitAnimationGenerations =
        mutableMapOf<String, androidx.compose.runtime.MutableIntState>()
    private var watchedKeys: Set<String> = emptySet()
''',
    '''    private val watchedExitAnimationGenerations =
        mutableMapOf<String, androidx.compose.runtime.MutableIntState>()
    private val optimisticallyHiddenWatchedKeys = mutableSetOf<String>()
    private var watchedKeys: Set<String> = emptySet()
'''
)

replace_once(
    '''    fun syncWatched(
        movieStatus: Map<String, Boolean>,
        seriesStatus: Map<String, Boolean>
    ) {
        val next = buildSet {
            movieStatus.forEach { (key, value) -> if (value) add(key) }
            seriesStatus.forEach { (key, value) -> if (value) add(key) }
        }
        (watchedKeys + next).forEach { key ->
            stateFor(watchedStates, key).value = key in next
        }
        watchedKeys = next
    }
''',
    '''    fun syncWatched(
        movieStatus: Map<String, Boolean>,
        seriesStatus: Map<String, Boolean>
    ) {
        val authoritativeNext = buildSet {
            movieStatus.forEach { (key, value) -> if (value) add(key) }
            seriesStatus.forEach { (key, value) -> if (value) add(key) }
        }
        val next = authoritativeNext - optimisticallyHiddenWatchedKeys
        (watchedKeys + next + optimisticallyHiddenWatchedKeys).forEach { key ->
            stateFor(watchedStates, key).value = key in next
        }
        watchedKeys = next

        /*
         * Once the authoritative projection itself says a hidden key is no
         * longer watched, the optimism has served its purpose. Drop the guard
         * so a later real re-watch can publish the badge normally.
         */
        optimisticallyHiddenWatchedKeys.removeAll { key ->
            key !in authoritativeNext
        }
    }
'''
)

replace_once(
    '''    fun beginWatchedExit(statusKey: String) {
        val generation = watchedExitGenerationStateFor(statusKey)
        generation.intValue = generation.intValue + 1
        stateFor(watchedStates, statusKey).value = false
    }

    fun restoreWatched(statusKey: String) {
        stateFor(watchedStates, statusKey).value = true
    }
''',
    '''    fun beginWatchedExit(statusKey: String) {
        optimisticallyHiddenWatchedKeys += statusKey
        val generation = watchedExitGenerationStateFor(statusKey)
        generation.intValue = generation.intValue + 1
        stateFor(watchedStates, statusKey).value = false
    }

    fun restoreWatched(statusKey: String) {
        optimisticallyHiddenWatchedKeys -= statusKey
        stateFor(watchedStates, statusKey).value = true
    }
'''
)

path.write_text(text, encoding="utf-8")
Path(__file__).unlink()
print("Optimistic watched-badge safety guard applied.")
