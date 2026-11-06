package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException

/** Match AsyncImage's Crop decode, including when executing without a painter. */
internal fun homePosterImageRequest(
    context: Context,
    imageUrl: String,
    widthPx: Int,
    heightPx: Int
): ImageRequest = ImageRequest.Builder(context)
    .data(imageUrl)
    .crossfade(false)
    .size(width = widthPx, height = heightPx)
    .scale(Scale.FILL)
    .precision(Precision.INEXACT)
    .placeholderMemoryCacheKey(imageUrl)
    .build()

/** Remember residency, not just a past success, without pinning poster bitmaps. */
internal class HomeExposureImageCache(
    private val context: Context,
    private val imageLoader: ImageLoader
) {
    private data class Entry(val key: MemoryCache.Key, val bitmap: WeakReference<Bitmap>)

    private val entries = mutableMapOf<HomeExposureWindow, List<Entry>>()

    fun retainWindows(windows: List<HomeExposureWindow>) {
        entries.keys.retainAll(windows.toSet())
    }

    fun isWarm(window: HomeExposureWindow): Boolean {
        val expected = entries[window] ?: return false
        if (expected.size != window.imageUrls.size) return false
        return expected.all { entry ->
            val bitmap = entry.bitmap.get() ?: return@all false
            imageLoader.memoryCache?.get(entry.key)?.bitmap === bitmap
        }
    }

    suspend fun prewarm(window: HomeExposureWindow) {
        val cached = mutableListOf<Entry>()
        for (imageUrl in window.imageUrls) {
            try {
                val result = imageLoader.execute(
                    homePosterImageRequest(
                        context = context,
                        imageUrl = imageUrl,
                        widthPx = window.requestWidthPx,
                        heightPx = window.requestHeightPx
                    )
                ) as? SuccessResult ?: continue
                val key = result.memoryCacheKey ?: continue
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: continue
                if (imageLoader.memoryCache?.get(key)?.bitmap === bitmap) {
                    cached += Entry(key, WeakReference(bitmap))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A subsequent idle snapshot can retry this window.
            }
        }
        entries[window] = cached
    }
}
