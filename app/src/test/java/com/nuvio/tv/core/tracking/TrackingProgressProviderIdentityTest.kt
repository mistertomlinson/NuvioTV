package com.nuvio.tv.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingProgressProviderIdentityTest {

    @Test
    fun `alternate local parent resolves to provider next up identity`() {
        val siblings = mapOf(
            "tt1234567" to setOf("tmdb:7654", "tvdb:4567", "simkl:99"),
            "tmdb:7654" to setOf("tt1234567", "tvdb:4567", "simkl:99"),
            "tvdb:4567" to setOf("tt1234567", "tmdb:7654", "simkl:99"),
            "simkl:99" to setOf("tt1234567", "tmdb:7654", "tvdb:4567")
        )

        val resolved = resolveProviderNextUpParentContentId(
            parentContentId = "tmdb:7654",
            showIdSiblings = siblings,
            providerSeedContentIds = listOf("tt1234567")
        )

        assertEquals("tt1234567", resolved)
    }

    @Test
    fun `raw and prefixed imdb identities collapse to provider seed`() {
        val siblings = mapOf(
            "tt1234567" to setOf("tmdb:7654"),
            "tmdb:7654" to setOf("tt1234567")
        )

        val resolved = resolveProviderNextUpParentContentId(
            parentContentId = "imdb:tt1234567",
            showIdSiblings = siblings,
            providerSeedContentIds = listOf("tt1234567")
        )

        assertEquals("tt1234567", resolved)
    }

    @Test
    fun `unrelated provider seed leaves parent identity unchanged`() {
        val siblings = mapOf(
            "tt1234567" to setOf("tmdb:7654"),
            "tmdb:7654" to setOf("tt1234567")
        )

        val resolved = resolveProviderNextUpParentContentId(
            parentContentId = "tmdb:7654",
            showIdSiblings = siblings,
            providerSeedContentIds = listOf("tt9999999")
        )

        assertEquals("tmdb:7654", resolved)
    }
}
