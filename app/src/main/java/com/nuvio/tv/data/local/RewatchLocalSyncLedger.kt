package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.nuvio.tv.domain.rewatch.RewatchRemoteCheckpoint
import com.nuvio.tv.domain.rewatch.RewatchRemoteLookup
import com.nuvio.tv.domain.rewatch.RewatchSession

/**
 * Part of the SAME profile DataStore transaction as the replay cursor.
 * Unlike the transport outbox, this is the proof that no local player save
 * has occurred since a successfully verified synchronization.
 *
 * No access token, watched history or PIN is ever stored here.
 */
internal data class RewatchLocalCleanProof(
    @field:SerializedName("simklAccountId")
    val simklAccountId: String,
    @field:SerializedName("deviceId")
    val deviceId: String,
    @field:SerializedName("revision")
    val revision: Long,
    @field:SerializedName("generation")
    val generation: Long,
    @field:SerializedName("cursor")
    val cursor: RewatchSession
)

internal data class RewatchLocalSyncLedger(
    @field:SerializedName("schemaVersion")
    val schemaVersion: Int = 1,
    @field:SerializedName("generation")
    val generation: Long = 0L,
    @field:SerializedName("cleanBySeries")
    val cleanBySeries: Map<String, RewatchLocalCleanProof> = emptyMap()
)

private fun key(series: String) = series.trim().lowercase()

/**
 * R8 may erase the generic field signature for cleanBySeries in a sideload
 * APK. Gson then returns Map<String, LinkedTreeMap> despite the Kotlin type.
 * Recover only recognized entries through a concrete Gson class token, then
 * subject every entry to the existing strict proof validation. Never clear
 * corrupt or unknown persisted state.
 */
internal fun restoreRewatchCleanProofMap(
    stored: Map<*, *>,
    gson: Gson = Gson()
): Map<String, RewatchLocalCleanProof> {
    val restored = LinkedHashMap<String, RewatchLocalCleanProof>()
    for ((storedKey, value) in stored) {
        val series = storedKey as? String
            ?: throw IllegalArgumentException("Invalid local replay clean proof key")
        val proof = when (value) {
            is RewatchLocalCleanProof -> value
            is Map<*, *> -> gson.fromJson(
                gson.toJsonTree(value), RewatchLocalCleanProof::class.java
            )
            else -> null
        } ?: throw IllegalArgumentException("Invalid local replay clean proof value")
        restored[series] = proof
    }
    return restored
}

/**
 * A previous sideload build may have persisted the three ledger properties
 * using obfuscated field names. Only reconstruct that legacy shape when the
 * raw JSON has EXACTLY two integral scalars (version and generation) and one
 * object (clean proofs), with no other content. Anything ambiguous fails
 * closed rather than dropping a generation or fabricating a clean baseline.
 *
 * This is a parsing compatibility shim, NOT a sync reset. New writes use
 * explicit @SerializedName fields and ProGuard keep rules.
 */
internal fun normalizeLegacyRewatchLedgerJson(raw: String): JsonObject {
    val json = JsonParser.parseString(raw)
    require(json.isJsonObject) { "Invalid local replay sync ledger JSON" }
    val root = json.asJsonObject
    if (root.has("schemaVersion") && root.has("generation") &&
        root.has("cleanBySeries")) return root

    require(root.size() == 3) { "Unrecognized local replay ledger fields" }
    val entries = root.entrySet().toList()
    val objectFields = entries.filter { it.value.isJsonObject }
    val numberFields = entries.filter { field ->
        field.value.isJsonPrimitive && field.value.asJsonPrimitive.isNumber
    }
    require(objectFields.size == 1 && numberFields.size == 2) {
        "Unrecognized local replay ledger types"
    }
    val integers = numberFields.map { field ->
        val str = field.value.asString
        require(Regex("(0|[1-9][0-9]*)").matches(str)) {
            "Non-integral legacy ledger number"
        }
        str.toLongOrNull() ?: throw IllegalArgumentException(
            "Legacy ledger number overflow"
        )
    }
    val versionIndexes = integers.withIndex().filter { it.value == 1L }
    require(versionIndexes.isNotEmpty()) { "Unknown legacy ledger version" }
    val generation = if (versionIndexes.size == 2) {
        1L
    } else {
        integers[1 - versionIndexes.single().index]
    }
    require(generation < Long.MAX_VALUE) { "Legacy generation overflow" }
    return JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("generation", generation)
        add("cleanBySeries", objectFields.single().value.deepCopy())
    }
}

