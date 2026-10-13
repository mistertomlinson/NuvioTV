package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.PosterStatusIconStyle

@Composable
fun PosterStatusMarker(
    isWatched: Boolean,
    isReminderSet: Boolean,
    style: PosterStatusIconStyle,
    modifier: Modifier = Modifier
) {
    if (!isWatched && !isReminderSet) {
        return
    }

    if (style == PosterStatusIconStyle.MODERN) {
        Image(
            painter = painterResource(
                if (isWatched) {
                    R.drawable.poster_status_watched
                } else {
                    R.drawable.poster_status_reminder
                }
            ),
            contentDescription = stringResource(
                if (isWatched) {
                    R.string.episodes_cd_watched
                } else {
                    R.string.hero_reminder_set
                }
            ),
            contentScale = ContentScale.Fit,
            modifier = modifier
                .padding(
                    end = 4.dp,
                    top = 4.dp
                )
                .size(29.dp)
        )

        return
    }

    if (isWatched) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription =
                stringResource(R.string.episodes_cd_watched),
            tint = Color.White,
            modifier = modifier
                .padding(
                    end = 8.dp,
                    top = 8.dp
                )
                .size(21.dp)
                .drawBehind {
                    drawCircle(
                        color = Color.Black,
                        radius =
                            size.minDimension / 2f +
                                1.5f
                    )
                }
        )

        return
    }

    Box(
        modifier = modifier
            .padding(
                end = 8.dp,
                top = 8.dp
            )
            .size(21.dp)
            .drawBehind {
                drawCircle(
                    color = Color.Black,
                    radius =
                        size.minDimension / 2f +
                            1.5f
                )

                drawCircle(
                    color = Color.White,
                    radius =
                        size.minDimension *
                            (10f / 24f)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription =
                stringResource(R.string.hero_reminder_set),
            tint = Color.Black,
            modifier = Modifier.size(13.dp)
        )
    }
}
