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
import com.nuvio.tv.R as NuvioR
import com.nuvio.tv.R
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                AggregatePlatformsToggleRow(
                    checked = uiState.aggregateStreamingPlatformsEnabled,
                    onToggle = { viewModel.toggleAggregatePlatforms() }
                )
            }

            if (uiState.aggregateStreamingPlatformsEnabled) {
                item {
                    ShowAllCatalogsOnHomeToggleRow(
                        checked = uiState.showAllCatalogsOnHome,
                        onToggle = { viewModel.toggleShowAllCatalogsOnHome() }
                    )
                }
                item {
                    FastPlatformScrollToggleRow(
                        checked = uiState.fastPlatformScrollEnabled,
                        onToggle = { viewModel.toggleFastPlatformScroll() }
                    )
                }
                item {
                    FullWidthIconRowToggleRow(
                        checked = uiState.fullWidthIconRowEnabled,
                        onToggle = { viewModel.toggleFullWidthIconRow() }
                    )
                }
                item {
                    DimIconsOnRowExitToggleRow(
                        checked = uiState.dimIconsOnRowExitEnabled,
                        onToggle = { viewModel.toggleDimIconsOnRowExit() }
                    )
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
                        items = uiState.items
                    ) { index, item ->
                        CatalogOrderCard(
                            item = item,
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
    onMoveToTop: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit,
    onToggleNumbered: () -> Unit,
    onToggleLandscape: () -> Unit,
    globalLandscapeEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NuvioColors.BackgroundCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.catalogName} - ${item.typeLabel.toDisplayTypeLabel()}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (item.isDisabled) NuvioColors.TextSecondary else NuvioColors.TextPrimary
                )
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onMoveToTop,
                    enabled = item.canMoveUp,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(
                        painter = painterResource(id = NuvioR.drawable.ic_move_to_top),
                        contentDescription = "Move to top",
                        modifier = Modifier.size(24.dp)
                    )
                }

                Button(
                    onClick = onMoveUp,
                    enabled = item.canMoveUp,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Move up",
                        modifier = Modifier.size(24.dp)
                    )
                }

                Button(
                    onClick = onMoveDown,
                    enabled = item.canMoveDown,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Move down",
                        modifier = Modifier.size(24.dp)
                    )
                }

                Button(
                    onClick = onToggleNumbered,
                    colors = ButtonDefaults.colors(
                        containerColor = if (item.numberStyle != com.nuvio.tv.ui.screens.home.NumberStyle.OFF) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                        contentColor = if (item.numberStyle != com.nuvio.tv.ui.screens.home.NumberStyle.OFF) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f),
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = if (item.numberStyle != com.nuvio.tv.ui.screens.home.NumberStyle.OFF) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f)
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    val hashFont = when (item.numberStyle) {
                        com.nuvio.tv.ui.screens.home.NumberStyle.OUTLINE -> FontFamily(Font(R.font.sf_distant_galaxy_outline))
                        else -> FontFamily(Font(R.font.sf_distant_galaxy_alternate))
                    }
                    Text(
                        text = "#",
                        style = TextStyle(
                            fontFamily = hashFont,
                            fontSize = 28.sp,
                            lineHeight = 28.sp
                        ),
                        modifier = Modifier.padding(top = if (item.numberStyle == com.nuvio.tv.ui.screens.home.NumberStyle.OUTLINE) 7.dp else 8.dp)
                    )
                }

                if (!globalLandscapeEnabled) Button(
                    onClick = onToggleLandscape,
                    colors = ButtonDefaults.colors(
                        containerColor = if (item.isLandscape) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                        contentColor = if (item.isLandscape) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f),
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = if (item.isLandscape) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f)
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_landscape_poster),
                        contentDescription = "Toggle landscape posters",
                        modifier = Modifier.size(24.dp)
                    )
                }

                Button(
                    onClick = onToggleEnabled,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = if (item.isDisabled) NuvioColors.Success else NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
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
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
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
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else NuvioColors.Border
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
private fun FastPlatformScrollToggleRow(
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
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.catalog_fast_platform_scroll_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.catalog_fast_platform_scroll_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else NuvioColors.Border
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
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
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
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else NuvioColors.Border
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
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
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
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else NuvioColors.Border
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
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
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
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else NuvioColors.Border
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
            .height(62.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
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
            val pillColor = if (checked) NuvioColors.Secondary.copy(alpha = 0.35f) else NuvioColors.Border
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
