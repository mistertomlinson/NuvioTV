package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.nuvio.tv.R
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class StreamingPlatform(
    val id: String,
    val drawableRes: Int?,
    val brandColor: Color?,
    val brandColorEnd: Color? = null,
    val iconHeight: androidx.compose.ui.unit.Dp = 18.dp,
    val iconMaxWidth: androidx.compose.ui.unit.Dp = 60.dp,
    val verticalOffset: androidx.compose.ui.unit.Dp = 0.dp,
    val fontSize: Float = 13f
)

val streamingPlatforms = listOf(
    StreamingPlatform("home",      null,                             Color(0xFF5822B4),  Color(0xFF00A8E0), fontSize = 21.06f),
    StreamingPlatform("netflix",   R.drawable.platform_netflix,      Color(0xFFE50914), null),
    StreamingPlatform("disney",    R.drawable.platform_disney_p,     Color(0xFF113CCF), null, iconHeight = 27.dp, iconMaxWidth = 90.dp),
    StreamingPlatform("hbo",       R.drawable.platform_hbo_max,      Color(0xFF5822B4), null, iconHeight = 14.4.dp, iconMaxWidth = 57.6.dp),
    StreamingPlatform("hulu",      R.drawable.platform_hulu,         Color(0xFF1CE783), null, verticalOffset = (-1).dp),
    StreamingPlatform("amazon",    R.drawable.platform_amazon_prime, Color(0xFF00A8E0), null, iconHeight = 22.dp, iconMaxWidth = 72.dp),
    StreamingPlatform("apple",     R.drawable.platform_apple_tv,     Color(0xFF555555), null, verticalOffset = (-1).dp),
    StreamingPlatform("paramount", R.drawable.platform_paramount_p,  Color(0xFF0064FF), null, iconHeight = 24.2.dp, iconMaxWidth = 79.2.dp),
    StreamingPlatform("peacock",   R.drawable.platform_peacock,      Color(0xFFF2A900), null),
    StreamingPlatform("criterion", R.drawable.platform_criterion,    Color(0xFF8A8A8A), null, iconHeight = 26.dp, iconMaxWidth = 90.dp),
)

