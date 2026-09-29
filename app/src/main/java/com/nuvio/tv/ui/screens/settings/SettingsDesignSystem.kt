@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import com.nuvio.tv.ui.components.HomePopupGlassEnvironment
import com.nuvio.tv.ui.components.LocalHomePopupGlassEnvironment
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import androidx.compose.foundation.layout.RowScope
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.glassDialogFocusTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.LocalSettingsBackdropBitmap
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

internal val SettingsContainerRadius = 28.dp
internal val SettingsPillRadius = 999.dp
internal val SettingsSecondaryCardRadius = 16.dp
internal val SettingsInnerRowRadius = 0.dp
internal val SettingsRailFocusRadius = 10.dp
internal val SettingsRowFocusRadius = SettingsSecondaryCardRadius
internal val SettingsRowGap = 2.dp
internal val SettingsRailItemHeight = 40.dp

internal enum class SettingsGroupPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM
}

internal fun settingsGroupShape(position: SettingsGroupPosition): RoundedCornerShape {
    return when (position) {
        SettingsGroupPosition.SINGLE -> RoundedCornerShape(SettingsSecondaryCardRadius)
        SettingsGroupPosition.TOP -> RoundedCornerShape(
            topStart = SettingsSecondaryCardRadius,
            topEnd = SettingsSecondaryCardRadius,
            bottomStart = 0.dp,
            bottomEnd = 0.dp
        )
        SettingsGroupPosition.MIDDLE -> RoundedCornerShape(0.dp)
        SettingsGroupPosition.BOTTOM -> RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = SettingsSecondaryCardRadius,
            bottomEnd = SettingsSecondaryCardRadius
        )
    }
}

@Composable
internal fun animatedSettingsGroupShape(
    position: SettingsGroupPosition,
    animateTopFlatten: Boolean = false,
    animateBottomFlatten: Boolean = false
): RoundedCornerShape {
    val targetTop = when (position) {
        SettingsGroupPosition.SINGLE,
        SettingsGroupPosition.TOP -> SettingsSecondaryCardRadius
        SettingsGroupPosition.MIDDLE,
        SettingsGroupPosition.BOTTOM -> 0.dp
    }
    val targetBottom = when (position) {
        SettingsGroupPosition.SINGLE,
        SettingsGroupPosition.BOTTOM -> SettingsSecondaryCardRadius
        SettingsGroupPosition.TOP,
        SettingsGroupPosition.MIDDLE -> 0.dp
    }
    val roundedAnimation = tween<Dp>(
        durationMillis = 240,
        easing = FastOutSlowInEasing
    )
    val top by animateDpAsState(
        targetValue = targetTop,
        animationSpec =
            if (targetTop == 0.dp && !animateTopFlatten) {
                snap()
            } else {
                roundedAnimation
            },
        label = "settingsTopCorner"
    )
    val bottom by animateDpAsState(
        targetValue = targetBottom,
        animationSpec =
            if (targetBottom == 0.dp && !animateBottomFlatten) {
                snap()
            } else {
                roundedAnimation
            },
        label = "settingsBottomCorner"
    )
    return RoundedCornerShape(
        topStart = top,
        topEnd = top,
        bottomStart = bottom,
        bottomEnd = bottom
    )
}

private const val SETTINGS_COMPACT_FONT_SCALE = 0.86f

/**
 * Content-only density used by Settings, Addons and Library.
 *
 * Only fontScale changes. Physical dp sizing, Home, player UI and the legacy
 * sidebar are untouched.
 */
@Composable
internal fun SettingsCompactContent(
    content: @Composable () -> Unit
) {
    val baseDensity = LocalDensity.current
    val compactDensity = remember(
        baseDensity.density,
        baseDensity.fontScale
    ) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * SETTINGS_COMPACT_FONT_SCALE
        )
    }

    CompositionLocalProvider(
        LocalDensity provides compactDensity
    ) {
        content()
    }
}
internal val SettingsGlassCanvasBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xAD2A3038),
        Color(0x9E20252C),
        Color(0xA824292F)
    )
)
internal val SettingsGlassRowColor = Color.White.copy(alpha = 0.065f)
internal val SettingsGlassRowFocusedColor = Color.White.copy(alpha = 0.16f)
internal val SettingsDialogGlassIdleColor = Color.Transparent
internal val SettingsDialogGlassSelectedColor: Color
    @Composable
    get() = NuvioColors.Secondary.copy(alpha = 0.42f)
