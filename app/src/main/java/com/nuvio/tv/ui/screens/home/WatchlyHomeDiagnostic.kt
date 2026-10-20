package com.nuvio.tv.ui.screens.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nuvio.tv.domain.model.CatalogRow
import java.lang.ref.WeakReference
import java.security.MessageDigest

private const val WATCHLY_DIAG_TAG = "WatchlyHomeDiag"

internal object WatchlyHomeDiagnosticRegistry {
    @Volatile
    private var activeHomeViewModel:
        WeakReference<HomeViewModel>? = null

    fun attach(viewModel: HomeViewModel) {
        activeHomeViewModel =
            WeakReference(viewModel)
    }

    fun detach(viewModel: HomeViewModel) {
        if (
            activeHomeViewModel
                ?.get() === viewModel
        ) {
            activeHomeViewModel = null
        }
    }

    fun dump(): String {
        val viewModel =
            activeHomeViewModel
                ?.get()
                ?: return "RESULT=NO_ACTIVE_HOME_VIEW_MODEL"

        return viewModel
            .buildWatchlyHomeDiagnosticDump()
    }
}

private fun diagnosticFingerprint(
    value: String?
): String {
    val normalized =
        value
            ?.trim()
            ?.trimEnd('/')
            ?.lowercase()
            .orEmpty()

    if (normalized.isBlank()) {
        return "blank"
    }

    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                normalized.toByteArray(
                    Charsets.UTF_8
                )
            )

    return digest
        .take(8)
        .joinToString("") { byte ->
            "%02x".format(
                byte.toInt() and 0xff
            )
        }
}

private fun cleanDiagnosticText(
    value: String?
): String =
    value
        .orEmpty()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('|', '/')
        .trim()

private fun sampleTitles(
    row: CatalogRow
): String =
    row.items
        .take(5)
        .joinToString(" || ") { item ->
            cleanDiagnosticText(
                item.name
            )
        }

