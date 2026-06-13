package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDebridAvailabilityService @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val localDebridService: LocalDebridService
) {
    suspend fun markChecking(groups: List<AddonStreams>): List<AddonStreams> {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return groups
        val account = accounts.first()
        return groups.updateAvailabilityStatus { stream ->
            if (stream.localAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = StreamDebridCacheState.CHECKING
                    )
                )
            }
        }
    }

    suspend fun annotateCachedAvailability(groups: List<AddonStreams>): List<AddonStreams> {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return groups
        val hashes = groups.flatMap { group ->
            group.streams.mapNotNull { stream ->
                stream.localAvailabilityHash()
                    ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
            }
        }.distinct()
        if (hashes.isEmpty()) return groups

        // Check cache for all configured providers in parallel.
        // For each provider, produce annotated copies of streams — allowing the same
        // torrent to appear once per provider that has it cached.
        val cachedByProvider = kotlinx.coroutines.coroutineScope {
            accounts.map { account ->
                async {
                    val result = localDebridService.checkCached(account = account, hashes = hashes)
                    if (result != null) account to result else null
                }
            }.mapNotNull { it.await() }
        }

        if (cachedByProvider.isEmpty()) {
            val account = accounts.first()
            return groups.updateAvailabilityStatus { stream ->
                val hash = stream.localAvailabilityHash()
                if (hash == null) stream
                else stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = StreamDebridCacheState.UNKNOWN
                    )
                )
            }
        }

        // Expand each stream into one copy per provider that has it cached
        return groups.map { group ->
            var changed = false
            val expandedStreams = mutableListOf<Stream>()
            group.streams.forEach { stream ->
                val hash = stream.localAvailabilityHash()
                if (hash == null || stream.debridCacheStatus?.state in FINAL_CACHE_STATES) {
                    expandedStreams.add(stream)
                    return@forEach
                }
                val matchingProviders = cachedByProvider.filter { (_, cachedMap) -> cachedMap.containsKey(hash) }
                if (matchingProviders.isEmpty()) {
                    // Not cached by any provider — annotate with first provider as NOT_CACHED
                    val (account, _) = cachedByProvider.first()
                    expandedStreams.add(stream.copy(
                        debridCacheStatus = StreamDebridCacheStatus(
                            providerId = account.provider.id,
                            providerName = account.provider.displayName,
                            state = StreamDebridCacheState.NOT_CACHED
                        )
                    ))
                    changed = true
                } else {
                    // Add one copy per provider that has it cached
                    matchingProviders.forEach { (account, cachedMap) ->
                        val cachedItem = cachedMap[hash]
                        expandedStreams.add(stream.copy(
                            debridCacheStatus = StreamDebridCacheStatus(
                                providerId = account.provider.id,
                                providerName = account.provider.displayName,
                                state = StreamDebridCacheState.CACHED,
                                cachedName = cachedItem?.name,
                                cachedSize = cachedItem?.size
                            )
                        ))
                        changed = true
                    }
                }
            }
            if (changed) group.copy(streams = expandedStreams) else group
        }
    }

    suspend fun isCached(hash: String): Boolean? {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return null
        return accounts.firstNotNullOfOrNull { account ->
            localDebridService.isCached(account, hash)?.takeIf { it }
        }
    }

    private suspend fun cacheCheckAccounts(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return emptyList()
        return settings.resolverServices
            .filter { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
    }
}

private val FINAL_CACHE_STATES = setOf(
    StreamDebridCacheState.CACHED,
    StreamDebridCacheState.NOT_CACHED
)

fun Stream.localAvailabilityHash(): String? =
    infoHash
        ?.trim()
        ?.lowercase()
        ?.takeIf { needsLocalDebridResolve() && it.isNotBlank() }

private fun List<AddonStreams>.updateAvailabilityStatus(
    transform: (Stream) -> Stream
): List<AddonStreams> =
    map { group ->
        var changed = false
        val updatedStreams = group.streams.map { stream ->
            val updated = transform(stream)
            if (updated != stream) changed = true
            updated
        }
        if (changed) group.copy(streams = updatedStreams) else group
    }
