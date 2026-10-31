package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.reminders.ReleaseReminderWorker
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ReleaseReminderDataStore
import com.nuvio.tv.data.local.ReleaseReminderRecord
import com.nuvio.tv.data.local.ReleaseReminderStatus
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaBehaviorHints
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReleaseReminderHomeFreezeTest {

    private val contentId = "tt9876543"
    private val addonBaseUrl = "https://release-authority.example"

    private lateinit var context: Context
    private lateinit var dataStore: ReleaseReminderDataStore
    private lateinit var metaRepository: MetaRepository
    private lateinit var tmdbService: TmdbService
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var watchProgressRepository: WatchProgressRepository
    private lateinit var watchedSeriesStateHolder: WatchedSeriesStateHolder
    private lateinit var profileManager: ProfileManager

    private val activeProfileId = MutableStateFlow(1)
    private val watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    private val fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())

    private var stored: ReleaseReminderRecord? = null
    private var freshMeta: Meta? = null
    private var libraryWrites = 0

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        dataStore = mockk()
        metaRepository = mockk()
        tmdbService = mockk(relaxed = true)
        libraryRepository = mockk(relaxed = true)
        watchProgressRepository = mockk()
        watchedSeriesStateHolder = mockk()
        profileManager = mockk()

        activeProfileId.value = 1
        watchedMovieIds.value = emptySet()
        fullyWatchedSeriesIds.value = emptySet()
        stored = null
        freshMeta = null
        libraryWrites = 0

        every { profileManager.activeProfileId } returns activeProfileId
        every { watchProgressRepository.observeWatchedMovieIds() } returns watchedMovieIds
        every {
            watchedSeriesStateHolder.observeFullyWatchedSeriesIds(any())
        } returns fullyWatchedSeriesIds

        every { dataStore.reminders } answers {
            flowOf(stored?.let(::listOf).orEmpty())
        }

        coEvery { dataStore.upsert(any()) } coAnswers {
            stored = firstArg()
        }

        coEvery { dataStore.markFulfilled(any(), any()) } coAnswers {
            val key = firstArg<String>()
            val fulfilledAt = secondArg<Long>()
            stored = stored?.let { record ->
                if (record.key == key) {
                    record.copy(
                        status = ReleaseReminderStatus.FULFILLED,
                        fulfilledAtMillis = fulfilledAt,
                        membershipConfirmed = true
                    )
                } else {
                    record
                }
            }
        }

        coEvery { dataStore.markArmed(any()) } coAnswers {
            val key = firstArg<String>()
            stored = stored?.let { record ->
                if (record.key == key) {
                    record.copy(
                        status = ReleaseReminderStatus.ARMED,
                        fulfilledAtMillis = null,
                        membershipConfirmed = false
                    )
                } else {
                    record
                }
            }
        }

        coEvery { dataStore.remove(any<String>()) } coAnswers {
            val key = firstArg<String>()
            if (stored?.key == key) stored = null
        }

        every {
            metaRepository.getMetaFresh(any(), any(), any())
        } answers {
            val meta = freshMeta
            if (meta == null) {
                flowOf<NetworkResult<Meta>>(
                    NetworkResult.Error("No authority result")
                )
            } else {
                flowOf<NetworkResult<Meta>>(
                    NetworkResult.Success(meta)
                )
            }
        }

        coEvery { libraryRepository.ensureInDefault(any()) } coAnswers {
            libraryWrites += 1
            true
        }

        mockkObject(ReleaseReminderWorker.Companion)
        every {
            ReleaseReminderWorker.scheduleDue(any(), any())
        } just Runs

        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun confirmedReleaseWhileHomeVisible_waitsToFulfillUntilHomeExit() = runTest {
        stored = reminder(
            releaseDate = today().minusDays(1).toString()
        )
        freshMeta = releasedAuthority()

        val coordinator = coordinator()
        coordinator.setHomeVisible(true)

        coordinator.runDueMaintenance()

        assertEquals(ReleaseReminderStatus.ARMED, stored?.status)
        assertNotNull(stored?.lastAuthorityCheckAtMillis)
        assertEquals(0, libraryWrites)

        coordinator.setHomeVisible(false)
        coordinator.runDueMaintenance()

        assertEquals(ReleaseReminderStatus.FULFILLED, stored?.status)
        assertTrue(stored?.membershipConfirmed == true)
        assertEquals(1, libraryWrites)
    }

    @Test
    fun watchedReminderWhileHomeVisible_waitsToRemoveUntilHomeExit() = runTest {
        stored = reminder(
            releaseDate = today().plusDays(30).toString()
        )
        watchedMovieIds.value = setOf(contentId)

        val coordinator = coordinator()
        coordinator.setHomeVisible(true)

        coordinator.runWatchedReminderMaintenance()

        assertNotNull(stored)
        assertEquals(ReleaseReminderStatus.ARMED, stored?.status)

        coordinator.setHomeVisible(false)
        coordinator.runDueMaintenance()

        assertNull(stored)
        assertEquals(0, libraryWrites)
    }

    private fun coordinator(): ReleaseReminderCoordinator =
        ReleaseReminderCoordinator(
            appContext = context,
            dataStore = dataStore,
            metaRepository = metaRepository,
            tmdbService = tmdbService,
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            profileManager = profileManager
        )

    private fun reminder(
        releaseDate: String
    ): ReleaseReminderRecord =
        ReleaseReminderRecord(
            itemId = contentId,
            itemType = ContentType.MOVIE.toApiString(),
            title = "Freeze Test",
            imdbId = contentId,
            addonBaseUrl = addonBaseUrl,
            releaseDate = releaseDate,
            platformId = "netflix"
        )

    private fun releasedAuthority(): Meta =
        Meta(
            id = contentId,
            type = ContentType.MOVIE,
            name = "Freeze Test",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = contentId,
            behaviorHints = MetaBehaviorHints(
                platformId = "netflix",
                releaseReminderState = "released"
            )
        )

    private fun today(): LocalDate =
        LocalDate.now(ZoneId.systemDefault())
}
