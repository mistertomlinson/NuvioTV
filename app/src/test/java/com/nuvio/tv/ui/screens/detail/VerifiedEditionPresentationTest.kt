package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.domain.model.StreamingOriginalsEditionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedEditionPresentationTest {
    private val canonicalId = "tt5463162"
    private val editionId = "soedition:hbo:tt5463162:verified-super-id"
    private val route = StreamingOriginalsEditionId.parse(editionId)

    private fun baseMeta() = MetaDto(
        id = canonicalId,
        type = "movie",
        name = "Deadpool 2",
        poster = "original-poster",
        background = "clean-backdrop",
        runtime = "120 min",
        imdbId = canonicalId,
        description = "Original cut"
    ).toDomain()

    private fun firstPartyEdition() = MetaDto(
        id = canonicalId,
        type = "movie",
        name = "Deadpool 2: Super Duper Cut",
        poster = "verified-poster",
        logo = "verified-logo",
        runtime = "135 min",
        imdbId = canonicalId,
        description = "Verified extended cut"
    ).toDomain()

    @Test
    fun visualMetadataIsEditionSpecificButPlaybackIdentityIsCanonical() {
        val base = baseMeta()
        val merged = applyVerifiedEditionPresentation(base, firstPartyEdition(), canonicalId, route)
        assertEquals(canonicalId, merged.id)
        assertEquals(canonicalId, merged.imdbId)
        assertEquals(base.videos, merged.videos)
        assertEquals(base.links, merged.links)
        assertEquals("Deadpool 2: Super Duper Cut", merged.name)
        assertEquals("verified-poster", merged.poster)
        assertEquals("verified-logo", merged.logo)
        assertEquals("135 min", merged.runtime)
        assertEquals("clean-backdrop", merged.background)
    }

    @Test
    fun unverifiedOrMismatchedEditionsNeverReplaceMetadata() {
        val base = baseMeta()
        val wrong = firstPartyEdition().copy(id = "tt0000001")
        assertEquals(base, applyVerifiedEditionPresentation(base, wrong, canonicalId, route))
        assertEquals(base, applyVerifiedEditionPresentation(base, null, canonicalId, route))
        assertEquals(base, applyVerifiedEditionPresentation(base, firstPartyEdition(), canonicalId, null))
        assertEquals(base, applyVerifiedEditionPresentation(base, firstPartyEdition(), "tt0000001", route))
    }
}
