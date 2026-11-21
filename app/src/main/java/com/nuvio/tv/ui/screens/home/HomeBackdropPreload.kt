package com.nuvio.tv.ui.screens.home

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.catalog.xlCatalogPreferenceKey

/** A cache destination includes the image variant, not just its URL. */
internal data class HomeBackdropArtwork(val url: String?, val blurred: Boolean)

internal fun MetaPreview.backdropPreloadArtwork(xl: Boolean): HomeBackdropArtwork? =
    (if (xl) firstNonBlank(landscapePoster, backdropUrl, poster)
     else firstNonBlank(backdropUrl, landscapePoster, poster))
        ?.let { HomeBackdropArtwork(it, xl) }

internal fun ModernCarouselItem.backdropPreloadArtwork(xl: Boolean): HomeBackdropArtwork? =
    (if (xl) xlLandscapeImageUrl() else firstNonBlank(heroPreview.backdrop, heroPreview.imageUrl))
        ?.let { HomeBackdropArtwork(it, xl) }

internal fun CatalogRow.firstBackdropPreloadArtwork(xlCatalogKeys: Set<String>): HomeBackdropArtwork? =
    items.firstOrNull()?.backdropPreloadArtwork(xlCatalogPreferenceKey(catalogRowKey(this)) in xlCatalogKeys)

/** Match the renderer's bounded blur/crop/cache key; sharp requests keep their render size. */
internal fun homeBackdropPreloadRequest(context: Context, artwork: HomeBackdropArtwork?,
    widthPx: Int, heightPx: Int): ImageRequest? {
    val url = artwork?.url?.takeIf { it.isNotBlank() } ?: return null
    if (artwork.blurred) return xlBlurredBackdropRequest(context, url)
    if (widthPx <= 0 || heightPx <= 0) return null
    return ImageRequest.Builder(context).data(url).crossfade(false)
        .size(widthPx, heightPx).memoryCachePolicy(CachePolicy.ENABLED).build()
}
