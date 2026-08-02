package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Singleton
class SimklMutationService internal constructor(
    private val client: SimklApiClient,
    private val onMutationCommitted: suspend (SimklMutationReceipt) -> Unit = {}
) {
    @Inject
    constructor(client: SimklApiClient, syncRepository: SimklSyncRepository) : this(
        client,
        { receipt ->
            syncRepository.commitMutation(receipt)
            if (receipt.requiresReconciliation) {
                syncRepository.refreshAsync(TrackingRefreshIntent.INVALIDATED)
            }
        }
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    suspend fun moveToList(
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus
    ): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/add-to-list",
                body = buildSimklListMutationBody(candidates, destination, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toListMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    suspend fun removeFromList(items: Collection<TrackingMediaReference>): TrackingMutationResult =
        removeFromHistory(items)

    suspend fun addToHistory(items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val candidates = items.toList().also { historyItems ->
            require(historyItems.all { item -> item.media.hasResolvableIdentity }) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                body = buildSimklHistoryMutationBody(candidates, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toHistoryMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    suspend fun removeFromHistory(items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history/remove",
                body = buildSimklHistoryRemovalBody(candidates, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toHistoryRemovalReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    internal suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): SimklScrobbleResult {
        require(event.media.hasResolvableIdentity) { "Simkl scrobble requires a media ID or title" }
        require(event.media.kind == TrackingMediaKind.MOVIE || event.media.episode != null) {
            "Simkl series scrobble requires an episode"
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl mutation request action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}"
        )
        val response = try {
            client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/scrobble/${action.wireValue}",
                    body = buildSimklScrobbleBody(event, json),
                    retryPolicy = SimklRetryPolicy.NEVER,
                    scrobbleStopConflictIsSuccess = action == TrackingScrobbleAction.STOP
                )
            )
        } catch (error: Throwable) {
            Log.e(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl mutation failed action=${action.wireValue} " +
                    "error=${error.javaClass.simpleName}:${error.message} ${event.scrobbleDiagnosticSummary()}",
                error
            )
            throw error
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl mutation response action=${action.wireValue} status=${response.status} " +
                "softSuccess=${response.isSoftSuccess} ${event.scrobbleDiagnosticSummary()}"
        )
        return response.toSimklScrobbleResult(action, event, json)
    }

    suspend fun rate(
        media: TrackingMediaReference,
        rating: Int
    ): Boolean {
        require(rating in 1..10) { "Simkl rating must be between 1 and 10" }
        require(media.hasResolvableIdentity) {
            "Simkl rating requires a media ID or title"
        }

        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/ratings",
                body = buildSimklRatingBody(media, rating),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )

        val wasApplied = runCatching {
            val root = json.parseToJsonElement(response.body).jsonObject
            root["added"]
                ?.jsonObject
                ?.get("statuses")
                ?.jsonArray
                ?.isNotEmpty() == true
        }.getOrDefault(false)

        check(wasApplied) {
            "Simkl accepted the request but did not apply the rating"
        }
        return true
    }

    private fun buildSimklRatingBody(
        media: TrackingMediaReference,
        rating: Int
    ): String {
        val bucket = when (media.kind) {
            TrackingMediaKind.MOVIE -> "movies"
            TrackingMediaKind.SHOW -> "shows"
            TrackingMediaKind.ANIME -> "anime"
        }

        val item = buildJsonObject {
            put("rating", JsonPrimitive(rating))
            media.title?.takeIf(String::isNotBlank)?.let {
                put("title", JsonPrimitive(it))
            }
            media.year?.let {
                put("year", JsonPrimitive(it))
            }
            put(
                "ids",
                buildJsonObject {
                    media.ids.simkl?.let { put("simkl", JsonPrimitive(it)) }
                    media.ids.imdb?.takeIf(String::isNotBlank)?.let {
                        put("imdb", JsonPrimitive(it))
                    }
                    media.ids.tmdb?.let { put("tmdb", JsonPrimitive(it)) }
                    media.ids.tvdb?.takeIf(String::isNotBlank)?.let {
                        put("tvdb", JsonPrimitive(it))
                    }
                    media.ids.mal?.let { put("mal", JsonPrimitive(it)) }
                    media.ids.anidb?.let { put("anidb", JsonPrimitive(it)) }
                    media.ids.anilist?.let { put("anilist", JsonPrimitive(it)) }
                    media.ids.kitsu?.let { put("kitsu", JsonPrimitive(it)) }
                }
            )
        }

        return buildJsonObject {
            put(
                bucket,
                buildJsonArray {
                    add(item)
                }
            )
        }.toString()
    }

    private fun Collection<TrackingMediaReference>.validated(): List<TrackingMediaReference> =
        toList().also { candidates ->
            require(candidates.all(TrackingMediaReference::hasResolvableIdentity)) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
}
