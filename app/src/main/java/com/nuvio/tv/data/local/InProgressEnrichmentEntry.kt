package com.nuvio.tv.data.local

data class InProgressEnrichmentEntry(
    val contentId: String,
    val season: Int?,
    val episode: Int?,
    val episodeDescription: String?,
    val episodeThumbnail: String?,
    val episodeImdbRating: Float?,
    val genres: List<String>,
    val releaseInfo: String?,
    val logo: String?
)
