package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktRatingMovieDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "rated_at") val ratedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingShowDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "seasons") val seasons: List<TraktRatingSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingSeasonDto(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<TraktRatingEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingEpisodeDto(
    @Json(name = "number") val number: Int,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "rated_at") val ratedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktAddRatingRequestDto(
    @Json(name = "movies") val movies: List<TraktRatingMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktRatingShowDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktAddRatingResponseDto(
    @Json(name = "added") val added: TraktRatingCountDto? = null,
    @Json(name = "not_found") val notFound: TraktRatingNotFoundDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingCountDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "shows") val shows: Int? = null,
    @Json(name = "seasons") val seasons: Int? = null,
    @Json(name = "episodes") val episodes: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingNotFoundDto(
    @Json(name = "movies") val movies: List<TraktRatingMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktRatingShowDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktRatingEpisodeDto>? = null
)