internal fun sameRewatchCursor(a: RewatchSession, b: RewatchSession): Boolean =
    a.ownerKey == b.ownerKey &&
        key(a.canonicalSeriesId) == key(b.canonicalSeriesId) &&
        a.sessionId == b.sessionId &&
        a.episode == b.episode &&
        a.positionMs == b.positionMs &&
        a.durationMs == b.durationMs &&
        a.lastCompletedEpisode == b.lastCompletedEpisode &&
        a.phase == b.phase

internal fun decodeRewatchLocalSyncLedger(
    raw: String,
    profileId: Int,
    gson: Gson = Gson()
): RewatchLocalSyncLedger {
    require(profileId > 0)
    // Deserialize the non-generic root by its concrete class. R8 may erase
    // the generic signature on the nested cleanBySeries map; Gson then emits
    // LinkedTreeMap instead of RewatchLocalCleanProof for its values. Convert
    // each entry using a concrete class token BEFORE accessing typed fields.
    // Keep existing serialized field names intact for installed user data.
    val normalized = normalizeLegacyRewatchLedgerJson(raw)
    val decoded = requireNotNull(
        gson.fromJson(normalized, RewatchLocalSyncLedger::class.java)
    ) { "Local replay sync ledger is null" }
    val rawProofs: Map<*, *> = requireNotNull(decoded.cleanBySeries) {
        "Local replay sync ledger has no clean proof map"
    }
    val ledger = decoded.copy(
        cleanBySeries = restoreRewatchCleanProofMap(rawProofs, gson)
    )
    require(ledger.schemaVersion == 1 &&
        ledger.generation >= 0L && ledger.generation < Long.MAX_VALUE
    ) { "Invalid local replay sync ledger" }
    require(ledger.cleanBySeries.all { (series, proof) ->
        series.isNotBlank() && series.length <= 160 && series == key(series) &&
            proof.simklAccountId.matches(Regex("[1-9][0-9]{0,15}")) &&
            proof.deviceId.isNotBlank() && proof.deviceId.length <= 160 &&
            proof.revision > 0L && proof.revision < Long.MAX_VALUE &&
            proof.generation <= ledger.generation && proof.generation >= 0L &&
            proof.cursor.ownerKey == "local-profile:$profileId" &&
            key(proof.cursor.canonicalSeriesId) == series &&
            proof.cursor.sessionId.isNotBlank() &&
            proof.cursor.episode.season > 0 && proof.cursor.episode.episode > 0 &&
            proof.cursor.positionMs >= 0L && proof.cursor.durationMs >= 0L
    }) { "Invalid local replay clean proof" }
    return ledger
}

/** Called as part of every accepted local DataStore replay update. */
internal fun onLocalRewatchCommitted(
    old: RewatchLocalSyncLedger,
    replay: RewatchSession
): RewatchLocalSyncLedger {
    require(old.generation < Long.MAX_VALUE - 1)
    return old.copy(
        generation = old.generation + 1L,
        cleanBySeries = old.cleanBySeries - key(replay.canonicalSeriesId)
    )
}

/** Pure proof calculation. Caller saves it inside the replay DataStore edit. */
internal fun certifyRewatchLocalClean(
    sessions: List<RewatchSession>,
    old: RewatchLocalSyncLedger,
    profileId: Int,
    simklAccountId: String,
    deviceId: String,
    localSnapshot: RewatchSession,
    expectedGeneration: Long,
    remoteLookup: RewatchRemoteLookup,
    hasPendingOrUnpublishedChanges: Boolean
): RewatchLocalSyncLedger? {
    if (profileId <= 0 || old.generation != expectedGeneration ||
        hasPendingOrUnpublishedChanges ||
        localSnapshot.ownerKey != "local-profile:$profileId" ||
        simklAccountId.isBlank() || deviceId.isBlank()) return null
    val verified = remoteLookup as? RewatchRemoteLookup.Verified ?: return null
    val checkpoint = verified.checkpoint as? RewatchRemoteCheckpoint.Synced ?: return null
    val remote = verified.remoteSession ?: return null
    val matching = sessions.filter { key(it.canonicalSeriesId) ==
        key(localSnapshot.canonicalSeriesId) }
    if (matching.size != 1 || matching.single() != localSnapshot ||
        !sameRewatchCursor(remote, localSnapshot) ||
        checkpoint.sessionId != localSnapshot.sessionId) return null
    val oldProof = old.cleanBySeries[key(localSnapshot.canonicalSeriesId)]
    if (oldProof != null &&
        (checkpoint.revision < oldProof.revision ||
            oldProof.simklAccountId != simklAccountId ||
            oldProof.deviceId != deviceId)) return null
    return old.copy(cleanBySeries = old.cleanBySeries + (
        key(localSnapshot.canonicalSeriesId) to RewatchLocalCleanProof(
            simklAccountId, deviceId, checkpoint.revision,
            old.generation, localSnapshot
        )
    ))
}