internal val SettingsDialogPillShape = RoundedCornerShape(32.dp)
internal const val SettingsDialogFocusScale = 1.045f
internal const val SettingsDialogPressedScale = 0.99f
internal val SettingsDialogGlassInsetColor =
    Color.Black.copy(alpha = 0.16f)
internal val SettingsDialogGlassInsetFocusedColor =
    Color.White.copy(alpha = 0.10f)

private val SettingsNeutralSurfaceColor = Color(0xFF40464D)
private val SettingsNeutralSurfaceFocusedColor = Color(0xFF5B636C)

internal val SettingsRightSurfaceColor: Color
    @Composable
    @ReadOnlyComposable
    get() =
        if (NuvioTheme.currentTheme == AppTheme.WHITE) {
            SettingsNeutralSurfaceColor
        } else {
            lerp(
                SettingsNeutralSurfaceColor,
                NuvioColors.Secondary,
                0.38f
            )
        }

internal val SettingsRightSurfaceFocusedColor: Color
    @Composable
    @ReadOnlyComposable
    get() =
        if (NuvioTheme.currentTheme == AppTheme.WHITE) {
            SettingsNeutralSurfaceFocusedColor
        } else {
            lerp(
                SettingsNeutralSurfaceFocusedColor,
                NuvioColors.Secondary,
                0.50f
            )
        }

internal val SettingsInsetControlColor: Color
    @Composable
    @ReadOnlyComposable
    get() = lerp(
        SettingsRightSurfaceColor,
        Color.Black,
        0.16f
    )

internal val SettingsInsetControlFocusedColor: Color
    @Composable
    @ReadOnlyComposable
    get() = lerp(
        SettingsRightSurfaceColor,
        Color.Black,
        0.06f
    )

internal val SettingsGlassGroupColor = Color.White.copy(alpha = 0.025f)
internal val SettingsGlassBorderColor = Color.White.copy(alpha = 0.09f)
internal val SettingsGlassFocusBorderColor = Color.White.copy(alpha = 0.28f)
internal val SettingsGlassDividerColor = Color.White.copy(alpha = 0.08f)
internal val SettingsGlassControlIdleColor = Color.Transparent
internal val SettingsGlassControlSelectedColor: Color
    @Composable get() = NuvioColors.Secondary.copy(alpha = 0.22f)

@Composable
internal fun SettingsGlassBackdrop(
    modifier: Modifier = Modifier
) {
    val bitmap = LocalSettingsBackdropBitmap.current

    Box(modifier = modifier) {
        if (bitmap != null && !bitmap.isRecycled) {
            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(30.dp)
                    .graphicsLayer {
                        scaleX = 1.06f
                        scaleY = 1.06f
                    }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SettingsGlassCanvasBrush)
        )
    }
}

@Composable
internal fun SettingsGlassScreen(
    content: @Composable BoxScope.() -> Unit
) {
    SettingsCompactContent {
        SettingsGlassScreenContent(content)
    }
}

@Composable
private fun SettingsGlassScreenContent(
    content: @Composable BoxScope.() -> Unit
) {
    val dialogHazeState = remember { HazeState() }
    val dialogBlurEnabled =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val dialogGlassEnvironment = remember(
        dialogHazeState,
        dialogBlurEnabled
    ) {
        HomePopupGlassEnvironment(
            hazeState = dialogHazeState,
            blurEnabled = dialogBlurEnabled,
            backdropAlreadyBlurred = true
        )
    }

    CompositionLocalProvider(
        LocalHomePopupGlassEnvironment provides dialogGlassEnvironment
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (dialogBlurEnabled) {
                        Modifier.haze(dialogHazeState)
                    } else {
                        Modifier
                    }
                )
        ) {
            SettingsGlassBackdrop(modifier = Modifier.fillMaxSize())
            content()
        }
    }
}

