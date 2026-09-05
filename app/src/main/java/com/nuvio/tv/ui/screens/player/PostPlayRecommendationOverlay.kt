@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.R
import kotlinx.coroutines.delay

@Composable
internal fun PostPlayRecommendationOverlay(
    recommendation: PostPlayRecommendation,
    currentTitle: String,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    playFocusRequester: FocusRequester,
    playerWindowFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onOpenDetails: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val opensDetails = recommendation.contentType.trim().lowercase() in
        setOf("series", "tv", "show", "tvshow")

    LaunchedEffect(recommendation.id) {
        delay(420L)
        runCatching { playFocusRequester.requestFocus() }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AsyncImage(
            model = recommendation.backdrop ?: recommendation.poster,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.96f),
                        0.52f to Color.Black.copy(alpha = 0.70f),
                        1f to Color.Black.copy(alpha = 0.24f)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.15f),
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.55f)
                .padding(start = 52.dp, end = 28.dp, top = 54.dp, bottom = 42.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.player_post_play_because, currentTitle),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 15.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = recommendation.title,
                color = Color.White,
                fontSize = 36.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val facts = listOfNotNull(
                recommendation.releaseInfo?.takeIf { it.isNotBlank() },
                recommendation.genres.take(3).joinToString(" • ").takeIf { it.isNotBlank() }
            ).joinToString("  •  ")
            if (facts.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(facts, color = Color.White.copy(alpha = 0.76f), fontSize = 14.sp)
            }
            recommendation.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = if (opensDetails) onOpenDetails else onPlay,
                    modifier = Modifier
                        .focusRequester(playFocusRequester)
                        .focusProperties { up = playerWindowFocusRequester },
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(
                        if (opensDetails) Icons.Default.Info else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (opensDetails) R.string.tmdb_details_title
                            else R.string.player_post_play_play
                        )
                    )
                }
                if (!opensDetails) {
                    Button(onClick = onOpenDetails) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tmdb_details_title))
                    }
                }
                if (canGoPrevious) {
                    IconButton(onClick = onPrevious) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.player_post_play_previous_recommendation)
                        )
                    }
                }
                if (canGoNext) {
                    IconButton(onClick = onNext) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.player_post_play_next_recommendation)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PostPlayPlayerWindow(
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .focusProperties { down = downFocusRequester },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        border = CardDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}
