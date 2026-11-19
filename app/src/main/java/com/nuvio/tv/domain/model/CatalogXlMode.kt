package com.nuvio.tv.domain.model

enum class CatalogXlMode {
    OFF, WITH_SUMMARY, WITHOUT_SUMMARY;

    fun next(): CatalogXlMode = when (this) {
        OFF -> WITH_SUMMARY
        WITH_SUMMARY -> WITHOUT_SUMMARY
        WITHOUT_SUMMARY -> OFF
    }
}
