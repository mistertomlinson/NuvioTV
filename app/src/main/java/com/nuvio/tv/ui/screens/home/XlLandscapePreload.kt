package com.nuvio.tv.ui.screens.home

import android.content.Context
import coil.request.ImageRequest

internal fun ModernCarouselItem.xlLandscapeImageUrl(): String? =
    firstNonBlank(metaPreview?.landscapePoster, heroPreview.backdrop, heroPreview.imageUrl, heroPreview.poster)

/** Blur the artwork actually displayed in the card, keeping its palette and metadata. */
internal fun ModernCarouselItem.xlBackdropPreview(): HeroPreview =
    heroPreview.copy(backdrop = xlLandscapeImageUrl())

/** One lookahead, using the exact same URL, size, Crop scale and cache policy as the featured card. */
internal fun xlNextLandscapeRequest(
    context: Context,
    items: List<ModernCarouselItem>,
    selected: Int,
    widthPx: Int,
    heightPx: Int
): ImageRequest? = items.getOrNull(selected + 1)?.xlLandscapeImageUrl()?.let {
    homePosterImageRequest(context, it, widthPx, heightPx)
}