@Composable
internal fun SettingsStandaloneScaffold(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsCompactContent {
        SettingsStandaloneScaffoldContent(
            title = title,
            subtitle = subtitle,
            content = content
        )
    }
}

@Composable
private fun SettingsStandaloneScaffoldContent(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val dialogHazeState = remember { HazeState() }
    val dialogBlurEnabled =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val dialogGlassEnvironment = remember(
        dialogHazeState,
        dialogBlurEnabled
    ) {
        HomePopupGlassEnvironment(
            hazeState = dialogHazeState,
            blurEnabled = dialogBlurEnabled,
            backdropAlreadyBlurred = true
        )
    }

    CompositionLocalProvider(
        LocalHomePopupGlassEnvironment provides dialogGlassEnvironment
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (dialogBlurEnabled) {
                        Modifier.haze(dialogHazeState)
                    } else {
                        Modifier
                    }
                )
        ) {
            SettingsGlassBackdrop(modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                SettingsWorkspaceSurface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsBrandPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    showBuiltInHeader: Boolean = true
) {
    val titleColor = if (showBuiltInHeader) NuvioColors.TextPrimary else Color.Transparent
    val subtitleColor = if (showBuiltInHeader) NuvioColors.TextSecondary else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(SettingsContainerRadius))
            .background(SettingsGlassGroupColor)
            .border(
                width = 1.dp,
                color = SettingsGlassBorderColor,
                shape = RoundedCornerShape(SettingsContainerRadius)
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SettingsGlassRowColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = titleColor
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.titleLarge,
                color = titleColor
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Image(
            painter = painterResource(id = R.drawable.app_logo_wordmark),
            contentDescription = "NuvioTV",
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(56.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.settings_rounded_ui),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.2.sp,
            color = subtitleColor
        )
    }
}

@Composable
internal fun SettingsWorkspaceSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier,
        content = content
    )
}

@Composable
internal fun SettingsRailButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    icon: ImageVector? = null,
    rawIconRes: Int? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val appliedModifier = if (focusRequester != null) {
        modifier.focusRequester(focusRequester)
    } else {
        modifier
    }
    val focusShape = RoundedCornerShape(SettingsRailFocusRadius)
    val focusedRailColor =
        if (NuvioTheme.currentTheme == AppTheme.WHITE) {
            Color.White.copy(alpha = 0.18f)
        } else {
            SettingsRightSurfaceFocusedColor
        }
    val focusBackground by animateColorAsState(
        targetValue =
            if (isFocused) {
                focusedRailColor
            } else {
                Color.Transparent
            },
        animationSpec = tween(durationMillis = 180),
        label = "settingsRailFocusBackground"
    )
    val focusedTextScale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "settingsRailTextScale"
    )

    Card(
        onClick = onClick,
        modifier = appliedModifier
            .fillMaxWidth()
            .heightIn(min = SettingsRailItemHeight)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = focusBackground,
            focusedContainerColor = focusBackground
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = CardDefaults.shape(focusShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRailItemHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rawIconRes != null) {
                Image(
                    painter = rememberRawSvgPainter(rawIconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(
                        if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary
                    )
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (rawIconRes != null || icon != null) {
                Spacer(modifier = Modifier.width(10.dp))
            }

            Text(
                text = title,
                modifier = Modifier.graphicsLayer {
                    scaleX = focusedTextScale
                    scaleY = focusedTextScale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    isFocused -> Color.White
                    isSelected -> NuvioColors.TextPrimary
                    else -> NuvioColors.TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary
        )
    }
}

@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    segmented: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(SettingsSecondaryCardRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (segmented) {
                    Modifier.background(Color.Transparent)
                } else {
                    Modifier
                        .background(SettingsRightSurfaceColor)
                        .border(
                            width = 1.dp,
                            color = SettingsGlassBorderColor,
                            shape = shape
                        )
                }
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 2.dp)
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
            )
        }
        content()
    }
}

@Composable
internal fun SettingsExpandedSectionSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = settingsGroupShape(SettingsGroupPosition.BOTTOM)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap),
        content = content
    )
}

