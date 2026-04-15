import sys
file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

with open(file_path, 'r') as f:
    content = f.read()

# 1. Modify the scroll effect to skip centering when focus is "locked" by a selection
# We want to check if the current transition is a user selection.
old_scroll_logic = 'if (!isCarouselFocused) return@LaunchedEffect'
new_scroll_logic = '// Skip auto-centering during the selection transition to prevent jumping\n        if (!isCarouselFocused || suppressFocusOpen) return@LaunchedEffect'

if old_scroll_logic in content:
    content = content.replace(old_scroll_logic, new_scroll_logic)

# 2. Re-enable the suppressFocusOpen toggle for the duration of the transition
# This acts as our "Selection in progress" flag
content = content.replace(
    'onPlatformSelected(platform.id)',
    'suppressFocusOpen = true; onPlatformSelected(platform.id)'
)

# 3. Add a small safety to turn it off after the recomposition cycle
content = content.replace(
    'onPlatformSelected(platform.id)\n',
    'onPlatformSelected(platform.id)\n                                scope.launch { kotlinx.coroutines.delay(500); suppressFocusOpen = false }\n'
)

with open(file_path, 'w') as f:
    f.write(content)
