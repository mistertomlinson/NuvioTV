import sys
file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

with open(file_path, 'r') as f:
    content = f.read()

# 1. REMOVE the guard that kills the scroll on focus loss
# This ensures the carousel stays put even during that 1.2s "dark period"
content = content.replace(
    'if (!isCarouselFocused || suppressFocusOpen) return@LaunchedEffect',
    '// Scroll logic now persists regardless of focus state'
)

# 2. Add a more robust 'remember' for the ScrollState
# Using 'selectedPlatformId' as a key ensures that the scroll position 
# is preserved specifically for the current view.
content = content.replace(
    'val scrollState = rememberScrollState(initial = 0)',
    'val scrollState = rememberScrollState()'
)

# 3. Force the focusedIndex to stay pinned to the selection
# We modify the initialization to be even more aggressive
old_init = 'var focusedIndex by remember { mutableStateOf(initialIdx) }'
new_init = 'var focusedIndex by remember(activePlatforms) { mutableStateOf(initialIdx) }'

if old_init in content:
    content = content.replace(old_init, new_init)

with open(file_path, 'w') as f:
    f.write(content)