@Composable
internal fun SettingsRowDivider() {
    // Row separation is owned by the parent container so no trailing gap is
    // added after the final setting in a rounded group.
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    showDivider: Boolean = true,
    groupPosition: SettingsGroupPosition? = null,
    animateTopFlatten: Boolean = false,
    animateBottomFlatten: Boolean = false
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }
    val rowShape = if (groupPosition != null) {
        animatedSettingsGroupShape(
            position = groupPosition,
            animateTopFlatten = animateTopFlatten,
            animateBottomFlatten = animateBottomFlatten
        )
    } else {
        RoundedCornerShape(SettingsInnerRowRadius)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            onClick = {
                if (enabled) onToggle()
            },
            modifier = modifier
                .fillMaxWidth()
                .height(49.dp)
                .onFocusChanged { state ->
                    val nowFocused = state.isFocused
                    if (isFocused != nowFocused) {
                        isFocused = nowFocused
                        if (nowFocused) onFocused()
                    }
                },
            colors = CardDefaults.colors(
                containerColor = SettingsRightSurfaceColor,
                focusedContainerColor = SettingsRightSurfaceFocusedColor
            ),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = Border.None
            ),
            shape = CardDefaults.shape(rowShape),
            scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(49.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary.copy(alpha = contentAlpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                SettingsTogglePill(
                    checked = checked,
                    enabled = enabled
                )
            }
        }
        if (showDivider) {
            SettingsRowDivider()
        }
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String?,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    trailingIcon: ImageVector = Icons.Default.ChevronRight,
    showDivider: Boolean = true,
    groupPosition: SettingsGroupPosition? = null,
    animateTopFlatten: Boolean = false,
    animateBottomFlatten: Boolean = false
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }
    val rowShape = if (groupPosition != null) {
        animatedSettingsGroupShape(
            position = groupPosition,
            animateTopFlatten = animateTopFlatten,
            animateBottomFlatten = animateBottomFlatten
        )
    } else {
        RoundedCornerShape(SettingsInnerRowRadius)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            onClick = { if (enabled) onClick() },
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 49.dp)
                .onFocusChanged { state ->
                    val nowFocused = state.isFocused
                    if (isFocused != nowFocused) {
                        isFocused = nowFocused
                        if (nowFocused) onFocused()
                    }
                },
            colors = CardDefaults.colors(
                containerColor = SettingsRightSurfaceColor,
                focusedContainerColor = SettingsRightSurfaceFocusedColor
            ),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = Border.None
            ),
            shape = CardDefaults.shape(rowShape),
            scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 49.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary.copy(alpha = contentAlpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!value.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioColors.TextSecondary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = NuvioColors.TextTertiary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (showDivider) {
            SettingsRowDivider()
        }
    }
}

@Composable
internal fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 3.dp, vertical = 2.dp)
            .onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
                if (nowFocused) onFocused()
            }
        },
        colors = CardDefaults.colors(
            containerColor = if (selected) {
                NuvioColors.Secondary.copy(alpha = 0.22f)
            } else {
                SettingsGlassRowColor
            },
            focusedContainerColor = SettingsRightSurfaceFocusedColor
        ),
        border = CardDefaults.border(
            border = if (selected) Border(
                border = BorderStroke(1.dp, NuvioColors.Secondary.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(SettingsRowFocusRadius)
            ) else Border.None,
            focusedBorder = Border.None
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsRowFocusRadius)),
        scale = CardDefaults.scale(focusedScale = 1.025f, pressedScale = 0.99f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SettingsTogglePill(
    checked: Boolean,
    enabled: Boolean
) {
    val alpha = if (enabled) 1f else 0.35f
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(SettingsPillRadius))
            .background(
                if (checked) {
                    NuvioColors.Secondary.copy(alpha = 0.35f * alpha)
                } else {
                    Color.White.copy(alpha = 0.10f * alpha)
                }
            )
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = alpha))
        )
    }
}

/**
 * Shared neutral action used by every Reset to Default control. The label is
 * explicitly centered because TV Material buttons otherwise inherit differing
 * content arrangements from their call sites.
 */
