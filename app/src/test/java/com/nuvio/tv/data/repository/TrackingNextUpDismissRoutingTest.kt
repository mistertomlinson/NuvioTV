package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingNextUpDismissRoutingTest {

    @Test
    fun `rewatch dismissal never writes persistent dismissal or calls provider`() = runTest {
        val provider =
            mockk<TrackingProgressProvider>(
                relaxed = true
            )

        var persistentDismissals = 0

        val rewatchConsumed =
            runProtectedNextUpDismissal(
                isPlayerRewatch = true,
                persistNormalDismissal = {
                    persistentDismissals++
                },
                dismissThroughProvider = {
                    provider.dismissNextUp(
                        contentId =
                            "tt1234567",
                        season = 1,
                        episode = 6
                    )
                }
            )

        assertTrue(rewatchConsumed)
        assertEquals(
            0,
            persistentDismissals
        )

        coVerify(exactly = 0) {
            provider.dismissNextUp(
                contentId = any(),
                season = any(),
                episode = any()
            )
        }
    }

    @Test
    fun `normal next up still persists and calls provider exactly once`() = runTest {
        val provider =
            mockk<TrackingProgressProvider>(
                relaxed = true
            )

        var persistentDismissals = 0

        val rewatchConsumed =
            runProtectedNextUpDismissal(
                isPlayerRewatch = false,
                persistNormalDismissal = {
                    persistentDismissals++
                },
                dismissThroughProvider = {
                    provider.dismissNextUp(
                        contentId =
                            "tt1234567",
                        season = 1,
                        episode = 6
                    )
                }
            )

        assertFalse(rewatchConsumed)
        assertEquals(
            1,
            persistentDismissals
        )

        coVerify(exactly = 1) {
            provider.dismissNextUp(
                contentId =
                    "tt1234567",
                season = 1,
                episode = 6
            )
        }
    }

    @Test
    fun `active provider receives Next Up dismissal`() = runTest {
        val provider = mockk<TrackingProgressProvider>(relaxed = true)

        val handled = dismissNextUpWithProvider(
            provider = provider,
            contentId = "tt1234567",
            season = 2,
            episode = 4
        )

        assertTrue(handled)
        coVerify(exactly = 1) {
            provider.dismissNextUp(
                contentId = "tt1234567",
                season = 2,
                episode = 4
            )
        }
    }

    @Test
    fun `local source reports dismissal was not remotely handled`() = runTest {
        val handled = dismissNextUpWithProvider(
            provider = null,
            contentId = "tt1234567",
            season = 2,
            episode = 4
        )

        assertFalse(handled)
    }
    @Test
    fun `old provider projection is rejected after profile switch`() {
        val result =
            gateProviderProjectionForActiveProfile(
                value = listOf("foreign-next-up"),
                projectionProfileId = 1,
                activeProfileId = 2,
                emptyValue = emptyList()
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `unstamped provider projection is rejected during profile handoff`() {
        val result =
            gateProviderProjectionForActiveProfile(
                value = listOf("foreign-next-up"),
                projectionProfileId = null,
                activeProfileId = 2,
                emptyValue = emptyList()
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `matching provider projection is accepted`() {
        val result =
            gateProviderProjectionForActiveProfile(
                value = listOf("wife-next-up"),
                projectionProfileId = 2,
                activeProfileId = 2,
                emptyValue = emptyList()
            )

        assertEquals(
            listOf("wife-next-up"),
            result
        )
    }

}
