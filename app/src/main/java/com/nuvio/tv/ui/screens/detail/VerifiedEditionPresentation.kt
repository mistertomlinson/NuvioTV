package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.StreamingOriginalsEditionRoute

/**
 * Overlay only first-party, provider-verified edition presentation fields.
 * Never change the canonical ID, episode list, cast, videos or history keys.
 */
internal fun applyVerifiedEditionPresentation(
    base: Meta,
    source: Meta?,
    canonicalId: String,
    route: StreamingOriginalsEditionRoute?
): Meta {
    if (route == null || route.canonicalId != canonicalId || source == null ||
        source.id != canonicalId || source.apiType != "movie" ||
        source.name.isBlank() || source.poster.isNullOrBlank() ||
        source.runtime.isNullOrBlank()
    ) return base

    return base.copy(
        name = source.name,
        poster = source.poster,
        logo = source.logo?.takeIf { it.isNotBlank() } ?: base.logo,
        description = source.description?.takeIf { it.isNotBlank() } ?: base.description,
        runtime = source.runtime,
        background = source.background ?: base.background,
        originalBackground = source.background ?: base.originalBackground
    )
}