@Composable
internal fun SettingsResetButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    groupPosition: SettingsGroupPosition? = null,
    useDialogGlass: Boolean = false
) {
    val shape = if (groupPosition != null) {
        animatedSettingsGroupShape(groupPosition)
    } else {
        RoundedCornerShape(SettingsSecondaryCardRadius)
    }
    val focusModifier =
        if (useDialogGlass) {
            Modifier.glassDialogFocusTransform { focused ->
                if (focused) onFocused()
            }
        } else {
            Modifier.onFocusChanged {
                if (it.isFocused) onFocused()
            }
        }

    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier.then(focusModifier),
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor =
                if (useDialogGlass) {
                    SettingsDialogGlassIdleColor
                } else {
                    SettingsRightSurfaceColor
                },
            focusedContainerColor =
                if (useDialogGlass) {
                    SettingsGlassRowFocusedColor
                } else {
                    SettingsRightSurfaceFocusedColor
                },
            contentColor =
                if (useDialogGlass) {
                    NuvioColors.TextSecondary
                } else {
                    NuvioColors.TextPrimary
                },
            focusedContentColor = NuvioColors.TextPrimary
        ),
        border = androidx.tv.material3.ButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = androidx.tv.material3.ButtonDefaults.shape(
            if (useDialogGlass) {
                SettingsDialogPillShape
            } else {
                shape
            }
        ),
        scale = androidx.tv.material3.ButtonDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.layout_reset_default),
                style = MaterialTheme.typography.titleMedium,
                color = androidx.tv.material3.LocalContentColor.current,
                maxLines = 1
            )
        }
    }
},
    groupPosition: SettingsGroupPosition? = null,
    useDialogGlass: Boolean = false
) {
    val shape = if (groupPosition != null) {
        animatedSettingsGroupShape(groupPosition)
    } else {
        RoundedCornerShape(SettingsSecondaryCardRadius)
    }
    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor =
                if (useDialogGlass) {
                    SettingsDialogGlassIdleColor
                } else {
                    SettingsRightSurfaceColor
                },
            focusedContainerColor =
                if (useDialogGlass) {
                    SettingsGlassRowFocusedColor
                } else {
                    SettingsRightSurfaceFocusedColor
                },
            contentColor = NuvioColors.TextPrimary,
            focusedContentColor = Color.White
        ),
        border = androidx.tv.material3.ButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = androidx.tv.material3.ButtonDefaults.shape(
            if (useDialogGlass) {
                SettingsDialogPillShape
            } else {
                shape
            }
        ),
        scale = androidx.tv.material3.ButtonDefaults.scale(
            focusedScale =
                if (useDialogGlass) {
                    SettingsDialogFocusScale
                } else {
                    1f
                },
            pressedScale =
                if (useDialogGlass) {
                    SettingsDialogPressedScale
                } else {
                    1f
                }
        )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.layout_reset_default),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val context = LocalContext.current
    val request = remember(rawIconRes, context) {
        ImageRequest.Builder(context)
            .data(rawIconRes)
            .decoderFactory(SvgDecoder.Factory())
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

internal data class SettingsPickerOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val trailing: String? = null,
    val titleFontFamily: FontFamily? = null
)

