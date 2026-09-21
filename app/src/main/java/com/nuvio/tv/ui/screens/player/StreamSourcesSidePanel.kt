@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
internal fun StreamSourcesSidePanel(
    uiState: PlayerUiState,
    streamsFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    modifier: Modifier = Modifier
) {
    // Prefer the currently playing source for initial focus. While a cold
    // result is still loading, the chip row may temporarily own focus. Once
    // Playing becomes available it may take focus only if the user has not
    // already started navigating.

    val orderedAddonNames = remember(uiState.sourceAvailableAddons, uiState.sourceChips) {
        buildList {
            uiState.sourceChips.forEach { chip ->
                if (chip.name !in this) add(chip.name)
            }

            uiState.sourceAvailableAddons.forEach { addon ->
                if (addon !in this) add(addon)
            }
        }
    }
    val allChipFocusRequester = remember { FocusRequester() }
    val addonChipFocusRequesters = remember {
        mutableMapOf<String, FocusRequester>()
    }
    val chipFocusRequesters = remember(orderedAddonNames) {
        addonChipFocusRequesters.keys.retainAll(orderedAddonNames.toSet())
        buildList {
            add(allChipFocusRequester)
            orderedAddonNames.forEach { addon ->
                add(
                    addonChipFocusRequesters.getOrPut(addon) {
                        FocusRequester()
                    }
                )
            }
        }
    }

    val sourceCurrentStreamIndex = remember(
        uiState.sourceFilteredStreams,
        uiState.currentSourceStreamKey,
        uiState.currentStreamInfoHash,
        uiState.currentStreamFileIdx,
        uiState.currentStreamUrl,
        uiState.currentStreamName,
        uiState.currentStreamAddonName,
        uiState.currentStreamDescription
    ) {
        findCurrentStreamIndex(
            streams = uiState.sourceFilteredStreams,
            currentSourceStreamKey = uiState.currentSourceStreamKey,
            currentStreamInfoHash = uiState.currentStreamInfoHash,
            currentStreamFileIdx = uiState.currentStreamFileIdx,
            currentStreamUrl = uiState.currentStreamUrl,
            currentStreamName = uiState.currentStreamName,
            currentStreamAddonName = uiState.currentStreamAddonName,
            currentStreamDescription = uiState.currentStreamDescription
        )
    }

    val displayStreams = remember(
        uiState.sourceFilteredStreams,
        sourceCurrentStreamIndex
    ) {
        if (sourceCurrentStreamIndex > 0) {
            buildList {
                add(uiState.sourceFilteredStreams[sourceCurrentStreamIndex])
                uiState.sourceFilteredStreams.forEachIndexed { index, stream ->
                    if (index != sourceCurrentStreamIndex) {
                        add(stream)
                    }
                }
            }
        } else {
            uiState.sourceFilteredStreams
        }
    }

    val currentStreamIndex =
        if (sourceCurrentStreamIndex >= 0) 0 else -1

    val streamKeys = remember(displayStreams) {
        val seenLogicalKeys = mutableSetOf<String>()
        val variantOccurrences = mutableMapOf<String, Int>()

        displayStreams.map { stream ->
            val baseKey = stream.stableKey(0)

            if (seenLogicalKeys.add(baseKey)) {
                baseKey
            } else {
                val variantKey = buildString {
                    append(baseKey)
                    append('\u0000')
                    append("provider-variant")
                    append('\u0000')
                    append(stream.debridCacheStatus?.providerId.orEmpty())
                }

                val occurrence = variantOccurrences[variantKey] ?: 0
                variantOccurrences[variantKey] = occurrence + 1

                buildString {
                    append(variantKey)
                    append('\u0000')
                    append(occurrence)
                }
            }
        }
    }

    val streamFocusRequesters = remember {
        mutableMapOf<String, FocusRequester>()
    }
    streamFocusRequesters.keys.retainAll(streamKeys.toSet())
    streamKeys.forEach { key ->
        streamFocusRequesters.getOrPut(key) {
            FocusRequester()
        }
    }

    val userInteractedBeforePlayingFocus = remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val playingFocusApplied = remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        if (uiState.sourceSelectedAddonFilter != null) {
            onAddonFilterSelected(null)
            androidx.compose.runtime.withFrameNanos { }
        }

        if (currentStreamIndex != 0) {
            runCatching {
                allChipFocusRequester.requestFocus()
            }
        }
    }

    LaunchedEffect(
        currentStreamIndex,
        streamKeys,
        uiState.sourceSelectedAddonFilter,
        userInteractedBeforePlayingFocus.value
    ) {
        if (playingFocusApplied.value ||
            userInteractedBeforePlayingFocus.value ||
            uiState.sourceSelectedAddonFilter != null ||
            currentStreamIndex != 0 ||
            streamKeys.isEmpty()
        ) {
            return@LaunchedEffect
        }

        val requester =
            streamFocusRequesters[streamKeys.first()] ?: return@LaunchedEffect

        repeat(3) {
            androidx.compose.runtime.withFrameNanos { }

            if (runCatching { requester.requestFocus() }.isSuccess) {
                playingFocusApplied.value = true
                return@LaunchedEffect
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(NuvioColors.BackgroundElevated)
            .onPreviewKeyEvent { event ->
                if (!playingFocusApplied.value &&
                    event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                ) {
                    userInteractedBeforePlayingFocus.value = true
                }
                false
            }
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sources_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton(
                        text = stringResource(R.string.sources_reload),
                        onClick = onReload,
                        isPrimary = false
                    )
                    DialogButton(
                        text = stringResource(R.string.sources_close),
                        onClick = onClose,
                        isPrimary = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Current content info
            Text(
                text = buildString {
                    if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                        append("S${uiState.currentSeason} E${uiState.currentEpisode}")
                        if (!uiState.currentEpisodeTitle.isNullOrBlank()) {
                            append(" • ${uiState.currentEpisodeTitle}")
                        }
                    } else {
                        append(uiState.title)
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.extendedColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                AddonFilterChips(
                    addons = uiState.sourceAvailableAddons,
                    sourceChips = uiState.sourceChips,
                    selectedAddon = uiState.sourceSelectedAddonFilter,
                    onAddonSelected = onAddonFilterSelected,
                    externalFocusRequesters = chipFocusRequesters,
                    externalOrderedNames = orderedAddonNames
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                uiState.isLoadingSourceStreams -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                uiState.sourceStreamsError != null && uiState.sourceFilteredStreams.isEmpty() -> {
                    Text(
                        text = uiState.sourceStreamsError,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                uiState.sourceFilteredStreams.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.sources_no_streams),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                else -> {
                    Column(modifier = Modifier.fillMaxHeight()) {
                        uiState.sourceStreamsError?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.Error,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                            )
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                top = 14.dp,
                                end = 8.dp,
                                bottom = 8.dp
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .onKeyEvent { event ->
                                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                                    val addons =
                                        orderedAddonNames.filter { it in uiState.sourceAvailableAddons }
                                    if (addons.isEmpty()) return@onKeyEvent false
                                    val allOptions = listOf<String?>(null) + addons
                                    val currentIdx = allOptions.indexOf(uiState.sourceSelectedAddonFilter)
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            if (currentIdx > 0) { onAddonFilterSelected(allOptions[currentIdx - 1]); true } else false
                                        }
                                        Key.DirectionRight -> {
                                            if (currentIdx < allOptions.lastIndex) { onAddonFilterSelected(allOptions[currentIdx + 1]); true } else false
                                        }
                                        else -> false
                                    }
                                }
                        ) {
                            itemsIndexed(
                                items = displayStreams,
                                key = { index, _ -> streamKeys[index] }
                            ) { index, stream ->
                                StreamItem(
                                    stream = stream,
                                    focusRequester = streamFocusRequesters.getValue(streamKeys[index]),
                                    requestInitialFocus = true,
                                    isCurrentStream = index == currentStreamIndex,
                                    onClick = { onStreamSelected(stream) },
                                    onUpKey = if (index == 0 && chipFocusRequesters.isNotEmpty()) {{
                                        val selected = uiState.sourceSelectedAddonFilter
                                        val idx = if (selected == null) 0 else orderedAddonNames.indexOf(selected) + 1
                                        if (idx >= 0 && idx < chipFocusRequesters.size) {
                                            try { chipFocusRequesters[idx].requestFocus() } catch (_: Exception) {}
                                        }
                                    }} else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun findCurrentStreamIndex(
    streams: List<Stream>,
    currentSourceStreamKey: String?,
    currentStreamInfoHash: String?,
    currentStreamFileIdx: Int?,
    currentStreamUrl: String?,
    currentStreamName: String?,
    currentStreamAddonName: String?,
    currentStreamDescription: String?
): Int {
    if (streams.isEmpty()) return -1

    val expectedInfoHash = currentStreamInfoHash?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() }
    if (expectedInfoHash != null) {
        val hashMatches = streams.indices.filter { index ->
            val stream = streams[index]
            val streamHash = (stream.infoHash ?: stream.clientResolve?.infoHash)
                ?.trim()
                ?.lowercase()
            val streamFileIdx = stream.fileIdx ?: stream.clientResolve?.fileIdx
            streamHash == expectedInfoHash &&
                (currentStreamFileIdx == null || streamFileIdx == currentStreamFileIdx)
        }
        if (hashMatches.isNotEmpty()) {
            // Provider expansion may create multiple presentation rows for the
            // same underlying torrent file. They are all the same playing
            // media identity, so keep the first configured/sorted variant
            // pinned instead of dropping the Playing marker as duplicates load.
            return hashMatches.first()
        }
    }

    // Best signal: the exact source row selected by the user. This survives
    // direct-debrid resolution even when the actual playback URL changes.
    if (!currentSourceStreamKey.isNullOrBlank()) {
        val exactKeyMatches = streams.indices.filter { index ->
            streams[index].stableKey() == currentSourceStreamKey
        }

        if (exactKeyMatches.isNotEmpty()) {
            return exactKeyMatches.first()
        }
    }

    val indexedStreams = streams.withIndex().toList()

    fun uniqueIndex(matches: List<IndexedValue<Stream>>): Int =
        if (matches.size == 1) matches.first().index else -1

    fun nameMatches(stream: Stream): Boolean {
        val expected = currentStreamName?.trim().orEmpty()
        if (expected.isEmpty()) return false

        return sequenceOf(
            stream.name,
            stream.getDisplayName(),
            stream.addonName
        ).filterNotNull().any {
            it.trim().equals(expected, ignoreCase = true)
        }
    }

    fun descriptionMatches(stream: Stream): Boolean {
        val expected = currentStreamDescription?.trim().orEmpty()
        if (expected.isEmpty()) return false

        return sequenceOf(
            stream.description,
            stream.getDisplayDescription()
        ).filterNotNull().any {
            it.trim().equals(expected, ignoreCase = true)
        }
    }

    fun addonMatches(stream: Stream): Boolean {
        val expected = currentStreamAddonName?.trim().orEmpty()
        if (expected.isEmpty()) return false

        return stream.addonName.trim().equals(expected, ignoreCase = true)
    }

    if (!currentStreamUrl.isNullOrBlank()) {
        val urlMatches = indexedStreams.filter {
            it.value.getStreamUrl() == currentStreamUrl
        }

        uniqueIndex(urlMatches).takeIf { it >= 0 }?.let { return it }

        if (urlMatches.isNotEmpty()) {
            uniqueIndex(
                urlMatches.filter {
                    addonMatches(it.value) &&
                        (nameMatches(it.value) || descriptionMatches(it.value))
                }
            ).takeIf { it >= 0 }?.let { return it }
        }
    }

    if (!currentStreamAddonName.isNullOrBlank() &&
        !currentStreamDescription.isNullOrBlank()
    ) {
        uniqueIndex(
            indexedStreams.filter {
                addonMatches(it.value) && descriptionMatches(it.value)
            }
        ).takeIf { it >= 0 }?.let { return it }
    }

    if (!currentStreamAddonName.isNullOrBlank() &&
        !currentStreamName.isNullOrBlank()
    ) {
        uniqueIndex(
            indexedStreams.filter {
                addonMatches(it.value) && nameMatches(it.value)
            }
        ).takeIf { it >= 0 }?.let { return it }
    }

    if (!currentStreamName.isNullOrBlank() &&
        !currentStreamDescription.isNullOrBlank()
    ) {
        uniqueIndex(
            indexedStreams.filter {
                nameMatches(it.value) && descriptionMatches(it.value)
            }
        ).takeIf { it >= 0 }?.let { return it }
    }

    if (!currentStreamName.isNullOrBlank()) {
        uniqueIndex(
            indexedStreams.filter { nameMatches(it.value) }
        ).takeIf { it >= 0 }?.let { return it }
    }

    if (!currentStreamDescription.isNullOrBlank()) {
        uniqueIndex(
            indexedStreams.filter { descriptionMatches(it.value) }
        ).takeIf { it >= 0 }?.let { return it }
    }

    // Better to show no Playing badge than mark the wrong source.
    return -1
}
