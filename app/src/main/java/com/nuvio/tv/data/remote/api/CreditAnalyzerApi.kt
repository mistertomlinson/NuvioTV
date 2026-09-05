package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class CreditAnalyzeRequest(
    @Json(name = "media_url") val mediaUrl: String,
    @Json(name = "media_key") val mediaKey: String,
    @Json(name = "content_key") val contentKey: String?,
    @Json(name = "duration_ms") val durationMs: Long?,
    @Json(name = "size_bytes") val sizeBytes: Long?,
    val title: String?,
    @Json(name = "content_type") val contentType: String
)

@JsonClass(generateAdapter = true)
data class CreditTimeSegment(
    val kind: String,
    @Json(name = "start_ms") val startMs: Long,
    @Json(name = "end_ms") val endMs: Long,
    val confidence: Double
)

@JsonClass(generateAdapter = true)
data class CreditAnalysisResult(
    val detected: Boolean,
    @Json(name = "duration_ms") val durationMs: Long,
    @Json(name = "credits_start_ms") val creditsStartMs: Long?,
    @Json(name = "final_credits_start_ms") val finalCreditsStartMs: Long?,
    @Json(name = "post_credit_scenes") val postCreditScenes: List<CreditTimeSegment> = emptyList(),
    val confidence: Double,
    val source: String
)

@JsonClass(generateAdapter = true)
data class CreditTimingFallback(
    @Json(name = "credits_start_ms") val creditsStartMs: Long,
    @Json(name = "final_credits_start_ms") val finalCreditsStartMs: Long,
    @Json(name = "source_duration_ms") val sourceDurationMs: Long,
    @Json(name = "target_duration_ms") val targetDurationMs: Long,
    @Json(name = "runtime_difference_ms") val runtimeDifferenceMs: Long,
    val confidence: Double
)

@JsonClass(generateAdapter = true)
data class CreditAnalyzerJobResponse(
    @Json(name = "job_id") val jobId: String,
    @Json(name = "cache_key") val cacheKey: String,
    val status: String,
    val cached: Boolean = false,
    val result: CreditAnalysisResult?,
    val fallback: CreditTimingFallback? = null,
    val error: String?
)

interface CreditAnalyzerApi {
    @POST("v1/analyze")
    suspend fun analyze(
        @Header("Authorization") authorization: String,
        @Body request: CreditAnalyzeRequest
    ): Response<CreditAnalyzerJobResponse>

    @GET("v1/jobs/{jobId}")
    suspend fun getJob(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String
    ): Response<CreditAnalyzerJobResponse>
}