@Composable
internal fun <T> SettingsSingleChoiceDialog(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 360.dp,
    maxHeight: Dp = 260.dp
) {
    val focusRequester = remember { FocusRequester() }
    val focusedIndex = options.indexOfFirst { it.value == selectedValue }.let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusedIndex)

    LaunchedEffect(focusedIndex) { focusRequester.requestFocusAfterFrames() }

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        compact = true,
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width,
        suppressFirstKeyUp = false
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(items = options, key = { index, option -> "$index-${option.value}" }) { index, option ->
                    val isSelected = option.value == selectedValue
                    var isOptionFocused by remember(option.value) {
                        mutableStateOf(false)
                    }
                    Card(
                        onClick = { onOptionSelected(option.value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassDialogFocusTransform { focused ->
                                isOptionFocused = focused
                            }
                            .then(
                                if (index == focusedIndex) {
                                    Modifier.focusRequester(focusRequester)
                                } else {
                                    Modifier
                                }
                            ),
                        colors = CardDefaults.colors(
                            containerColor =
                                if (isSelected) {
                                    SettingsDialogGlassSelectedColor
                                } else {
                                    SettingsDialogGlassIdleColor
                                },
                            focusedContainerColor = SettingsGlassRowFocusedColor
                        ),
                        border = CardDefaults.border(
                            border = Border.None,
                            focusedBorder = Border.None
                        ),
                        shape = CardDefaults.shape(SettingsDialogPillShape),
                        scale = CardDefaults.scale(
                            focusedScale = 1f,
                            pressedScale = 1f
                        )
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.title,
                                    color =
                                        if (isOptionFocused || isSelected) {
                                            NuvioColors.TextPrimary
                                        } else {
                                            NuvioColors.TextSecondary
                                        },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (!option.description.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = option.description, color = NuvioColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (!option.trailing.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = option.trailing, style = MaterialTheme.typography.bodySmall, color = NuvioColors.TextSecondary)
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = NuvioColors.Primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T> SettingsMultiChoiceDialog(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValues: List<T>,
    onValuesSelected: (List<T>) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 440.dp,
    maxHeight: Dp = 340.dp
) {
    val focusRequester = remember { FocusRequester() }
    val selected = remember(selectedValues) { mutableStateListOf<T>().also { it.addAll(selectedValues) } }
    val firstSelectedIndex = options.indexOfFirst { option -> selectedValues.contains(option.value) }.let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstSelectedIndex)

    LaunchedEffect(firstSelectedIndex) { focusRequester.requestFocusAfterFrames() }

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        compact = true,
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width,
        suppressFirstKeyUp = false
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(items = options, key = { index, option -> "$index-${option.value}" }) { index, option ->
                        val isSelected = selected.contains(option.value)
                        var isOptionFocused by remember(option.value) {
                            mutableStateOf(false)
                        }
                        Card(
                            onClick = {
                                if (isSelected) {
                                    selected.remove(option.value)
                                } else {
                                    selected.add(option.value)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassDialogFocusTransform { focused ->
                                    isOptionFocused = focused
                                }
                                .then(
                                    if (index == firstSelectedIndex) {
                                        Modifier.focusRequester(focusRequester)
                                    } else {
                                        Modifier
                                    }
                                ),
                            colors = CardDefaults.colors(
                                containerColor =
                                if (isSelected) {
                                    SettingsDialogGlassSelectedColor
                                } else {
                                    SettingsDialogGlassIdleColor
                                },
                                focusedContainerColor = SettingsGlassRowFocusedColor
                            ),
                            border = CardDefaults.border(
                            border = Border.None,
                            focusedBorder = Border.None
                        ),
                        shape = CardDefaults.shape(SettingsDialogPillShape),
                            scale = CardDefaults.scale(
                                focusedScale = 1f,
                                pressedScale = 1f
                            )
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.title,
                                        color =
                                            if (isOptionFocused || isSelected) {
                                                NuvioColors.TextPrimary
                                            } else {
                                                NuvioColors.TextSecondary
                                            },
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = NuvioColors.Primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_clear), onClick = { selected.clear() })
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_save),
                    onClick = { onValuesSelected(options.map { it.value }.filter { selected.contains(it) }) },
                    primary = true
                )
            }
        }
    }
}

@Composable
internal fun SettingsDialogActionRow(
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, horizontalAlignment),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun SettingsDialogActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val containerColor =
        when {
            isFocused -> SettingsGlassRowFocusedColor
            primary -> SettingsDialogGlassSelectedColor
            else -> SettingsDialogGlassIdleColor
        }
    val contentColor =
        if (isFocused || primary) {
            NuvioColors.TextPrimary
        } else {
            NuvioColors.TextSecondary
        }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.glassDialogFocusTransform { focused ->
            isFocused = focused
        },
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = SettingsGlassRowFocusedColor,
            contentColor = contentColor,
            focusedContentColor = NuvioColors.TextPrimary
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = ButtonDefaults.shape(SettingsDialogPillShape),
        scale = ButtonDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        )
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
