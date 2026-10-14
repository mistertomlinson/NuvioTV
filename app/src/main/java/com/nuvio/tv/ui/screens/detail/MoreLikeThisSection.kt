package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MoreLikeThisSection(
    items: List<MetaPreview>,
    modifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    restoreItemId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onItemFocused: (MetaPreview) -> Unit = {},
    onItemClick: (MetaPreview) -> Unit
) {
    if (items.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val lazyListState = rememberLazyListState()

    LaunchedEffect(items) {
        val validIds = items.mapTo(mutableSetOf()) { it.id }
        itemFocusRequesters.keys.retainAll(validIds)
    }

    /*
     * MORE_LIKE_THIS_RETURN_STABLE_FOCUS
     *
     * Keep one FocusRequester attached to each recommendation for its lifetime.
     * The old restore path temporarily replaced the selected card's requester
     * with one shared restore requester, then replaced it again after focus was
     * handled. That focus-node swap could make Compose fall through to another
     * Details target before the recommendation reclaimed focus.
     */
    LaunchedEffect(restoreFocusToken, restoreItemId, items) {
        val targetId = restoreItemId
        if (restoreFocusToken <= 0 || targetId.isNullOrBlank()) {
            return@LaunchedEffect
        }

        val targetIndex = items.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return@LaunchedEffect

        // ON_START arms this restore before the destination reaches RESUMED.
        // Give the returning focus tree two frames to attach, matching the
        // stable Cast return path.
        repeat(2) {
            withFrameNanos { }
        }

        repeat(8) { attempt ->
            val requester =
                if (targetIndex == 0) {
                    firstItemFocusRequester
                } else {
                    itemFocusRequesters[targetId]
                }

            if (
                requester != null &&
                runCatching { requester.requestFocus() }
                    .getOrDefault(false)
            ) {
                return@LaunchedEffect
            }

            // Normally the retained LazyRow is already at the selected item.
            // This only handles a target that is no longer composed.
            if (attempt == 0 && requester == null) {
                lazyListState.scrollToItem(targetIndex)
            }

            withFrameNanos { }
        }
    }

    val landscapeStyle = remember {
        PosterCardStyle(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = 12.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.02f
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer { firstItemFocusRequester },
            state = lazyListState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { index, item -> item.id + "|" + item.name + "|" + index }
            ) { index, item ->
                val isRestoreTarget = item.id == restoreItemId
                val isFirstItem = index == 0
                val focusRequester =
                    if (isFirstItem) {
                        firstItemFocusRequester
                    } else {
                        remember(item.id) {
                            itemFocusRequesters.getOrPut(item.id) {
                                FocusRequester()
                            }
                        }
                    }

                Column {
                    GridContentCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        posterCardStyle = landscapeStyle,
                        showLabel = true,
                        imageCrossfade = true,
                        focusRequester = focusRequester,
                        upFocusRequester = upFocusRequester,
                        onFocused = {
                            onItemFocused(item)
                            if (isRestoreTarget && restoreFocusToken > 0) {
                                onRestoreFocusHandled()
                            }
                        }
                    )
                    val year = item.releaseInfo
                    if (!year.isNullOrBlank()) {
                        Text(
                            text = year,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(landscapeStyle.width)
                                .padding(start = 2.dp, end = 2.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
