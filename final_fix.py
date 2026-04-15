import sys
# Fixed the path: changed nuvio.tv to nuvio/tv
file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

with open(file_path, 'r') as f:
    content = f.read()

# Fix: Initialize focusedIndex based on the selectedPlatformId instead of 0
# This prevents the "snap back to start" behavior upon selection
old_init = 'var focusedIndex by remember { mutableStateOf(0) }'
new_init = """val initialIdx = remember(activePlatforms) { activePlatforms.indexOfFirst { it.id == selectedPlatformId }.coerceAtLeast(0) }
    var focusedIndex by remember { mutableStateOf(initialIdx) }"""

if old_init in content:
    content = content.replace(old_init, new_init)

with open(file_path, 'w') as f:
    f.write(content)
