package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoUtilsTest {
    private val today = LocalDate.of(2026, 10, 13)

    @Test
    fun `resolved recent movie with no digital date is hidden`() {
        val item = movie(released = "2026-09-01")

        assertTrue(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `resolved recent movie with past digital date is visible`() {
        val item = movie(
            released = "2026-09-01",
            digitalReleaseInfo = "2026-10-01"
        )

        assertFalse(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `resolved movie with future digital date is hidden`() {
        val item = movie(
            released = "2026-09-01",
            digitalReleaseInfo = "2026-11-01"
        )

        assertTrue(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `unresolved recent movie remains provisionally visible after generic release`() {
        val item = movie(released = "2026-09-01")

        assertFalse(item.isUnreleased(today, movieHomeAvailabilityResolved = false))
    }

    @Test
    fun `unresolved future movie is still hidden`() {
        val item = movie(released = "2026-11-01")

        assertTrue(item.isUnreleased(today, movieHomeAvailabilityResolved = false))
    }

    @Test
    fun `resolved legacy movie may use generic release fallback`() {
        val item = movie(released = "2025-09-01")

        assertFalse(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `resolved movie inside one year boundary stays hidden without home release`() {
        val item = movie(released = "2025-10-14")

        assertTrue(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `movie exactly one year old is visible through legacy fallback`() {
        val item = movie(released = "2025-10-13")

        assertFalse(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `malformed digital date cannot override resolved recent movie policy`() {
        val item = movie(
            released = "2026-09-01",
            digitalReleaseInfo = "not-a-date"
        )

        assertTrue(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `tv keeps normal future air date behavior`() {
        val item = series(released = "2026-11-01")

        assertTrue(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `tv keeps normal past air date behavior`() {
        val item = series(released = "2026-09-01")

        assertFalse(item.isUnreleased(today, movieHomeAvailabilityResolved = true))
    }

    @Test
    fun `recent released movie needs targeted home availability verification`() {
        val item = movie(released = "2026-09-01")

        assertTrue(item.needsHomeAvailabilityVerification(today))
    }

    @Test
    fun `future movie does not need targeted home availability verification`() {
        val item = movie(released = "2026-11-01")

        assertFalse(item.needsHomeAvailabilityVerification(today))
    }

    @Test
    fun `legacy movie does not need targeted home availability verification`() {
        val item = movie(released = "2025-09-01")

        assertFalse(item.needsHomeAvailabilityVerification(today))
    }

    @Test
    fun `tv never needs movie home availability verification`() {
        val item = series(released = "2026-09-01")

        assertFalse(item.needsHomeAvailabilityVerification(today))
    }

    private fun movie(
        released: String? = null,
        releaseInfo: String? = null,
        digitalReleaseInfo: String? = null
    ) = preview(
        type = ContentType.MOVIE,
        released = released,
        releaseInfo = releaseInfo,
        digitalReleaseInfo = digitalReleaseInfo
    )

    private fun series(
        released: String? = null,
        releaseInfo: String? = null
    ) = preview(
        type = ContentType.SERIES,
        released = released,
        releaseInfo = releaseInfo,
        digitalReleaseInfo = null
    )

    private fun preview(
        type: ContentType,
        released: String?,
        releaseInfo: String?,
        digitalReleaseInfo: String?
    ) = MetaPreview(
        id = "test:${type.name.lowercase()}",
        type = type,
        name = "Test",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseInfo,
        digitalReleaseInfo = digitalReleaseInfo,
        imdbRating = null,
        genres = emptyList(),
        released = released
    )
}
