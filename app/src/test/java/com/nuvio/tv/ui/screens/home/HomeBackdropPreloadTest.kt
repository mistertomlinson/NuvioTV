package com.nuvio.tv.ui.screens.home

import android.app.Application
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.catalog.xlCatalogPreferenceKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HomeBackdropPreloadTest {
    private fun title() = MetaPreview(id = "title", type = ContentType.MOVIE, name = "Title",
        poster = "portrait", posterShape = PosterShape.POSTER, background = "blue-fanart", logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), landscapePoster = "red-landscape")
    private fun row(item: MetaPreview = title()) = CatalogRow("fixture", "Fixture", "https://fixture.invalid",
        "top", "Netflix Top", ContentType.MOVIE, items = listOf(item), hasMore = false)

    @Test fun anXlPlatformWarmsTheLandscapeThatItsFocusedCardActuallyUses() {
        val row = row()
        val mapped = buildCatalogItem(title(), row, false, 0)
        val expected = mapped.xlBackdropPreview().backdrop
        val artwork = row.firstBackdropPreloadArtwork(setOf(xlCatalogPreferenceKey(catalogRowKey(row))))
        assertEquals(HomeBackdropArtwork(expected, true), artwork)
        assertEquals("red-landscape", artwork?.url)
        assertNotEquals("blue-fanart", artwork?.url)
    }

    @Test fun anOrdinaryPlatformKeepsItsSharpFanartAndOriginalRenderSize() = runBlocking {
        val artwork = row().firstBackdropPreloadArtwork(emptySet())
        assertEquals(HomeBackdropArtwork("blue-fanart", false), artwork)
        val request = checkNotNull(homeBackdropPreloadRequest(RuntimeEnvironment.getApplication(), artwork, 1382, 777))
        assertEquals("blue-fanart", request.data)
        assertTrue(request.transformations.isEmpty())
        assertEquals(Size(Dimension.Pixels(1382), Dimension.Pixels(777)), request.sizeResolver.size())
    }

    @Test fun anXlWarmupMatchesTheRenderersExactBlurVariantEvenBeforeHeroMeasurement() = runBlocking {
        val request = checkNotNull(homeBackdropPreloadRequest(RuntimeEnvironment.getApplication(),
            HomeBackdropArtwork("landscape", true), 0, 0))
        assertEquals(Size(Dimension.Pixels(XL_BLUR_WIDTH), Dimension.Pixels(XL_BLUR_HEIGHT)), request.sizeResolver.size())
        assertEquals(Scale.FILL, request.scale)
        assertEquals(Precision.EXACT, request.precision)
        assertFalse(request.allowHardware)
        assertEquals(listOf(XlBackdropBlur.cacheKey), request.transformations.map { it.cacheKey })
    }

    @Test fun rememberedVerticalDestinationsChooseTheirOwnVariant() {
        val row = row()
        val item = buildCatalogItem(title(), row, false, 0)
        assertEquals(HomeBackdropArtwork(item.xlLandscapeImageUrl(), true), item.backdropPreloadArtwork(true))
        assertEquals(HomeBackdropArtwork("blue-fanart", false), item.backdropPreloadArtwork(false))
    }

    @Test fun aPreferenceChangeCannotReuseTheSharpWarmupAsTheBlurredWarmup() {
        val row = row()
        val ordinary = checkNotNull(row.firstBackdropPreloadArtwork(emptySet()))
        val xl = checkNotNull(row.firstBackdropPreloadArtwork(setOf(xlCatalogPreferenceKey(catalogRowKey(row)))))
        assertNotEquals(ordinary, xl)
        assertEquals(2, setOf(ordinary, xl).size)
    }

    @Test fun missingArtworkAndUnmeasuredSharpDestinationsDoNotStartRequests() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(homeBackdropPreloadRequest(context, null, 1920, 1080))
        assertNull(homeBackdropPreloadRequest(context, HomeBackdropArtwork(" ", true), 1920, 1080))
        assertNull(homeBackdropPreloadRequest(context, HomeBackdropArtwork("sharp", false), 0, 1080))
        assertNull(row(title().copy(poster = null, background = null, landscapePoster = null)).firstBackdropPreloadArtwork(emptySet()))
    }
}