@Composable
fun StreamingPlatformCarousel(
    selectedPlatformId: String,
    visiblePlatformIds: Set<String> = emptySet(),
    isCarouselFocused: Boolean,
    onCarouselFocusChanged: (Boolean) -> Unit,
    onPlatformSelected: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val activePlatforms = remember(visiblePlatformIds) {
        streamingPlatforms.filter { it.id == "home" || visiblePlatformIds.isEmpty() || it.id in visiblePlatformIds }
    }
    val contentFocusRequester = com.nuvio.tv.LocalContentFocusRequester.current
    val scope = rememberCoroutineScope()

    var focusedIndex by remember { mutableStateOf(activePlatforms.indexOfFirst { it.id == selectedPlatformId }.coerceAtLeast(0)) }
    var containerWidthPx by remember { mutableStateOf(0f) }
    val itemWidths = remember(activePlatforms) { HashMap<Int, Float>() }
    val spacingPx = with(density) { 4.dp.toPx() }

    fun defaultW() = with(density) { 88.dp.toPx() }

    fun itemAbsoluteX(idx: Int): Float {
        var x = 0f
        for (i in 0 until idx) x += (itemWidths[i] ?: defaultW()) + spacingPx
        return x
    }

    fun totalContentWidth(): Float {
        var w = 0f
        for (i in activePlatforms.indices) w += (itemWidths[i] ?: defaultW()) + spacingPx
        return (w - spacingPx).coerceAtLeast(0f)
    }

    fun centeredScrollFor(idx: Int): Float {
        val absX = itemAbsoluteX(idx)
        val w = itemWidths[idx] ?: defaultW()
        val maxScroll = (totalContentWidth() - containerWidthPx).coerceAtLeast(0f)
        return (absX + w / 2f - containerWidthPx / 2f).coerceIn(0f, maxScroll)
    }

    // Scroll is just a float we control — no ScrollState, no Compose scroll container
    val scrollState = rememberScrollState()
    val selectorXAnim = remember { Animatable(0f) }
    val selectorWAnim = remember { Animatable(defaultW()) }

    var snapNextNavigation by remember { mutableStateOf(true) }

    // Color
    val displayIndex = if (isCarouselFocused) focusedIndex else
        activePlatforms.indexOfFirst { it.id == selectedPlatformId }.coerceAtLeast(0)
    val focusedPlatform = activePlatforms.getOrNull(displayIndex)
    val targetSolidColor = focusedPlatform?.brandColor ?: Color(0xFF5822B4)
    val animatedSelectorColor by animateColorAsState(targetSolidColor, tween(250), label = "selCol")
    val selectorBoxAlpha by animateFloatAsState(
        if (isCarouselFocused) 1f else 0.5f, tween(250), label = "selAlpha"
    )

    val currentSelectorX by selectorXAnim.asState()
    val currentSelectorW by selectorWAnim.asState()

    // Open/close
    LaunchedEffect(isCarouselFocused) {
        if (isCarouselFocused) {
            val idx = activePlatforms.indexOfFirst { it.id == selectedPlatformId }.coerceAtLeast(0)
            snapNextNavigation = true
            focusedIndex = idx
        }
    }

    // Drive scroll and selector from focusedIndex changes
    LaunchedEffect(focusedIndex, snapNextNavigation) {
        if (containerWidthPx <= 0f) return@LaunchedEffect
        val scroll = centeredScrollFor(focusedIndex)
        val selX = itemAbsoluteX(focusedIndex)
        val selW = itemWidths[focusedIndex] ?: defaultW()
        if (snapNextNavigation) {
            scrollState.scrollTo(scroll.roundToInt())
            selectorXAnim.snapTo(selX)
            selectorWAnim.snapTo(selW)
        } else {
            launch { scrollState.animateScrollTo(scroll.roundToInt(), tween(150)) }
            launch { selectorXAnim.animateTo(selX, tween(150)) }
            launch { selectorWAnim.animateTo(selW, tween(150)) }
        }
    }

    // Outer container — the only focusable thing, handles all key events
    Box(
        modifier = modifier
            .wrapContentSize()
            .onGloballyPositioned { containerWidthPx = it.size.width.toFloat() }
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (!state.isFocused && !state.hasFocus) {
                    // lost focus entirely
                }
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!isCarouselFocused) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                    when (event.key) {
                        Key.DirectionRight -> {
                            val next = (focusedIndex + 1).coerceAtMost(activePlatforms.size - 1)
                            if (next != focusedIndex) {
                                snapNextNavigation = false
                                focusedIndex = next
                                onPlatformSelected(activePlatforms[next].id)
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            val next = (focusedIndex - 1).coerceAtLeast(0)
                            if (next != focusedIndex) {
                                snapNextNavigation = false
                                focusedIndex = next
                                onPlatformSelected(activePlatforms[next].id)
                            }
                            true
                        }
                        Key.DirectionDown, Key.Back -> {
                            onCarouselFocusChanged(false)
                            try { contentFocusRequester.requestFocus() } catch (e: Exception) {}
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // Fade edges
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edgeW = 48.dp.toPx()
                    val leftAlpha = (scrollState.value / 80f).coerceIn(0f, 1f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black.copy(alpha = 1f - leftAlpha), Color.Black),
                            startX = 0f, endX = edgeW
                        ),
                        blendMode = BlendMode.DstIn
                    )
                    if (scrollState.maxValue > 0) {
                        val rightAlpha = ((scrollState.maxValue - scrollState.value) / 80f).coerceIn(0f, 1f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - rightAlpha)),
                                startX = size.width - edgeW, endX = size.width
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
        ) {
            // Scrollable container — no focusable children so bringIntoView never fires
            Box(
                modifier = Modifier
                    .horizontalScroll(scrollState, enabled = false)
            ) {
                // Selector box — in content space, behind icons
                Box(
                    modifier = Modifier
                        .offset { IntOffset(currentSelectorX.roundToInt(), 0) }
                        .width(with(density) { currentSelectorW.toDp() })
                        .height(34.dp)
                        .alpha(selectorBoxAlpha)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (focusedPlatform?.id == "home") Brush.radialGradient(
                                colors = listOf(Color(0xFF9B5FE0), Color(0xFF6A3FD4), Color(0xFF00C8C8)),
                                center = androidx.compose.ui.geometry.Offset(0f, 0f),
                                radius = 120f
                            ) else Brush.linearGradient(listOf(animatedSelectorColor, animatedSelectorColor))
                        )
                )

                // Icons row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    activePlatforms.forEachIndexed { index, platform ->
                        val iconAlpha by animateFloatAsState(
                            targetValue = when {
                                !isCarouselFocused && platform.id == selectedPlatformId -> 0.75f
                                !isCarouselFocused -> 0.35f
                                else -> 1f
                            },
                            animationSpec = tween(200),
                            label = "iconAlpha$index"
                        )
                        Box(
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    itemWidths[index] = coords.size.width.toFloat()
                                }
                                .height(34.dp)
                                .padding(horizontal = 14.dp)
                                .alpha(iconAlpha),
                            contentAlignment = Alignment.Center
                        ) {
                            PlatformIconContent(platform)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformIconContent(platform: StreamingPlatform) {
    if (platform.drawableRes == null) {
        androidx.compose.material3.Text(
            text = "home", color = Color.White,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = androidx.compose.ui.unit.TextUnit(platform.fontSize, androidx.compose.ui.unit.TextUnitType.Sp),
                fontFamily = FontFamily(Font(R.font.surgena_semibold)),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        )
    } else {
        Image(
            painter = painterResource(id = platform.drawableRes),
            contentDescription = platform.id,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier
                .height(platform.iconHeight)
                .widthIn(max = platform.iconMaxWidth)
                .offset(y = platform.verticalOffset)
        )
    }
}
