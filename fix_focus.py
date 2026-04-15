import sys
file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

with open(file_path, 'r') as f:
    content = f.read()

# 1. Simplify the selection handler: Update ID, but don't force focus out and don't lock
# This keeps the focus exactly where the user clicked.
old_handler = """focusedIndex = index
                                onPlatformSelected(platform.id)
                                onCarouselFocusChanged(false)
                                // Handled by scope launch"""

new_handler = """focusedIndex = index
                                onPlatformSelected(platform.id)"""

content = content.replace(old_handler, new_handler)

# 2. Clean up the leftover scope launch if the previous patch was messy
if "scope.launch" in content and "suppressFocusOpen = false" in content:
    import re
    content = re.sub(r'scope\.launch \{.*?suppressFocusOpen = false.*?\}', '', content, flags=re.DOTALL)

with open(file_path, 'w') as f:
    f.write(content)
