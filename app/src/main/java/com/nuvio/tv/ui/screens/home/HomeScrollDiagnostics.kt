package com.nuvio.tv.ui.screens.home

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Temporary debug-only vertical-scroll diagnostics.
 *
 * Hot paths use atomic counters only. One aggregate summary is logged when
 * each vertical movement ends.
 */
internal object HomeScrollDiagnostics {
    const val TAG = "NuvioHomeDiag"

    // Temporary diagnostic branch: always enabled.
    private const val enabled = true

    private val scrolling =
        AtomicBoolean(false)

    private val activeMessageLogged =
        AtomicBoolean(false)

    private val scrollStartedAtMs =
        AtomicLong(0L)

    private val catalogSchedules =
        AtomicInteger(0)

    private val catalogUpdates =
        AtomicInteger(0)

    private val tmdbPublications =
        AtomicInteger(0)

    private val externalPublications =
        AtomicInteger(0)

    private val imdbFallbacks =
        AtomicInteger(0)

    private val proactiveCompletions =
        AtomicInteger(0)

    private val posterSuccesses =
        AtomicInteger(0)

    private val logoSuccesses =
        AtomicInteger(0)

    private val viewportChanges =
        AtomicInteger(0)

    private val focusEvents =
        AtomicInteger(0)

    private val adjacentEvents =
        AtomicInteger(0)

    private val selectionEvents =
        AtomicInteger(0)

    private val longestCatalogUs =
        AtomicLong(0L)

    private val longestPublicationUs =
        AtomicLong(0L)

    private val longestImdbUs =
        AtomicLong(0L)

    private val longestProactiveUs =
        AtomicLong(0L)

    @Volatile
    private var lastCatalogSummary =
        "none"

    @Volatile
    private var lastViewport =
        "none"

    private fun updateMaximum(
        target: AtomicLong,
        candidate: Long
    ) {
        while (true) {
            val previous = target.get()

            if (candidate <= previous) {
                return
            }

            if (
                target.compareAndSet(
                    previous,
                    candidate
                )
            ) {
                return
            }
        }
    }

    private fun resetMovementCounters() {
        catalogSchedules.set(0)
        catalogUpdates.set(0)
        tmdbPublications.set(0)
        externalPublications.set(0)
        imdbFallbacks.set(0)
        proactiveCompletions.set(0)
        posterSuccesses.set(0)
        logoSuccesses.set(0)
        viewportChanges.set(0)
        focusEvents.set(0)
        adjacentEvents.set(0)
        selectionEvents.set(0)
        longestCatalogUs.set(0L)
        longestPublicationUs.set(0L)
        longestImdbUs.set(0L)
        longestProactiveUs.set(0L)
        lastCatalogSummary = "none"
        lastViewport = "none"
    }

    fun recordScrollState(
        isScrolling: Boolean,
        firstVisibleRow: Int,
        firstVisibleOffset: Int
    ) {
        if (!enabled) return

        if (
            activeMessageLogged.compareAndSet(
                false,
                true
            )
        ) {
            Log.w(
                TAG,
                "DIAGNOSTICS_ACTIVE build=NO_VIEWPORT_OBSERVER_44B8F8C3"
            )
        }

        val previous =
            scrolling.getAndSet(isScrolling)

        if (
            isScrolling &&
            !previous
        ) {
            resetMovementCounters()

            scrollStartedAtMs.set(
                SystemClock.elapsedRealtime()
            )

            Log.w(
                TAG,
                "SCROLL_START" +
                    " row=$firstVisibleRow" +
                    " offset=$firstVisibleOffset"
            )

            return
        }

        if (
            !isScrolling &&
            previous
        ) {
            val durationMs =
                (
                    SystemClock.elapsedRealtime() -
                        scrollStartedAtMs.get()
                    )
                    .coerceAtLeast(0L)

            Log.w(
                TAG,
                buildString {
                    append("SCROLL_END")
                    append(" durationMs=")
                    append(durationMs)

                    append(" row=")
                    append(firstVisibleRow)

                    append(" offset=")
                    append(firstVisibleOffset)

                    append(" schedules=")
                    append(catalogSchedules.get())

                    append(" catalogUpdates=")
                    append(catalogUpdates.get())

                    append(" catalogMaxUs=")
                    append(longestCatalogUs.get())

                    append(" tmdbPublications=")
                    append(tmdbPublications.get())

                    append(" externalPublications=")
                    append(externalPublications.get())

                    append(" publicationMaxUs=")
                    append(longestPublicationUs.get())

                    append(" imdbFallbacks=")
                    append(imdbFallbacks.get())

                    append(" imdbMaxUs=")
                    append(longestImdbUs.get())

                    append(" proactive=")
                    append(proactiveCompletions.get())

                    append(" proactiveMaxUs=")
                    append(longestProactiveUs.get())

                    append(" posters=")
                    append(posterSuccesses.get())

                    append(" logos=")
                    append(logoSuccesses.get())

                    append(" viewportChanges=")
                    append(viewportChanges.get())

                    append(" focus=")
                    append(focusEvents.get())

                    append(" adjacent=")
                    append(adjacentEvents.get())

                    append(" selection=")
                    append(selectionEvents.get())

                    append(" catalogState=")
                    append(lastCatalogSummary)

                    append(" viewport=")
                    append(lastViewport)
                }
            )
        }
    }

