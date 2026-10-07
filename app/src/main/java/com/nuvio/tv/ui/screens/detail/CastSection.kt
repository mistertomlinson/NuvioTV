package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.nuvioCardDepth

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun CastSection(
    cast: List<MetaCastMember>,
    modifier: Modifier = Modifier,
    title: String = "Cast",
    leadingCast: List<MetaCastMember> = emptyList(),
    upFocusRequester: FocusRequester? = null,
    restoreFocusKey: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onCastMemberFocused: (MetaCastMember) -> Unit = {},
    onCastMemberClick: (MetaCastMember, String) -> Unit = { _, _ -> }
) {
    if (cast.isEmpty() && leadingCast.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val castPrefetchStrategy =
        remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }
    val lazyListState =
        rememberLazyListState(prefetchStrategy = castPrefetchStrategy)

    fun focusKeyFor(
        section: String,
        index: Int,
        member: MetaCastMember
    ): String {
        return "$section:$index:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
    }

    val firstFocusKey = remember(leadingCast, cast) {
        when {
            leadingCast.isNotEmpty() ->
                focusKeyFor("leading", 0, leadingCast.first())

            cast.isNotEmpty() ->
                focusKeyFor("cast", 0, cast.first())

            else -> null
        }
    }

    LaunchedEffect(cast, leadingCast) {
        val validKeys = buildSet {
            leadingCast.forEachIndexed { index, member ->
                add(focusKeyFor("leading", index, member))
            }
            cast.forEachIndexed { index, member ->
                add(focusKeyFor("cast", index, member))
            }
        }
        itemFocusRequesters.keys.retainAll(validKeys)
    }

    /*
     * CAST_RETURN_STABLE_FOCUS
     *
     * Every card keeps one FocusRequester for its lifetime. Restoration
     * requests that card's existing requester rather than temporarily
     * replacing it with a shared restore requester.
     */
    LaunchedEffect(
        restoreFocusToken,
        restoreFocusKey,
        leadingCast,
        cast
    ) {
        val targetKey = restoreFocusKey
        if (restoreFocusToken <= 0 || targetKey.isNullOrBlank()) {
            return@LaunchedEffect
        }

        val leadingIndex =
            leadingCast.indices.firstOrNull { index ->
                focusKeyFor(
                    "leading",
                    index,
                    leadingCast[index]
                ) == targetKey
            }

        val castIndex =
            cast.indices.firstOrNull { index ->
                focusKeyFor(
                    "cast",
                    index,
                    cast[index]
                ) == targetKey
            }

        val targetLazyIndex = when {
            leadingIndex != null -> leadingIndex

            castIndex != null -> {
                val dividerCount =
                    if (leadingCast.isNotEmpty() && cast.isNotEmpty()) 1 else 0

                leadingCast.size + dividerCount + castIndex
            }

            else -> return@LaunchedEffect
        }

        // Allow the returning Detail composition/focus tree to attach.
        repeat(2) {
            withFrameNanos { }
        }

        repeat(8) { attempt ->
            val requester =
                if (targetKey == firstFocusKey) {
                    firstItemFocusRequester
                } else {
                    itemFocusRequesters[targetKey]
                }

            if (
                requester != null &&
                runCatching { requester.requestFocus() }
                    .getOrDefault(false)
            ) {
                return@LaunchedEffect
            }

            /*
             * Normally the selected card is still composed because the
             * retained LazyRow state places us exactly where we left it.
             * This is only a safety fallback if that card is not composed.
             */
            if (attempt == 0 && requester == null) {
                lazyListState.scrollToItem(targetLazyIndex)
            }

            withFrameNanos { }
        }
    }

    val itemWidth = 150.dp
    val cardSize = 100.dp
    val hasTitle = title.isNotBlank()
    val upFocusModifier = if (upFocusRequester != null) {
        Modifier.focusProperties { up = upFocusRequester }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (hasTitle) 20.dp else 8.dp, bottom = 8.dp)
    ) {
        if (hasTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = NuvioColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer { firstItemFocusRequester },
            state = lazyListState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            val standardGap = 8.dp
            val deadSpace = itemWidth - cardSize

            if (leadingCast.isNotEmpty()) {
                itemsIndexed(
                    items = leadingCast,
                    key = { index, member ->
                        "leading|" + index + "|" + (member.tmdbId?.toString() ?: member.name) + "|" + (member.character ?: "") + "|" + (member.photo ?: "")
                    }
                ) { index, member ->
                    val isLastLeading = member == leadingCast.last()
                    val endPadding = if (isLastLeading && cast.isNotEmpty()) 0.dp else standardGap
                    val focusKey =
                        focusKeyFor("leading", index, member)
                    val isRestoreTarget =
                        focusKey == restoreFocusKey

                    val focusRequester =
                        if (focusKey == firstFocusKey) {
                            firstItemFocusRequester
                        } else {
                            remember(focusKey) {
                                itemFocusRequesters.getOrPut(focusKey) {
                                    FocusRequester()
                                }
                            }
                        }

                    Box(modifier = Modifier.padding(end = endPadding)) {
                        CastMemberItem(
                            member = member,
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .then(upFocusModifier),
                            itemWidth = itemWidth,
                            cardSize = cardSize,
                            onFocused = {
                                onCastMemberFocused(member)
                                if (isRestoreTarget && restoreFocusToken > 0) {
                                    onRestoreFocusHandled()
                                }
                            },
                            onClick = {
                                onCastMemberClick(member, focusKey)
                            }
                        )
                    }
                }
            }

            if (leadingCast.isNotEmpty() && cast.isNotEmpty()) {
                item(key = "role_divider") {
                    Box(
                        modifier = Modifier.height(cardSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(72.dp)
                                .offset(x = -deadSpace / 2)
                                .background(NuvioColors.SurfaceVariant.copy(alpha = 0.9f))
                        )
                    }
                }
            }

            itemsIndexed(
                items = cast,
                key = { index, member ->
                    index.toString() + "|" + (member.tmdbId?.toString() ?: member.name) + "|" + (member.character ?: "") + "|" + (member.photo ?: "")
                }
            ) { index, member ->
                val focusKey =
                    focusKeyFor("cast", index, member)
                val isRestoreTarget =
                    focusKey == restoreFocusKey

                val focusRequester =
                    if (focusKey == firstFocusKey) {
                        firstItemFocusRequester
                    } else {
                        remember(focusKey) {
                            itemFocusRequesters.getOrPut(focusKey) {
                                FocusRequester()
                            }
                        }
                    }

                Box(modifier = Modifier.padding(end = standardGap)) {
                    CastMemberItem(
                        member = member,
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .then(upFocusModifier),
                        itemWidth = itemWidth,
                        cardSize = cardSize,
                        onFocused = {
                            onCastMemberFocused(member)
                            if (isRestoreTarget && restoreFocusToken > 0) {
                                onRestoreFocusHandled()
                            }
                        },
                        onClick = {
                            onCastMemberClick(member, focusKey)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastMemberItem(
    member: MetaCastMember,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 150.dp,
    cardSize: Dp = 100.dp,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardSizePx = remember(cardSize, density) {
        with(density) { cardSize.roundToPx() }
    }
    val typography = MaterialTheme.typography
    val nameStyle = remember(typography) { typography.labelMedium }
    val characterStyle = remember(typography) { typography.labelSmall }
    val initialsStyle = remember(typography) { typography.titleLarge }
    val cardDepthStyle = LocalCardDepthStyle.current
    val castDepthModifier = remember(cardDepthStyle) {
        Modifier.nuvioCardDepth(
            shape = CircleShape,
            surface = CardDepthSurface.CAST,
            style = cardDepthStyle
        )
    }
    val photo = member.photo
    val photoModel = remember(context, photo, cardSizePx) {
        photo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = cardSizePx, height = cardSizePx)
                .build()
        }
    }

    Column(
        modifier = Modifier.width(itemWidth),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            onClick = onClick,
            modifier = modifier
                .size(cardSize)
                .align(Alignment.Start)
                .onFocusChanged { state ->
                    if (state.isFocused) onFocused()
                },
            shape = CardDefaults.shape(
                shape = CircleShape
            ),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.SurfaceVariant,
                focusedContainerColor = NuvioColors.FocusBackground
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = CircleShape
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(castDepthModifier),
                contentAlignment = Alignment.Center
            ) {
                if (photoModel != null) {
                    AsyncImage(
                        model = photoModel,
                        contentDescription = member.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = member.name.firstOrNull()?.uppercase() ?: "?",
                        style = initialsStyle,
                        color = NuvioColors.TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = member.name,
            style = nameStyle,
            color = NuvioColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        val character = member.character
        if (!character.isNullOrBlank()) {
            val displayCharacter = when {
                character.equals("Creator", ignoreCase = true) -> stringResource(R.string.cast_role_creator)
                character.equals("Director", ignoreCase = true) -> stringResource(R.string.cast_role_director)
                character.equals("Writer", ignoreCase = true) -> stringResource(R.string.cast_role_writer)
                else -> character
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = displayCharacter,
                style = characterStyle,
                color = NuvioColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
