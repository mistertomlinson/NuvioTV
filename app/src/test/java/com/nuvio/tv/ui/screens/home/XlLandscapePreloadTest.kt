package com.nuvio.tv.ui.screens.home

import android.app.Application
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class XlLandscapePreloadTest {
    private fun item(name: String, backdrop: String?, poster: String?) = ModernCarouselItem(
        name, name, null, "portrait-$name",
        HeroPreview(name, null, null, "Movie", yearText = null, genres = emptyList(),
            poster = poster, backdrop = backdrop, imageUrl = "fallback-$name"),
        ModernPayload.Catalog(name, name, "movie", "", name, null, "movie")
    )

    @Test fun lookaheadUsesOnlyTheItemToTheRightAndMatchesTheFeaturedCropDecode() = runBlocking {
        val items = listOf(item("left", "left-landscape", "left-poster"),
            item("selected", "selected-landscape", null), item("right", "right-landscape", "right-poster"),
            item("farther", "farther-landscape", null))
        val request = checkNotNull(xlNextLandscapeRequest(RuntimeEnvironment.getApplication(), items, 1, 868, 488))
        assertEquals("right-landscape", request.data)
        assertEquals(items[2].xlLandscapeImageUrl(), request.data)
        assertEquals(Size(Dimension.Pixels(868), Dimension.Pixels(488)), request.sizeResolver.size())
        assertEquals(Scale.FILL, request.scale)
        assertEquals(Precision.INEXACT, request.precision)
        assertEquals("right-landscape", request.placeholderMemoryCacheKey?.key)
    }

    @Test fun theEndOfTheRowDoesNotRequestLeftOrWrapAround() {
        val items = listOf(item("first", "first-landscape", null), item("last", "last-landscape", null))
        assertNull(xlNextLandscapeRequest(RuntimeEnvironment.getApplication(), items, 1, 434, 244))
        assertNull(xlNextLandscapeRequest(RuntimeEnvironment.getApplication(), emptyList(), 0, 434, 244))
    }

    @Test fun xlBlurUsesTheLandscapeCardsArtworkEvenWhenTheOrdinaryBackdropIsDifferent() = runBlocking {
        val title = item("right", "blue-fanart", "portrait").copy(metaPreview = MetaPreview(
            id = "right", type = ContentType.MOVIE, name = "Right", poster = "portrait",
            posterShape = PosterShape.POSTER, background = "blue-fanart", logo = null,
            description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
            landscapePoster = "red-landscape"))
        val row = HeroCarouselRow("xl", "XL", 0, listOf(title), isXl = true)
        val preview = checkNotNull(row.sceneSelection(0, 0)).preview
        val card = checkNotNull(xlNextLandscapeRequest(RuntimeEnvironment.getApplication(),
            listOf(item("left", "other", null), title), 0, 868, 488))
        val blur = xlBlurredBackdropRequest(RuntimeEnvironment.getApplication(), preview.backdrop)
        assertEquals("red-landscape", card.data)
        assertEquals("The field and visible card must share the same source colors", card.data, blur.data)
        assertEquals(title.heroPreview.copy(backdrop = "red-landscape"), preview)
        assertEquals("blue-fanart", checkNotNull(row.copy(isXl = false).sceneSelection(0, 0)).preview.backdrop)
        assertEquals("The ordinary image source remains unchanged", "blue-fanart", title.heroPreview.backdrop)
    }
}