    fun recordCatalogSchedule() {
        if (!enabled) return
        catalogSchedules.incrementAndGet()
    }

    fun recordCatalogUpdate(
        durationNs: Long,
        beforeRows: Int,
        beforeItems: Int,
        afterRows: Int,
        afterItems: Int
    ) {
        if (!enabled) return

        val durationUs =
            durationNs.coerceAtLeast(0L) /
                1_000L

        catalogUpdates.incrementAndGet()

        updateMaximum(
            longestCatalogUs,
            durationUs
        )

        lastCatalogSummary =
            "${beforeRows}r/${beforeItems}i" +
                "->${afterRows}r/${afterItems}i"

        if (scrolling.get()) {
            Log.w(
                TAG,
                "DURING_SCROLL catalogUpdateUs=$durationUs"
            )
        }
    }

    fun recordPublication(
        kind: String,
        durationNs: Long
    ) {
        if (!enabled) return

        val durationUs =
            durationNs.coerceAtLeast(0L) /
                1_000L

        when (kind) {
            "tmdb" ->
                tmdbPublications.incrementAndGet()

            "external" ->
                externalPublications.incrementAndGet()
        }

        updateMaximum(
            longestPublicationUs,
            durationUs
        )

        if (scrolling.get()) {
            Log.w(
                TAG,
                "DURING_SCROLL publication=$kind" +
                    " durationUs=$durationUs"
            )
        }
    }

    fun recordImdbFallback(
        durationNs: Long
    ) {
        if (!enabled) return

        val durationUs =
            durationNs.coerceAtLeast(0L) /
                1_000L

        imdbFallbacks.incrementAndGet()

        updateMaximum(
            longestImdbUs,
            durationUs
        )

        if (scrolling.get()) {
            Log.w(
                TAG,
                "DURING_SCROLL imdbFallbackUs=$durationUs"
            )
        }
    }

    fun recordProactiveEnrichment(
        durationNs: Long
    ) {
        if (!enabled) return

        val durationUs =
            durationNs.coerceAtLeast(0L) /
                1_000L

        proactiveCompletions.incrementAndGet()

        updateMaximum(
            longestProactiveUs,
            durationUs
        )
    }

    fun recordViewport(
        rowKeys: List<String>
    ) {
        if (!enabled) return

        viewportChanges.incrementAndGet()

        lastViewport =
            rowKeys.joinToString(
                separator = ",",
                limit = 4,
                truncated = "..."
            )
    }

    fun recordLanding(
        stage: String
    ) {
        if (!enabled) return

        when (stage) {
            "focus" ->
                focusEvents.incrementAndGet()

            "adjacent" ->
                adjacentEvents.incrementAndGet()

            "selection" ->
                selectionEvents.incrementAndGet()
        }
    }

    fun recordImageSuccess(
        kind: String
    ) {
        if (!enabled) return

        when (kind) {
            "poster" ->
                posterSuccesses.incrementAndGet()

            "logo" ->
                logoSuccesses.incrementAndGet()
        }
    }
}
