import sys
file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

with open(file_path, 'r') as f:
    content = f.read()

# 1. STOP THE SLIDE: If we are selecting, do NOT let the LaunchedEffect scroll.
# We change the guard to be absolute during selection.
content = content.replace(
    'if (!isCarouselFocused) return@LaunchedEffect',
    'if (!isCarouselFocused || suppressFocusOpen) return@LaunchedEffect'
)

# 2. FIX THE FOCUS LOSS: Force the focus requester to stay active.
# We modify the selection handler to keep the focus requester engaged.
old_select = "onPlatformSelected(platform.id)"
new_select = """onPlatformSelected(platform.id)
                                // Force focus to stay on THIS item specifically
                                itemFocusRequesters[index].requestFocus()"""

if old_select in content:
    content = content.replace(old_select, new_select)

with open(file_path, 'w') as f:
    f.write(content)
