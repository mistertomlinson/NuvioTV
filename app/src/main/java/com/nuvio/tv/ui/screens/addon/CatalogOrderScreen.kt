@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.addon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsCompactContent
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.R as NuvioR
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.SettingsGlassBorderColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassControlIdleColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassControlSelectedColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassGroupColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassRowColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassRowFocusedColor
import kotlinx.coroutines.launch
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.Card

@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    SettingsCompactContent {
        CatalogOrderScreenContent(
            viewModel = viewModel,
            onBackPress = onBackPress
        )
    }
}

@Composable
private fun CatalogOrderScreenContent(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Pickup/drop reordering is intentionally screen-local while moving.
    // We only persist the final order when the user presses Enter to drop.
    var pickedUpKey by remember { mutableStateOf<String?>(null) }
    var previewItems by remember { mutableStateOf<List<CatalogOrderItem>?>(null) }

    // After Enter drops a row, keep showing the final preview until the
    // persisted order comes back through the ViewModel. This prevents a
    // one-frame flash of the old ordering.
    var pendingDroppedOrderKeys by remember {
        mutableStateOf<List<String>?>(null)
    }

    // A held D-pad may request another move before the previous list scroll
    // animation completes. Keep only the newest animation target.
    var pickedMoveScrollJob by remember {
        mutableStateOf<kotlinx.coroutines.Job?>(null)
    }

    // Capture once when the row is picked up. Reusing this exact anchor during
    // fast D-pad repeat prevents the carried row from drifting up/down while
    // an earlier scroll animation is still in progress.
    var pickedRowAnchorScrollOffset by remember {
        mutableStateOf<Int?>(null)
    }

    val displayedItems = previewItems ?: uiState.items

    LaunchedEffect(uiState.items, pendingDroppedOrderKeys) {
        val pending = pendingDroppedOrderKeys ?: return@LaunchedEffect
        if (uiState.items.map { it.key } == pending) {
            previewItems = null
            pendingDroppedOrderKeys = null
        }
    }

    // There are three LazyColumn items before the catalog rows:
    // title/subtitle, streaming-platform settings, and number-theme toggle.
    val catalogListStartIndex = 3

    fun movePickedRow(direction: Int) {
        val key = pickedUpKey ?: return
        val current = previewItems ?: return
        val fromIndex = current.indexOfFirst { it.key == key }
        if (fromIndex == -1) return

        val toIndex = fromIndex + direction
        if (toIndex !in current.indices) return

        // Never recalculate this while carrying. A fresh measurement taken
        // during an in-flight animation is what caused fast-scroll drift.
        val anchorScrollOffset = pickedRowAnchorScrollOffset

        val reordered = current.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }.mapIndexed { index, item ->
            item.copy(
                canMoveUp = index > 0,
                canMoveDown = index < current.lastIndex
            )
        }

        previewItems = reordered

        pickedMoveScrollJob?.cancel()
        pickedMoveScrollJob = scope.launch {
            val toLazyIndex = catalogListStartIndex + toIndex

            if (anchorScrollOffset != null) {
                // Animate the list underneath the carried row while preserving
                // approximately the same focused position in the viewport.
                listState.animateScrollToItem(
                    index = toLazyIndex,
                    scrollOffset = anchorScrollOffset
                )
            } else {
                // Defensive fallback only if the row somehow wasn't visible.
                listState.animateScrollToItem(toLazyIndex)
            }
        }
    }

    BackHandler {
        if (pickedUpKey != null) {
            // Cancel pickup and restore the untouched persisted order.
            pickedMoveScrollJob?.cancel()
            pickedMoveScrollJob = null
            pickedUpKey = null
            pickedRowAnchorScrollOffset = null
            previewItems = null
            pendingDroppedOrderKeys = null
        } else {
            onBackPress()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 18.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (pickedUpKey == null) {
                        Modifier.dpadRepeatThrottle(
                            horizontalGateMs = 0L,
                            verticalGateMs = 100L
                        )
                    } else {
                        Modifier
                    }
                ),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.catalog_order_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.catalog_order_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }

            item {
                var streamingPlatformSettingsExpanded by remember { mutableStateOf(false) }
                val headerFocusRequester = remember { FocusRequester() }
                CatalogCollapsibleSectionCard(
                    title = "Streaming Platform Settings",
                    description = "Controls how streaming platforms are handled",
                    expanded = streamingPlatformSettingsExpanded,
                    onToggle = { streamingPlatformSettingsExpanded = !streamingPlatformSettingsExpanded },
                    focusRequester = headerFocusRequester
                ) {
                    AggregatePlatformsToggleRow(
                        checked = uiState.aggregateStreamingPlatformsEnabled,
                        onToggle = { viewModel.toggleAggregatePlatforms() }
                    )
                    if (uiState.aggregateStreamingPlatformsEnabled) {
                        ShowAllCatalogsOnHomeToggleRow(
                            checked = uiState.showAllCatalogsOnHome,
                            onToggle = { viewModel.toggleShowAllCatalogsOnHome() }
                        )
                        HidePlatformNameToggleRow(
                            checked = uiState.hidePlatformNameInCatalogTitleEnabled,
                            onToggle = { viewModel.toggleHidePlatformNameInCatalogTitle() }
                        )
                        FullWidthIconRowToggleRow(
                            checked = uiState.fullWidthIconRowEnabled,
                            onToggle = { viewModel.toggleFullWidthIconRow() }
                        )
                        if (uiState.fullWidthIconRowEnabled) {
                            HeroMetadataSizeToggleRow(
                                checked = uiState.heroMetadataLarge,
                                onToggle = { viewModel.toggleHeroMetadataLarge() }
                            )
                        }
                        HidePlatformIconsOnRowExitToggleRow(
                            checked = uiState.hidePlatformIconsOnRowExitEnabled,
                            onToggle = { viewModel.toggleHidePlatformIconsOnRowExit() }
                        )
                        if (!uiState.hidePlatformIconsOnRowExitEnabled) {
                            DimIconsOnRowExitToggleRow(
                                checked = uiState.dimIconsOnRowExitEnabled,
                                onToggle = { viewModel.toggleDimIconsOnRowExit() }
                            )
                        }
                    }
                }
            }

            item {
                ThemeColorToggleRow(
                    checked = uiState.useThemeColorForNumbers,
                    onToggle = { viewModel.toggleUseThemeColorForNumbers() }
                )
            }

            when {
                uiState.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }
                }

                uiState.items.isEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.catalog_order_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioColors.TextSecondary
                        )
                    }
                }

                else -> {
                    itemsIndexed(
                        items = displayedItems,
                        key = { _, item -> item.key }
                    ) { index, item ->
                        CatalogOrderCard(
                            item = item,
                            isPickedUp = pickedUpKey == item.key,
                            onTogglePickup = {
                                if (pickedUpKey == item.key) {
                                    // Enter while carrying = drop and persist.
                                    // Keep the preview frozen until DataStore's
                                    // observed order confirms the same result.
                                    val droppedKeys =
                                        (previewItems ?: displayedItems).map { it.key }

                                    pickedMoveScrollJob?.cancel()
                                    pickedMoveScrollJob = null
                                    pendingDroppedOrderKeys = droppedKeys
                                    viewModel.setCatalogOrder(droppedKeys)
                                    pickedUpKey = null
                                    pickedRowAnchorScrollOffset = null
                                } else if (pickedUpKey == null) {
                                    // Enter on handle = pick this row up and
                                    // permanently anchor it to this viewport Y.
                                    val lazyIndex = catalogListStartIndex + index
                                    val layoutInfo = listState.layoutInfo
                                    val visibleItem =
                                        layoutInfo.visibleItemsInfo.firstOrNull {
                                            it.index == lazyIndex
                                        }

                                    pickedRowAnchorScrollOffset =
                                        visibleItem?.let { visible ->
                                            layoutInfo.viewportStartOffset -
                                                visible.offset
                                        }

                                    pendingDroppedOrderKeys = null
                                    previewItems = displayedItems.toList()
                                    pickedUpKey = item.key
                                }
                            },
                            onMovePicked = { direction ->
                                movePickedRow(direction)
                            },
                            onMoveToTop = {
                                viewModel.moveToTop(item.key)
                            },
                            onMoveUp = {
                                viewModel.moveUp(item.key)
                            },
                            onMoveDown = {
                                viewModel.moveDown(item.key)
                            },
                            onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) },
                            onToggleNumbered = { viewModel.toggleCatalogNumbered(item.key) },
                            onToggleLandscape = { viewModel.toggleCatalogLandscape(item.key) },
                            onToggleShuffle = { viewModel.toggleCatalogShuffle(item.key) },
                            globalLandscapeEnabled = uiState.globalLandscapePostersEnabled
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogOrderCard(
    item: CatalogOrderItem,
    isPickedUp: Boolean,
    onTogglePickup: () -> Unit,
    onMovePicked: (Int) -> Unit,
    onMoveToTop: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit,
    onToggleNumbered: () -> Unit,
    onToggleLandscape: () -> Unit,
    onToggleShuffle: () -> Unit,
    globalLandscapeEnabled: Boolean
) {
    val lastPickedMoveTime = remember(item.key) { longArrayOf(0L) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isPickedUp) 1f else 0f)
            .graphicsLayer {
                val pickedScale = if (isPickedUp) 1.025f else 1f
                scaleX = pickedScale
                scaleY = pickedScale
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isPickedUp) {
                // Lifted rows should read as raised/lighter, not selected by
                // another focus ring.
                SettingsGlassRowFocusedColor
            } else {
                SettingsGlassRowColor
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPickedUp) 12.dp else 0.dp
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                        onClick = onTogglePickup,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (!isPickedUp) {
                                return@onPreviewKeyEvent false
                            }

                            val native = event.nativeKeyEvent
                            if (native.action != android.view.KeyEvent.ACTION_DOWN) {
                                return@onPreviewKeyEvent false
                            }

                            val isVerticalMove =
                                native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                                    native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN

                            if (isVerticalMove) {
                                val now = android.os.SystemClock.uptimeMillis()

                                if (
                                    native.repeatCount > 0 &&
                                    now - lastPickedMoveTime[0] < 100L
                                ) {
                                    return@onPreviewKeyEvent true
                                }

                                lastPickedMoveTime[0] = now
                            }

                            when (native.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                    onMovePicked(-1)
                                    true
                                }

                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    onMovePicked(1)
                                    true
                                }

                                // While carrying, keep focus locked to the
                                // reorder handle until Enter drops or Back cancels.
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true

                                else -> false
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = if (isPickedUp) {
                                SettingsGlassRowFocusedColor
                            } else {
                                SettingsGlassControlIdleColor
                            },
                            contentColor = if (isPickedUp) {
                                NuvioColors.Primary
                            } else {
                                NuvioColors.TextSecondary
                            },
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(
                                    if (isPickedUp) 0.dp else 2.dp,
                                    if (isPickedUp) Color.Transparent
                                    else NuvioColors.FocusRing
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                        contentPadding = PaddingValues(
                            horizontal = 4.dp,
                            vertical = 4.dp
                        )
                    ) {
                        Canvas(modifier = Modifier.size(22.dp)) {
                            val stroke = 2.dp.toPx()

                            if (!isPickedUp) {
                                val lineColor = NuvioColors.TextSecondary
                                val left = size.width * 0.12f
                                val right = size.width * 0.88f

                                repeat(4) { line ->
                                    val y =
                                        size.height * (0.20f + line * 0.20f)

                                    drawLine(
                                        color = lineColor,
                                        start = Offset(left, y),
                                        end = Offset(right, y),
                                        strokeWidth = stroke,
                                        cap = StrokeCap.Round
                                    )
                                }
                            } else {
                                // Two permanently bright chevrons — essentially
                                // ">" rotated upward and downward.
                                val chevronColor = NuvioColors.TextPrimary

                                val cx = size.width * 0.50f
                                val halfWidth = size.width * 0.30f
                                val halfHeight = size.height * 0.11f

                                val upY = size.height * 0.24f
                                drawLine(
                                    color = chevronColor,
                                    start = Offset(
                                        cx - halfWidth,
                                        upY + halfHeight
                                    ),
                                    end = Offset(cx, upY - halfHeight),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = chevronColor,
                                    start = Offset(cx, upY - halfHeight),
                                    end = Offset(
                                        cx + halfWidth,
                                        upY + halfHeight
                                    ),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )

                                val downY = size.height * 0.76f
                                drawLine(
                                    color = chevronColor,
                                    start = Offset(
                                        cx - halfWidth,
                                        downY - halfHeight
                                    ),
                                    end = Offset(cx, downY + halfHeight),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = chevronColor,
                                    start = Offset(cx, downY + halfHeight),
                                    end = Offset(
                                        cx + halfWidth,
                                        downY - halfHeight
                                    ),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.isGroup) item.catalogName else "${item.catalogName} - ${item.typeLabel.toDisplayTypeLabel()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (item.isDisabled) NuvioColors.TextSecondary else NuvioColors.TextPrimary
                    )
                    if (
                        item.isGroup &&
                        (item.groupSize == 0 || item.groupSize > 1)
                    ) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(NuvioColors.Primary.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${item.groupSize}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioColors.Primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.addonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
                if (item.isDisabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.catalog_order_disabled_on_home),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.Error
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onMoveToTop,
                    enabled = item.canMoveUp,
                    colors = ButtonDefaults.colors(
                        containerColor = SettingsGlassControlIdleColor,
                        disabledContainerColor = SettingsGlassControlIdleColor,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = SettingsGlassRowFocusedColor,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        painter = painterResource(id = NuvioR.drawable.ic_move_to_top),
                        contentDescription = "Move to top",
                        modifier = Modifier.size(18.dp)
                    )
                }

                Button(
                    onClick = onMoveUp,
                    enabled = item.canMoveUp,
                    colors = ButtonDefaults.colors(
                        containerColor = SettingsGlassControlIdleColor,
                        disabledContainerColor = SettingsGlassControlIdleColor,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = SettingsGlassRowFocusedColor,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Move up",
                        modifier = Modifier.size(18.dp)
                    )
                }

                Button(
                    onClick = onMoveDown,
                    enabled = item.canMoveDown,
                    colors = ButtonDefaults.colors(
                        containerColor = SettingsGlassControlIdleColor,
                        disabledContainerColor = SettingsGlassControlIdleColor,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = SettingsGlassRowFocusedColor,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Move down",
                        modifier = Modifier.size(18.dp)
                    )
                }

                Button(
                    onClick = onToggleNumbered,
                    colors = ButtonDefaults.colors(
                        containerColor =
                            if (
                                item.numberStyle !=
                                com.nuvio.tv.ui.screens.home.NumberStyle.OFF
                            ) {
                                SettingsGlassControlSelectedColor
                            } else {
                                SettingsGlassControlIdleColor
                            },
                        contentColor = if (item.numberStyle != com.nuvio.tv.ui.screens.home.NumberStyle.OFF) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f),
                        focusedContainerColor = SettingsGlassRowFocusedColor,
                        focusedContentColor = if (item.numberStyle != com.nuvio.tv.ui.screens.home.NumberStyle.OFF) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f)
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    val hashFont = when (item.numberStyle) {
                        com.nuvio.tv.ui.screens.home.NumberStyle.OUTLINE -> FontFamily(Font(R.font.sf_distant_galaxy_outline))
                        else -> FontFamily(Font(R.font.sf_distant_galaxy_alternate))
                    }
                    Text(
                        text = "#",
                        style = TextStyle(
                            fontFamily = hashFont,
                            fontSize = 22.sp,
                            lineHeight = 22.sp
                        ),
                        modifier = Modifier.padding(
                            top = if (
                                item.numberStyle ==
                                com.nuvio.tv.ui.screens.home.NumberStyle.OUTLINE
                            ) 4.dp else 5.dp
                        )
                    )
                }

                if (!globalLandscapeEnabled) Button(
                    onClick = onToggleLandscape,
                    colors = ButtonDefaults.colors(
                        containerColor =
                            if (item.isLandscape) {
                                SettingsGlassControlSelectedColor
                            } else {
                                SettingsGlassControlIdleColor
                            },
                        contentColor = if (item.isLandscape) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f),
                        focusedContainerColor = SettingsGlassRowFocusedColor,
                        focusedContentColor = if (item.isLandscape) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f)
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_landscape_poster),
                        contentDescription = "Toggle landscape posters",
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (!item.isGroup) Button(
                    onClick = onToggleShuffle,
                    colors = ButtonDefaults.colors(
                        containerColor =
                            if (item.isShuffled) {
                                SettingsGlassControlSelectedColor
                            } else {
                                SettingsGlassControlIdleColor
                            },
                        contentColor = if (item.isShuffled) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f),
                        focusedContainerColor = SettingsGlassRowFocusedColor,
                        focusedContentColor = if (item.isShuffled) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f)
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shuffle_catalog),
                        contentDescription = "Toggle shuffle",
                        modifier = Modifier.size(18.dp)
                    )
                }

                Button(
                    onClick = onToggleEnabled,
                    colors = ButtonDefaults.colors(
                        containerColor = SettingsGlassControlIdleColor,
                        contentColor = if (item.isDisabled) NuvioColors.Success else NuvioColors.TextSecondary,
                        focusedContainerColor = SettingsGlassRowFocusedColor,
                        focusedContentColor = if (item.isDisabled) NuvioColors.Success else NuvioColors.Error
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Text(text = if (item.isDisabled) stringResource(R.string.catalog_order_enable) else stringResource(R.string.catalog_order_disable))
                }
            }
        }
    }
}

