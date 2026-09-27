package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDetailsLookupPolicyTest {

    @Test
    fun `unresolved tmdb catalog id uses all-addon lookup`() {
        assertTrue(
            isUnresolvedTmdbCatalogLookup(
                itemId = "tmdb:87012",
                metaLookupId = "tmdb:87012"
            )
        )
    }

    @Test
    fun `successfully resolved tmdb catalog id keeps existing preferred path`() {
        assertFalse(
            isUnresolvedTmdbCatalogLookup(
                itemId = "tmdb:87012",
                metaLookupId = "tt1877368"
            )
        )
    }

    @Test
    fun `imdb catalog id keeps existing preferred path`() {
        assertFalse(
            isUnresolvedTmdbCatalogLookup(
                itemId = "tt1877368",
                metaLookupId = "tt1877368"
            )
        )
    }

    @Test
    fun `numeric catalog id keeps existing preferred path`() {
        assertFalse(
            isUnresolvedTmdbCatalogLookup(
                itemId = "87012",
                metaLookupId = "87012"
            )
        )
    }
}
