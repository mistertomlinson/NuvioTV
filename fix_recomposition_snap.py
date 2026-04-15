import sys
file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

with open(file_path, 'r') as f:
    content = f.read()

# We are replacing the conditional drawing with "Always-On" drawing.
# This ensures the UI structure is identical whether the gradient is visible or not.
old_draw_block = """                .drawWithContent {
                    drawContent()
                    val leftAlpha = (scrollState.value / 80f).coerceIn(0f, 1f)
                    val rightAlpha = if (scrollState.maxValue > 0)
                        ((scrollState.maxValue - scrollState.value) / 80f).coerceIn(0f, 1f)
                    else 0f
                    if (leftAlpha > 0f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                                startX = 0f,
                                endX = 48.dp.toPx()
                            ),
                            alpha = leftAlpha,
                            blendMode = BlendMode.DstIn
                        )
                    }
                    if (rightAlpha > 0f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Black, Color.Transparent),
                                startX = size.width - 48.dp.toPx(),
                                endX = size.width
                            ),
                            alpha = rightAlpha,
                            blendMode = BlendMode.DstIn
                        )
                    }
                }"""

new_draw_block = """                .drawWithContent {
                    drawContent()
                    
                    // Left Gradient (Always present, alpha handles visibility)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = 48.dp.toPx()
                        ),
                        alpha = (scrollState.value / 80f).coerceIn(0f, 1f),
                        blendMode = BlendMode.DstIn
                    )
                    
                    // Right Gradient (Always present, alpha handles visibility)
                    val maxScroll = scrollState.maxValue
                    if (maxScroll > 0) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Black, Color.Transparent),
                                startX = size.width - 48.dp.toPx(),
                                endX = size.width
                            ),
                            alpha = ((maxScroll - scrollState.value) / 80f).coerceIn(0f, 1f),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }"""

if old_draw_block in content:
    content = content.replace(old_draw_block, new_draw_block)
    with open(file_path, 'w') as f:
        f.write(content)
    print("Patch applied successfully.")
else:
    print("Match failed. The code might have slightly different formatting.")