private fun String.toDisplayTypeLabel(): String {
    return replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}

@Composable
private fun ShowAllCatalogsOnHomeToggleRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsGlassGroupColor,
            focusedContainerColor = SettingsGlassGroupColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.catalog_show_all_on_home_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.catalog_show_all_on_home_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else SettingsGlassBorderColor
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun HidePlatformNameToggleRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsGlassGroupColor,
            focusedContainerColor = SettingsGlassGroupColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.catalog_hide_platform_name_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.catalog_hide_platform_name_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else SettingsGlassBorderColor
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun CatalogCollapsibleSectionCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsActionRow(
            title = title,
            subtitle = description,
            value = if (expanded) stringResource(R.string.layout_open) else stringResource(R.string.layout_closed),
            onClick = onToggle,
            trailingIcon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            modifier = Modifier.focusRequester(focusRequester)
        )
        if (expanded) {
            SettingsGroupCard {
                content()
            }
        }
    }
}

@Composable
private fun HeroMetadataSizeToggleRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsGlassGroupColor,
            focusedContainerColor = SettingsGlassGroupColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.hero_metadata_large_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.hero_metadata_large_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else SettingsGlassBorderColor
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun AggregatePlatformsToggleRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsGlassGroupColor,
            focusedContainerColor = SettingsGlassGroupColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.catalog_aggregate_platforms_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.catalog_aggregate_platforms_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else SettingsGlassBorderColor
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun FullWidthIconRowToggleRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsGlassGroupColor,
            focusedContainerColor = SettingsGlassGroupColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.catalog_full_width_icon_row_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.catalog_full_width_icon_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else SettingsGlassBorderColor
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}


@Composable
private fun HidePlatformIconsOnRowExitToggleRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsGlassGroupColor,
            focusedContainerColor = SettingsGlassGroupColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = androidx.tv.material3.CardDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hide Icons on Row Exit",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Only show platform icons while the row is active",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            val pillColor =
                if (checked) {
                    NuvioColors.Secondary.copy(alpha = 0.35f)
                } else {
                    SettingsGlassBorderColor
                }

            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(2.dp),
                contentAlignment =
                    if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (checked) {
                                NuvioColors.Secondary
                            } else {
                                NuvioColors.TextSecondary
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun DimIconsOnRowExitToggleRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsGlassGroupColor,
            focusedContainerColor = SettingsGlassGroupColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dim Icons on Row Exit",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Icons only brighten when row is active",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else SettingsGlassBorderColor
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (checked) NuvioColors.Secondary else NuvioColors.TextSecondary)
                )
            }
        }
    }
}
@Composable
private fun ThemeColorToggleRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsGlassGroupColor,
            focusedContainerColor = SettingsGlassGroupColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.catalog_use_theme_color_numbers),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextPrimary
            )
            Spacer(modifier = Modifier.width(12.dp))
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else SettingsGlassBorderColor
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(pillColor)
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}
