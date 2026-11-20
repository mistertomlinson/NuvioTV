package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.*
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class XlArtworkFrame(val rowKey: String, val itemKey: String, val backdropUrl: String?)

/** The row and background commit a prepared destination together, without a timer. */
@Stable
internal class XlArtworkPresentation {
    val renderers = mutableStateMapOf<String, Boolean>()
    var frame by mutableStateOf<XlArtworkFrame?>(null)
    fun ready(rowKey: String, itemKey: String, backdropUrl: String?): Boolean =
        renderers[rowKey] != true || frame == XlArtworkFrame(rowKey, itemKey, backdropUrl)
}

internal suspend fun prepareXlArtworkPair(loader: ImageLoader, requests: List<ImageRequest>) {
    // Cancel obsolete destinations and bound failures; input/focus never waits
    // for this coroutine. A missing image still follows the card's placeholder.
    withTimeoutOrNull(2_000) {
        coroutineScope {
            requests.map { request -> async(Dispatchers.IO) {
                try { loader.execute(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { }
            } }.awaitAll()
        }
    }
}