/**
 * All inputs are checked again under one DataStore edit. A stale network
 * response is never allowed to overwrite a local player write, including
 * when the cursor moved away and later returned to identical values (ABA).
 */
internal fun mergeRewatchRemoteWithLedger(
    sessions: List<RewatchSession>,
    ledger: RewatchLocalSyncLedger,
    profileId: Int,
    simklAccountId: String,
    deviceId: String,
    expectedLocal: RewatchSession?,
    expectedGeneration: Long,
    canonicalSeriesId: String,
    remoteLookup: RewatchRemoteLookup,
    pendingOrUnpublished: Boolean,
    appliedAtEpochMs: Long,
    allowExplicitInitialTestAdoption: Boolean = false
): Pair<List<RewatchSession>, RewatchLocalSyncLedger>? {
    if (profileId <= 0 || canonicalSeriesId.isBlank() ||
        simklAccountId.isBlank() || deviceId.isBlank() ||
        pendingOrUnpublished || expectedGeneration != ledger.generation ||
        appliedAtEpochMs < 0L || ledger.generation >= Long.MAX_VALUE - 1) return null
    val verified = remoteLookup as? RewatchRemoteLookup.Verified ?: return null
    val checkpoint = verified.checkpoint as? RewatchRemoteCheckpoint.Synced ?: return null
    val remote = verified.remoteSession ?: return null
    if (remote.ownerKey != "local-profile:$profileId" ||
        key(remote.canonicalSeriesId) != key(canonicalSeriesId) ||
        checkpoint.sessionId != remote.sessionId ||
        remote.updatedAtEpochMs != 0L) return null

    val matches = sessions.withIndex().filter {
        key(it.value.canonicalSeriesId) == key(canonicalSeriesId)
    }
    if (matches.size > 1) return null
    val current = matches.singleOrNull()?.value
    if (current != expectedLocal) return null

    val proof = ledger.cleanBySeries[key(canonicalSeriesId)]
    if (current == null) {
        // A truly empty profile can adopt a verified remote replay. Do not
        // resurrect a replay which had a clean marker but was locally deleted.
        if (proof != null) return null
    } else {
        if (proof == null) {
            // Opt-in migration is authorized by the test-only DataStore guard,
            // never from an ordinary network GET or an unbound client.
            if (!allowExplicitInitialTestAdoption) return null
        } else if (proof.simklAccountId != simklAccountId ||
            proof.deviceId != deviceId ||
            proof.generation != ledger.generation ||
            !sameRewatchCursor(proof.cursor, current) ||
            checkpoint.revision <= proof.revision) return null
    }

    val applied = remote.copy(
        updatedAtEpochMs = maxOf(appliedAtEpochMs, current?.updatedAtEpochMs ?: 0L)
    )
    val updatedSessions = if (current == null) sessions + applied
        else sessions.toMutableList().apply {
            set(matches.single().index, applied)
        }
    val generation = ledger.generation + 1L
    val clean = RewatchLocalCleanProof(
        simklAccountId, deviceId, checkpoint.revision, generation, applied
    )
    return updatedSessions to ledger.copy(
        generation = generation,
        cleanBySeries = ledger.cleanBySeries + (key(canonicalSeriesId) to clean)
    )
}

/**
 * Test-only, user-approved initial adoption. Android already has a local-only
 * S5E16 Office cursor while the same authenticated Simkl account has S5E6 on
 * the VPS. Never generalize this into "server always wins" sync behavior.
 *
 * The caller additionally checks a durable per-profile one-time marker,
 * atomic local generation, matching authenticated identity, and empty outbox.
 */
internal fun isApprovedOfficeInitialTestAdoption(
    local: RewatchSession?,
    lookup: RewatchRemoteLookup,
    alreadyAdopted: Boolean,
    pending: Boolean
): Boolean {
    val verified = lookup as? RewatchRemoteLookup.Verified ?: return false
    val remote = verified.remoteSession ?: return false
    return !alreadyAdopted && !pending &&
        verified.checkpoint is RewatchRemoteCheckpoint.Synced &&
        local != null &&
        local.canonicalSeriesId.trim().equals("tt0386676", ignoreCase = true) &&
        local.canonicalSeriesId.trim().equals(
            remote.canonicalSeriesId.trim(), ignoreCase = true
        ) &&
        local.sessionId != remote.sessionId &&
        local.episode.season == 5 && local.episode.episode == 16 &&
        remote.episode.season == 5 && remote.episode.episode == 6 &&
        local.phase in setOf(
            com.nuvio.tv.domain.rewatch.RewatchPhase.PLAYING,
            com.nuvio.tv.domain.rewatch.RewatchPhase.NEXT_UP
        ) &&
        remote.phase == com.nuvio.tv.domain.rewatch.RewatchPhase.PLAYING
}
