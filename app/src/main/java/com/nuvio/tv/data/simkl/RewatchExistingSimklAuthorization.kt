package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.rewatch.ExistingSimklRewatchAuthorization
import javax.inject.Inject

/**
 * Read-only adapter to the existing encrypted, profile-scoped Simkl login.
 * Never starts a PIN flow, writes a token, or updates a tracking-provider item.
 *
 * A profile switch changes the auth scope generation. Double-checking it
 * stops a stale snapshot from being sent to the replay bootstrap.
 */
internal class RewatchExistingSimklAuthorization @Inject constructor(
    private val storage: SimklAuthStorage
) {
    fun snapshot(): ExistingSimklRewatchAuthorization? {
        val authorization = storage.authorization() ?: return null
        if (!storage.isCurrent(authorization.scope)) return null
        val state = storage.state.value
        if (!state.isAuthenticated) return null
        val current = storage.authorization() ?: return null
        if (current != authorization || !storage.isCurrent(current.scope)) return null
        return ExistingSimklRewatchAuthorization(
            profileId = current.scope.profileId,
            accessToken = current.accessToken,
            accountId = state.accountId
        )
    }
}