internal fun HomeViewModel
    .buildWatchlyHomeDiagnosticDump():
    String {

    val state =
        _uiState.value

    val visibleRows =
        state.catalogRows
            .filter { row ->
                row.addonId ==
                    WATCHLY_ADDON_ID
            }

    val mapRows =
        synchronized(catalogsMap) {
            catalogsMap
                .entries
                .filter { entry ->
                    entry.value.addonId ==
                        WATCHLY_ADDON_ID
                }
                .map { entry ->
                    entry.key to entry.value
                }
        }

    val sourceRows =
        synchronized(catalogSourceRows) {
            catalogSourceRows
                .entries
                .filter { entry ->
                    entry.value.addonId ==
                        WATCHLY_ADDON_ID
                }
                .map { entry ->
                    entry.key to entry.value
                }
        }

    val watchlyAddons =
        addonsCache
            .filter { addon ->
                addon.id ==
                    WATCHLY_ADDON_ID
            }

    return buildString {
        appendLine("RESULT=OK")
        appendLine(
            "ACTIVE_PROFILE=" +
                profileManager
                    .activeProfileId
                    .value
        )
        appendLine(
            "HOME_LOAD_SESSION=" +
                state.homeLoadSessionId
        )
        appendLine(
            "UI_LOADING=" +
                state.isLoading
        )

        appendLine()
        appendLine(
            "===== ADDON CACHE ====="
        )
        appendLine(
            "WATCHLY_ADDONS=" +
                watchlyAddons.size
        )

        watchlyAddons
            .forEachIndexed {
                    addonIndex,
                    addon ->

                appendLine(
                    "ADDON[$addonIndex] " +
                        "urlfp=" +
                        diagnosticFingerprint(
                            addon.baseUrl
                        ) +
                        " catalogs=" +
                        addon.catalogs.size
                )

                addon.catalogs
                    .filter { catalog ->
                        catalog.id
                            .startsWith(
                                "watchly.theme."
                            )
                    }
                    .forEachIndexed {
                            catalogIndex,
                            catalog ->

                        appendLine(
                            "  MANIFEST[$catalogIndex] " +
                                "type=" +
                                cleanDiagnosticText(
                                    catalog.apiType
                                ) +
                                " id=" +
                                cleanDiagnosticText(
                                    catalog.id
                                ) +
                                " name=" +
                                cleanDiagnosticText(
                                    catalog.name
                                )
                        )
                    }
            }

        appendLine()
        appendLine(
            "===== VISIBLE HOME ROWS ====="
        )
        appendLine(
            "VISIBLE_COUNT=" +
                visibleRows.size
        )

        visibleRows
            .forEachIndexed {
                    index,
                    row ->

                val key =
                    catalogKey(
                        addonId = row.addonId,
                        type = row.apiType,
                        catalogId = row.catalogId
                    )

                appendLine(
                    "VISIBLE[$index] " +
                        "key=" + key +
                        " type=" +
                        cleanDiagnosticText(
                            row.apiType
                        ) +
                        " id=" +
                        cleanDiagnosticText(
                            row.catalogId
                        ) +
                        " name=" +
                        cleanDiagnosticText(
                            row.catalogName
                        ) +
                        " urlfp=" +
                        diagnosticFingerprint(
                            row.addonBaseUrl
                        ) +
                        " items=" +
                        row.items.size
                )

                appendLine(
                    "  sample=" +
                        sampleTitles(row)
                )
            }

        appendLine()
        appendLine(
            "===== CATALOG MAP ====="
        )
        appendLine(
            "MAP_COUNT=" +
                mapRows.size
        )

        mapRows
            .forEachIndexed {
                    index,
                    pair ->

                val key = pair.first
                val row = pair.second

                appendLine(
                    "MAP[$index] " +
                        "key=" + key +
                        " type=" +
                        cleanDiagnosticText(
                            row.apiType
                        ) +
                        " id=" +
                        cleanDiagnosticText(
                            row.catalogId
                        ) +
                        " name=" +
                        cleanDiagnosticText(
                            row.catalogName
                        ) +
                        " urlfp=" +
                        diagnosticFingerprint(
                            row.addonBaseUrl
                        ) +
                        " items=" +
                        row.items.size
                )

                appendLine(
                    "  sample=" +
                        sampleTitles(row)
                )
            }

        appendLine()
        appendLine(
            "===== SOURCE ROWS ====="
        )
        appendLine(
            "SOURCE_COUNT=" +
                sourceRows.size
        )

        sourceRows
            .forEachIndexed {
                    index,
                    pair ->

                val key = pair.first
                val row = pair.second

                appendLine(
                    "SOURCE[$index] " +
                        "key=" + key +
                        " type=" +
                        cleanDiagnosticText(
                            row.apiType
                        ) +
                        " id=" +
                        cleanDiagnosticText(
                            row.catalogId
                        ) +
                        " name=" +
                        cleanDiagnosticText(
                            row.catalogName
                        ) +
                        " urlfp=" +
                        diagnosticFingerprint(
                            row.addonBaseUrl
                        ) +
                        " items=" +
                        row.items.size
                )

                appendLine(
                    "  sample=" +
                        sampleTitles(row)
                )
            }
    }
}

class WatchlyHomeDiagnosticReceiver :
    BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (
            intent.action !=
            ACTION_DUMP
        ) {
            return
        }

        val dump =
            WatchlyHomeDiagnosticRegistry
                .dump()

        Log.i(
            WATCHLY_DIAG_TAG,
            "===== BEGIN WATCHLY HOME DUMP ====="
        )

        dump
            .lineSequence()
            .forEach { line ->
                Log.i(
                    WATCHLY_DIAG_TAG,
                    line
                )
            }

        Log.i(
            WATCHLY_DIAG_TAG,
            "===== END WATCHLY HOME DUMP ====="
        )

        setResultData(dump)
    }

    companion object {
        const val ACTION_DUMP =
            "com.nuvio.tv.watchlydiag.DUMP"
    }
}
