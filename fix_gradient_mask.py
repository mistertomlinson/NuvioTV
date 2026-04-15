import sys
import re

file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

try:
    with open(file_path, 'r') as f:
        content = f.read()

    # The logic: Transparent in DstIn means "remove", Black means "keep".
    # When leftAlpha is 0, we want Black to Black (no mask).
    # When leftAlpha is 1, we want Transparent to Black (fade mask).
    new_draw_block = """                .drawWithContent {
                    drawContent()
                    val density = this
                    val edgeWidth = with(density) { 48.dp.toPx() }
                    
                    // Left Gradient
                    val leftAlpha = (scrollState.value / 80f).coerceIn(0f, 1f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black.copy(alpha = 1f - leftAlpha), Color.Black),
                            startX = 0f,
                            endX = edgeWidth
                        ),
                        blendMode = BlendMode.DstIn
                    )
                    
                    // Right Gradient
                    val maxScroll = scrollState.maxValue
                    if (maxScroll > 0) {
                        val rightAlpha = ((maxScroll - scrollState.value) / 80f).coerceIn(0f, 1f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - rightAlpha)),
                                startX = size.width - edgeWidth,
                                endX = size.width
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }"""

    pattern = r'\.drawWithContent\s*\{.*?\}'
    content = re.sub(pattern, new_draw_block, content, flags=re.DOTALL)

    with open(file_path, 'w') as f:
        f.write(content)
    print("Mask fixed successfully with correct path.")

except FileNotFoundError:
    print(f"Error: Could not find {file_path}. Please check your current directory.")
