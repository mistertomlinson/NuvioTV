package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingNextUpDismissRoutingTest {

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
}
