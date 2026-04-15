import sys
file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

with open(file_path, 'r') as f:
    content = f.read()

# 1. Prevent focus flip-flop from resetting the index
content = content.replace('if (isCarouselFocused) {', 'if (isCarouselFocused && !suppressFocusOpen) {')

# 2. Update the selection logic with a delayed lock release
# We target the Enter/Center key handler
old_select = """onPlatformSelected(platform.id)
                                onCarouselFocusChanged(false)
                                suppressFocusOpen = false"""

new_select = """focusedIndex = index
                                onPlatformSelected(platform.id)
                                onCarouselFocusChanged(false)
                                scope.launch {
                                    kotlinx.coroutines.delay(300)
                                    suppressFocusOpen = false
                                }"""

if old_select in content:
    content = content.replace(old_select, new_select)
else:
    # Fallback if whitespace differs slightly
    content = content.replace('onPlatformSelected(platform.id)', 'focusedIndex = index; onPlatformSelected(platform.id)')

with open(file_path, 'w') as f:
    f.write(content)
