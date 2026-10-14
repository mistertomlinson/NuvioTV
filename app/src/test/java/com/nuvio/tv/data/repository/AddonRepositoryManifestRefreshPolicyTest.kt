package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRepositoryManifestRefreshPolicyTest {

    @Test
    fun `initial stale emission still performs global refresh`() {
        assertTrue(
            shouldRunGlobalManifestRefresh(
                previousUrls = null,
                previousRevision = null,
                urls = listOf("https://watchly.example"),
                revision = 0L,
                cacheStale = true
            )
        )
    }

    @Test
    fun `addon url change still performs global refresh`() {
        assertTrue(
            shouldRunGlobalManifestRefresh(
                previousUrls = listOf("https://seasonal.example"),
                previousRevision = 4L,
                urls = listOf(
                    "https://seasonal.example",
                    "https://watchly.example"
                ),
                revision = 4L,
                cacheStale = true
            )
        )
    }

    @Test
    fun `revision only emission never cascades into global stale refresh`() {
        val urls = listOf(
            "https://seasonal.example",
            "https://watchly.example"
        )

        assertFalse(
            shouldRunGlobalManifestRefresh(
                previousUrls = urls,
                previousRevision = 4L,
                urls = urls,
                revision = 5L,
                cacheStale = true
            )
        )
    }

    @Test
    fun `fresh cache never performs global refresh`() {
        assertFalse(
            shouldRunGlobalManifestRefresh(
                previousUrls = listOf("https://watchly.example"),
                previousRevision = 4L,
                urls = listOf("https://watchly.example"),
                revision = 4L,
                cacheStale = false
            )
        )
    }
}
