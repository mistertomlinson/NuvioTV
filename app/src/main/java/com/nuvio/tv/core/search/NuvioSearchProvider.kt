package com.nuvio.tv.core.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Global search suggestions provider for Android TV / Gemini.
 * Queries search-capable addon catalogs and returns suggestion rows
 * that deep-link via nuvio://detail/{id}/{type}?addonBaseUrl=...
 */
class NuvioSearchProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun addonRepository(): AddonRepository
        fun catalogRepository(): CatalogRepository
    }

    private val columns = arrayOf(
        BaseColumns._ID,
        SearchManager.SUGGEST_COLUMN_TEXT_1,
        SearchManager.SUGGEST_COLUMN_TEXT_2,
        SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
        SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
        SearchManager.SUGGEST_COLUMN_INTENT_DATA
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(columns)
        val raw = uri.lastPathSegment ?: return cursor
        if (raw == SearchManager.SUGGEST_URI_PATH_QUERY) return cursor
        val query = Uri.decode(raw).trim()
        if (query.length < 2) return cursor
        val appContext = context?.applicationContext ?: return cursor
        val deps = EntryPointAccessors.fromApplication(appContext, Deps::class.java)

        runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                val addons = runCatching {
                    deps.addonRepository().getInstalledAddons().first()
                }.getOrNull() ?: return@withTimeoutOrNull

                val targets = addons
                    .filter { it.enabled }
                    .flatMap { addon ->
                        addon.catalogs
                            .filter { it.supportsExtra("search") }
                            .map { addon to it }
                    }
                val addonIds = targets.map { it.first.id }.distinct().take(MAX_ADDONS)
                val limited = targets.filter { it.first.id in addonIds }
                if (limited.isEmpty()) return@withTimeoutOrNull

                val rows = coroutineScope {
                    limited.map { (addon, catalog) ->
                        async {
                            runCatching {
                                deps.catalogRepository().getCatalog(
                                    addonBaseUrl = addon.baseUrl,
                                    addonId = addon.id,
                                    addonName = addon.displayName,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name,
                                    type = catalog.apiType,
                                    skip = 0,
                                    skipStep = 100,
                                    extraArgs = mapOf("search" to query),
                                    supportsSkip = false
                                ).mapNotNull { (it as? NetworkResult.Success)?.data }
                                    .firstOrNull()
                            }.getOrNull()
                        }
                    }.awaitAll().filterNotNull()
                }

                val seen = HashSet<String>()
                var id = 0L
                outer@ for (row in rows) {
                    for (item in row.items) {
                        if (!seen.add(item.name.lowercase())) continue
                        val deepLink = Uri.Builder()
                            .scheme("nuvio")
                            .authority("detail")
                            .appendPath(item.id)
                            .appendPath(item.rawType)
                            .appendQueryParameter("addonBaseUrl", row.addonBaseUrl)
                            .build()
                            .toString()
                        cursor.addRow(
                            arrayOf<Any?>(
                                id++,
                                item.name,
                                item.releaseInfo ?: item.description.orEmpty(),
                                item.poster.orEmpty(),
                                "video/*",
                                deepLink
                            )
                        )
                        if (id >= MAX_RESULTS) break@outer
                    }
                }
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    private companion object {
        const val TIMEOUT_MS = 4500L
        const val MAX_ADDONS = 2
        const val MAX_RESULTS = 10
    }
}
