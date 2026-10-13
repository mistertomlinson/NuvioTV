package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerFallbackCandidateMatcherTest {

    @Test
    fun `rejects old Dig series for Peacock Dig even with season one trailer wording`() {
        val result = evaluateTvTrailerCandidate(
            requestedTitle = "Dig",
            requestedYear = "2026",
            requestedNetwork = "Peacock",
            candidateTitle = "DIG Season 1 Official Trailer (2015)"
        )

        assertTrue(result.ambiguousTitle)
        assertTrue(result.conflictingYear)
        assertFalse(result.networkMatches)
        assertFalse(result.accepted)
    }

    @Test
    fun `accepts Peacock branded trailer for ambiguous Dig`() {
        val result = evaluateTvTrailerCandidate(
            requestedTitle = "Dig",
            requestedYear = "2026",
            requestedNetwork = "Peacock",
            candidateTitle = "DIG | Season 1 Official Trailer | Peacock"
        )

        assertTrue(result.ambiguousTitle)
        assertTrue(result.networkMatches)
        assertFalse(result.conflictingYear)
        assertTrue(result.accepted)
        assertEquals(5, result.tier)
    }

    @Test
    fun `rejects Conan Must Go for TBS Conan`() {
        val result = evaluateTvTrailerCandidate(
            requestedTitle = "Conan",
            requestedYear = "2010",
            requestedNetwork = "TBS",
            candidateTitle = "Conan O'Brien Must Go | Official Trailer | Max"
        )

        assertTrue(result.ambiguousTitle)
        assertFalse(result.networkMatches)
        assertFalse(result.accepted)
    }

    @Test
    fun `accepts TBS promo for ambiguous Conan`() {
        val result = evaluateTvTrailerCandidate(
            requestedTitle = "Conan",
            requestedYear = "2010",
            requestedNetwork = "TBS",
            candidateTitle = "CONAN O'Brien - TBS Promo"
        )

        assertTrue(result.ambiguousTitle)
        assertTrue(result.networkMatches)
        assertTrue(result.accepted)
        assertEquals(4, result.tier)
    }

    @Test
    fun `ambiguous one word title fails closed when network is unavailable`() {
        val result = evaluateTvTrailerCandidate(
            requestedTitle = "Survivor",
            requestedYear = "2000",
            requestedNetwork = null,
            candidateTitle = "Survivor Season 1 Official Trailer"
        )

        assertTrue(result.ambiguousTitle)
        assertFalse(result.identityCorroborated)
        assertFalse(result.accepted)
    }

    @Test
    fun `article plus one meaningful word is treated as ambiguous`() {
        val rejected = evaluateTvTrailerCandidate(
            requestedTitle = "The Bear",
            requestedYear = "2022",
            requestedNetwork = "FX",
            candidateTitle = "The Bear Season 1 Official Trailer"
        )
        val accepted = evaluateTvTrailerCandidate(
            requestedTitle = "The Bear",
            requestedYear = "2022",
            requestedNetwork = "FX",
            candidateTitle = "The Bear Season 1 Official Trailer | FX"
        )

        assertTrue(rejected.ambiguousTitle)
        assertFalse(rejected.accepted)
        assertTrue(accepted.accepted)
    }

    @Test
    fun `distinctive TV title keeps fallback coverage without network text`() {
        val result = evaluateTvTrailerCandidate(
            requestedTitle = "Only Murders in the Building",
            requestedYear = "2021",
            requestedNetwork = "Hulu",
            candidateTitle = "Only Murders in the Building Season 1 Official Trailer"
        )

        assertFalse(result.ambiguousTitle)
        assertTrue(result.accepted)
        assertEquals(3, result.tier)
    }

    @Test
    fun `explicit contradictory year rejects otherwise valid distinctive TV candidate`() {
        val result = evaluateTvTrailerCandidate(
            requestedTitle = "Only Murders in the Building",
            requestedYear = "2021",
            requestedNetwork = "Hulu",
            candidateTitle = "Only Murders in the Building 2020 Official Trailer"
        )

        assertTrue(result.conflictingYear)
        assertFalse(result.accepted)
    }

    @Test
    fun `missing candidate year is not treated as a conflict`() {
        val result = evaluateTvTrailerCandidate(
            requestedTitle = "Only Murders in the Building",
            requestedYear = "2021",
            requestedNetwork = "Hulu",
            candidateTitle = "Only Murders in the Building Official Trailer"
        )

        assertFalse(result.conflictingYear)
        assertTrue(result.accepted)
    }

    @Test
    fun `known network outranks generic season one wording`() {
        val networkCandidate = evaluateTvTrailerCandidate(
            requestedTitle = "The Morning Show",
            requestedYear = "2019",
            requestedNetwork = "Apple TV+",
            candidateTitle = "The Morning Show Official Teaser | Apple TV+"
        )
        val genericSeasonCandidate = evaluateTvTrailerCandidate(
            requestedTitle = "The Morning Show",
            requestedYear = "2019",
            requestedNetwork = "Apple TV+",
            candidateTitle = "The Morning Show Season 1 Official Trailer"
        )

        assertTrue(networkCandidate.accepted)
        assertTrue(genericSeasonCandidate.accepted)
        assertTrue(networkCandidate.tier > genericSeasonCandidate.tier)
    }
}
