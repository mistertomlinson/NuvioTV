package com.nuvio.tv.domain.model

enum class CatalogPosterMode {
    PORTRAIT, LANDSCAPE, XL_WITH_SUMMARY, XL_WITHOUT_SUMMARY;

    fun next(): CatalogPosterMode = entries[(ordinal + 1) % entries.size]

    val isXl: Boolean get() = this == XL_WITH_SUMMARY || this == XL_WITHOUT_SUMMARY

}

fun CatalogPosterMode?.usesLandscape(defaultLandscape: Boolean): Boolean =
    if (this == null) defaultLandscape else this == CatalogPosterMode.LANDSCAPE
