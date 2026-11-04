package com.nuvio.tv.domain.model

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingOriginalsEditionIdTest {
    private val canonical = "tt5463162"
    private val theatrical = "soedition:hbo:tt5463162:theatrical-uuid"
    private val extended = "soedition:hbo:tt5463162:super-uuid"

    @Test
    fun verifiedEditsNavigateSeparatelyWithoutChangingCanonicalId() {
        val base = MetaPreviewDto(
            id = canonical,
            type = "movie",
            name = "Deadpool 2",
            editionDetailId = theatrical
        ).toDomain()
        val cut = MetaPreviewDto(
            id = canonical,
            type = "movie",
            name = "Deadpool 2: Super Duper Cut",
            editionDetailId = extended
        ).toDomain()

        assertEquals(canonical, base.id)
        assertEquals(canonical, cut.id)
        assertEquals(theatrical, base.detailNavigationId)
        assertEquals(extended, cut.detailNavigationId)
        assertEquals(canonical, StreamingOriginalsEditionId.parse(extended)?.canonicalId)
    }

    @Test
    fun sidecarCannotChangeItsCatalogCanonicalIdentity() {
        assertEquals(
            canonical,
            StreamingOriginalsEditionId.detailsId("tt5463162", "soedition:hbo:tt0000001:super-uuid")
        )
        assertEquals(canonical, StreamingOriginalsEditionId.detailsId(canonical, "soedition:not-enough"))
        assertEquals(canonical, StreamingOriginalsEditionId.detailsId(canonical, null))
        assertNull(StreamingOriginalsEditionId.parse("soedition:hbo:broken:super-uuid"))
    }

    @Test
    fun tmdbFallbackCanonicalIdIsDecodedForMetadataLookup() {
        val edition = "soedition:disney:tmdb%3A354912:verified-first-party-id"
        assertEquals("tmdb:354912", StreamingOriginalsEditionId.parse(edition)?.canonicalId)
        assertEquals(edition, StreamingOriginalsEditionId.detailsId("tmdb:354912", edition))
    }
}
